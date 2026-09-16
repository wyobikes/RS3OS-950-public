package com.opennxt.model.commands.impl.mod

import com.opennxt.model.commands.CommandSender
import com.opennxt.model.commands.SimpleCommand
import com.opennxt.model.entity.player.PlayerInventory
import com.opennxt.model.items.Item
import com.opennxt.model.permissions.ModProfiles
import com.opennxt.model.permissions.Powers
import com.opennxt.model.permissions.Rights
import com.opennxt.model.world.TileLocation
import com.opennxt.model.world.WorldPlayer
import com.opennxt.resources.sqlite.RsDatabase
import com.opennxt.resources.sqlite.SqliteItemCodec
import com.opennxt.resources.sqlite.SqliteNpcCodec
import mu.KotlinLogging

private val logger = KotlinLogging.logger("ModCommands")

private fun modOrRefuse(sender: CommandSender, power: String): WorldPlayer? {
    if (sender !is WorldPlayer) {
        sender.error("This command can only be used by a player in the world.")
        return null
    }
    if (!sender.hasPermissions(power)) {
        sender.error(
            "You need the '$power' power to do that (granted in data/config/mods.json)."
        )
        return null
    }
    return sender
}

private fun resolveItem(sender: CommandSender, query: String): Pair<Int, String>? {
    val direct = query.toIntOrNull()
    if (direct != null) {
        val def = SqliteItemCodec.load(direct)
        return direct to (def?.name ?: "item $direct")
    }
    if (!RsDatabase.available) {
        sender.error("No definition database, so items can only be spawned by numeric id.")
        return null
    }
    val like = query.trim()
    val matches = RsDatabase.queryAll(
        "SELECT id, name FROM items WHERE name IS NOT NULL AND lower(name) = lower('${like.replace("'", "''")}') " +
            "ORDER BY id LIMIT 5"
    ) { it.getInt("id") to it.getString("name") }
    if (matches.size == 1) return matches[0]
    val fuzzy = RsDatabase.queryAll(
        "SELECT id, name FROM items WHERE name IS NOT NULL AND lower(name) LIKE lower('%${like.replace("'", "''")}%') " +
            "ORDER BY length(name), id LIMIT 6"
    ) { it.getInt("id") to it.getString("name") }
    if (fuzzy.isEmpty()) {
        sender.error("No item matches '$query'.")
        return null
    }
    if (fuzzy.size == 1) return fuzzy[0]
    sender.console("'$query' matches: " + fuzzy.joinToString { "${it.second} (${it.first})" } + " - be more specific.")
    return null
}

object ItemCommand : SimpleCommand() {
    internal fun parseItemArgs(args: String): Pair<String, Int> {
        val parts = args.trim().split(" ").filter { it.isNotEmpty() }
        val trailing = if (parts.size > 1) parts.last().toIntOrNull() else null
        val amount = (trailing ?: 1).coerceIn(1, Int.MAX_VALUE)
        val query = if (trailing != null) parts.dropLast(1).joinToString(" ") else parts.joinToString(" ")
        return query to amount
    }

    override fun execute(sender: CommandSender, alias: String, command: String) {
        val player = modOrRefuse(sender, Powers.SPAWN_ITEM) ?: return
        val args = command.trim()
        if (args.isEmpty()) {
            sender.error("Usage: ::item <id|name> [amount]   e.g. ::item 995 10000, ::item bronze pickaxe")
            return
        }
        val (query, amount) = parseItemArgs(args)
        val (id, name) = resolveItem(sender, query) ?: return

        val backpack = PlayerInventory.backpackOf(player)
        val result = backpack.add(Item(id, amount))
        val added = amount - result.remaining
        if (added > 0) PlayerInventory.sendBackpack(player)
        if (result.remaining > 0) {
            com.opennxt.OpenNXT.world.groundItems.spawnItem(
                itemId = id, itemName = name, quantity = result.remaining,
                tile = player.entity.location, owner = player.name, source = "mod:${player.name}"
            )
            sender.console("Spawned $name x$amount - $added to your backpack, ${result.remaining} on the floor.")
        } else {
            sender.console("Spawned $name x$added into your backpack.")
        }
        logger.warn { "MOD ${player.name} spawned item $id ($name) x$amount at ${player.entity.location}" }
    }
}

object NpcCommand : SimpleCommand() {
    override fun execute(sender: CommandSender, alias: String, command: String) {
        val player = modOrRefuse(sender, Powers.SPAWN_NPC) ?: return
        val args = command.trim()
        if (args.isEmpty()) {
            sender.error("Usage: ::npc <id|name> [count]   e.g. ::npc 758, ::npc goblin 3")
            return
        }
        val parts = args.split(" ")
        val trailing = parts.last().toIntOrNull()
        val count = (if (parts.size > 1) trailing ?: 1 else 1).coerceIn(1, 25)
        val query = if (trailing != null && parts.size > 1) parts.dropLast(1).joinToString(" ") else args

        val id = query.toIntOrNull() ?: run {
            if (!RsDatabase.available) {
                sender.error("No definition database, so npcs can only be spawned by numeric id.")
                return
            }
            val matches = RsDatabase.queryAll(
                "SELECT id, name FROM npcs WHERE name IS NOT NULL AND lower(name) LIKE " +
                    "lower('%${query.replace("'", "''")}%') ORDER BY length(name), id LIMIT 6"
            ) { it.getInt("id") to it.getString("name") }
            val exact = matches.firstOrNull { it.second.equals(query, true) }
            when {
                matches.isEmpty() -> { sender.error("No npc matches '$query'."); return }
                exact != null -> exact.first
                matches.size == 1 -> matches[0].first
                else -> {
                    sender.console("'$query' matches: " + matches.joinToString { "${it.second} (${it.first})" })
                    return
                }
            }
        }
        val def = SqliteNpcCodec.load(id)
        val name = def?.name ?: "npc $id"
        val at = player.entity.location
        val spawned = com.opennxt.OpenNXT.world.npcs.spawnAt(id, at, count)
        if (spawned <= 0) {
            sender.error("Could not spawn $name here.")
            return
        }
        sender.console("Spawned $name x$spawned at (${at.x}, ${at.y}, ${at.plane}).")
        logger.warn { "MOD ${player.name} spawned npc $id ($name) x$spawned at $at" }
    }
}

object BossCommand : SimpleCommand() {
    override fun execute(sender: CommandSender, alias: String, command: String) {
        val player = modOrRefuse(sender, Powers.SPAWN_NPC) ?: return
        val data = com.opennxt.model.combat.NpcBossData
        val query = command.trim()
        if (query.isEmpty()) {
            val placed = data.placedCountsByTier()
            sender.error("Usage: ::boss <name>   e.g. ::boss Kalphite Queen, ::boss Vorkath")
            sender.console(
                "${data.all().size} bosses known; ${placed.values.sum()} already spawned in the world " +
                    "(${placed.entries.joinToString(", ") { "${it.key} ${it.value}" }})."
            )
            return
        }
        val boss = data.byName(query) ?: run {
            val near = data.matches(query)
            if (near.isEmpty()) sender.error("No boss matches '$query'.")
            else sender.console("'$query' matches: " + near.joinToString { "${it.name} (${it.tier})" } + " - be more specific.")
            return
        }
        val id = boss.npcIds.firstOrNull() ?: run {
            sender.error("${boss.name} has no npc id in the cache and cannot be spawned.")
            return
        }
        val at = player.entity.location
        val spawned = com.opennxt.OpenNXT.world.npcs.spawnAt(id, at, 1)
        if (spawned <= 0) {
            sender.error("Could not spawn ${boss.name} here.")
            return
        }
        sender.console(
            "Spawned ${boss.name} (${boss.tier}, npc $id${if (boss.npcIds.size > 1)
                " of ${boss.npcIds.joinToString(",")} - ::npc <id> for another version" else ""}) " +
                "at (${at.x}, ${at.y}, ${at.plane})."
        )
        val lp = boss.lifepointsById[id]
        sender.console(
            "  ${lp ?: "no seeded"} lifepoints" + (boss.lifepointsLayerById[id]?.let { " (layer $it)" } ?: "") +
                ", drops " + (if (boss.hasDrops) "yes" else "none") +
                ", respawn " + (boss.respawnById[id]?.let { "${it.ticks} ticks (${it.source})" } ?: "default") +
                ", special " + (boss.specialMaxHitById[id]?.let { "max $it every ${boss.specialCadenceById[id] ?: "?"} ticks" } ?: "none") +
                ", phases " + (if (boss.phases.isEmpty()) "none" else "${boss.phases} (${boss.phaseSource})")
        )
        if (boss.notBuilt.isNotEmpty()) {
            sender.console(
                "  Not implemented for this encounter: ${boss.notBuilt.joinToString(", ")}."
            )
        }
        logger.warn {
            "MOD ${player.name} spawned boss ${boss.name} (${boss.tier}, npc $id) at $at"
        }
    }
}

object TeleCommand : SimpleCommand() {
    override fun execute(sender: CommandSender, alias: String, command: String) {
        val player = modOrRefuse(sender, Powers.TELEPORT) ?: return
        val args = command.trim().split(" ").filter { it.isNotBlank() }
        if (args.isEmpty()) {
            sender.error("Usage: ::tele <x> <y> [plane]   or   ::tele <player name>")
            return
        }
        val x = args[0].toIntOrNull() ?: return sender.error("Usage: ::tele <x> <y> [plane]  (see ::where)")
        val y = args.getOrNull(1)?.toIntOrNull() ?: return sender.error("Usage: ::tele <x> <y> [plane]")
        val plane = args.getOrNull(2)?.toIntOrNull() ?: player.entity.location.plane
        if (x !in 0..16383 || y !in 0..16383 || plane !in 0..3) {
            sender.error("Out of range: x and y are 0..16383 and plane is 0..3.")
            return
        }
        player.entity.movement.teleport(TileLocation(x, y, plane))
        sender.console("Teleported to ($x, $y, $plane).")
        logger.warn { "MOD ${player.name} teleported to ($x, $y, $plane)" }
    }
}

object WhereCommand : SimpleCommand() {
    override fun execute(sender: CommandSender, alias: String, command: String) {
        if (sender !is WorldPlayer) return sender.error("Only a player has a location.")
        val l = sender.entity.location
        sender.console("You are at (${l.x}, ${l.y}, ${l.plane}) - ::tele ${l.x} ${l.y} ${l.plane}")
    }
}

object LodestoneCommand : SimpleCommand() {
    val DEFAULTS: Map<String, Triple<Int, Int, Int>> = linkedMapOf(
        "lumbridge" to Triple(3233, 3221, 0),
        "burthorpe" to Triple(2899, 3544, 0),
        "varrock" to Triple(3214, 3376, 0),
        "falador" to Triple(2967, 3403, 0),
        "draynor" to Triple(3105, 3298, 0),
        "alkharid" to Triple(3298, 3184, 0),
        "edgeville" to Triple(3067, 3505, 0),
        "port sarim" to Triple(3011, 3215, 0),
        "seers" to Triple(2689, 3482, 0),
        "ardougne" to Triple(2634, 3348, 0),
        "catherby" to Triple(2831, 3451, 0),
        "yanille" to Triple(2529, 3094, 0),
        "canifis" to Triple(3517, 3515, 0),
        "taverley" to Triple(2887, 3443, 0),
        "karamja" to Triple(2761, 3148, 0),
        "lunar isle" to Triple(2085, 3914, 0),
        "eagles peak" to Triple(2366, 3479, 0),
        "fremennik" to Triple(2712, 3677, 0),
        "oo glog" to Triple(2532, 2871, 0),
        "tirannwn" to Triple(2254, 3149, 0),
        "wilderness" to Triple(3143, 3635, 0)
    )

    override fun execute(sender: CommandSender, alias: String, command: String) {
        val player = modOrRefuse(sender, Powers.LODESTONE) ?: return
        val query = command.trim().lowercase()
        if (query.isEmpty()) {
            sender.console("Lodestones: " + DEFAULTS.keys.joinToString(", "))
            sender.console("Use ::lodestone <name>.")
            return
        }
        val hit = DEFAULTS.entries.firstOrNull { it.key == query }
            ?: DEFAULTS.entries.firstOrNull { it.key.startsWith(query) }
            ?: DEFAULTS.entries.firstOrNull { it.key.contains(query) }
        if (hit == null) {
            sender.error("No lodestone matches '$query'. Try: " + DEFAULTS.keys.joinToString(", "))
            return
        }
        val (x, y, plane) = hit.value
        player.entity.movement.teleport(TileLocation(x, y, plane))
        sender.console("Teleported to the ${hit.key} lodestone ($x, $y, $plane).")
        logger.warn { "MOD ${player.name} used the ${hit.key} lodestone" }
    }
}

object ModCommand : SimpleCommand() {
    override fun execute(sender: CommandSender, alias: String, command: String) {
        val player = modOrRefuse(sender, Powers.GRANT) ?: return
        val args = command.trim().split(" ").filter { it.isNotBlank() }
        if (args.isEmpty()) {
            sender.console("${ModProfiles.size()} profile(s) in ${ModProfiles.path}. Usage: ::mod <name> [off]")
            return
        }
        val off = args.size > 1 && args.last().lowercase() in setOf("off", "false", "remove", "0")
        val target = (if (off) args.dropLast(1) else args).joinToString(" ")
        val rights = if (off) Rights.PLAYER else Rights.MOD
        if (!ModProfiles.grant(target, rights)) {
            sender.error("Could not write ${ModProfiles.path} - nothing changed.")
            return
        }
        sender.console(
            "$target is now ${if (off) "a player" else "a mod"}; takes effect on next login."
        )
        logger.warn { "MOD ${player.name} set $target to $rights" }
    }
}

object ModsCommand : SimpleCommand() {
    override fun execute(sender: CommandSender, alias: String, command: String) {
        val player = modOrRefuse(sender, Powers.GRANT) ?: return
        val n = ModProfiles.reload()
        sender.console("Re-read ${ModProfiles.path}: $n profile(s) in force.")
    }
}

private fun cosmeticItemName(id: Int): String? =
    RsDatabase.queryAll("SELECT name AS n FROM items WHERE id = $id") { it.getString("n") }.firstOrNull()

object CosmeticCommand : SimpleCommand() {
    override fun execute(sender: CommandSender, alias: String, command: String) {
        val player = modOrRefuse(sender, Powers.SPAWN_ITEM) ?: return
        val args = command.trim()
        if (args.isEmpty()) {
            val snap = player.cosmeticsSnapshot()
            if (snap.isEmpty()) sender.console("No cosmetic overrides. Usage: ::cosmetic <id|name>, ::cosmetic off <slot|all>")
            else sender.console("Cosmetics: " + snap.entries.joinToString(", ") { (slot, id) -> "slot $slot = $id (${cosmeticItemName(id) ?: "?"})" })
            return
        }
        val parts = args.split(" ")
        if (parts[0].equals("off", ignoreCase = true)) {
            val what = parts.getOrNull(1) ?: "all"
            if (what.equals("all", ignoreCase = true)) {
                sender.console("Cleared ${player.clearCosmetics()} cosmetic override(s).")
            } else {
                val slot = what.toIntOrNull() ?: run { sender.error("Usage: ::cosmetic off <slot|all>"); return }
                val prev = player.setCosmetic(slot, null)
                sender.console(if (prev == null) "Slot $slot had no override." else "Cleared slot $slot (was $prev).")
            }
            return
        }
        val (id, name) = resolveItem(sender, args) ?: return
        val slot = RsDatabase.queryAll("SELECT equipSlotId AS s FROM items WHERE id = $id") { it.getObject("s")?.toString()?.toIntOrNull() }
            .firstOrNull()
        if (slot == null || slot < 0 || slot >= PlayerInventory.WORN_SIZE) {
            sender.error("$name ($id) has no wear slot in the cache, so it cannot be shown on the body.")
            return
        }
        val prev = player.setCosmetic(slot, id)
        sender.console("Showing $name ($id) in slot $slot" + (if (prev != null) " (replaced $prev)" else "") + ". ::cosmetic off $slot to clear.")
        logger.warn { "MOD ${player.name} set cosmetic slot $slot = $id ($name)" }
    }
}

object MaxCommand : SimpleCommand() {
    override fun execute(sender: CommandSender, alias: String, command: String) {
        val player = modOrRefuse(sender, Powers.SPAWN_ITEM) ?: return
        var changed = 0
        for (stat in com.opennxt.api.stat.Stat.values()) {
            val level = com.opennxt.api.stat.maxLevel(stat)
            val xp = com.opennxt.api.stat.xpForLevel(stat, level).toDouble()
            if (player.stats.get(stat).experience >= xp) continue
            player.stats.set(stat, com.opennxt.impl.stat.PlayerStatData(stat, xp, level, level))
            changed++
        }
        player.stats.markDirty(); player.stats.clean()
        player.entity.model.dirty = true
        sender.console("Maxed $changed stat(s).")
        logger.warn { "MOD ${player.name} maxed $changed stat(s)" }
    }
}

object EmotesCommand : SimpleCommand() {
    override fun execute(sender: CommandSender, alias: String, command: String) {
        val player = modOrRefuse(sender, Powers.SPAWN_ITEM) ?: return
        if (com.opennxt.model.entity.rendering.UpdateBlockType.experimentalBuild() && System.getProperty("opennxt.mod.emotes950") != "on") {
            sender.error("::emotes is disabled on build 950 (-Dopennxt.mod.emotes950=on to force).")
            return
        }
        val (writes, skipped) = com.opennxt.content.impl.EmoteUnlocks.varpsForAll { player.varpValue(it) }
        var sent = 0
        for ((varp, value) in writes) {
            if (player.varpValue(varp) == value) continue
            player.setVarpOverride(varp, value); sent++
        }
        sender.console("Unlocked ${com.opennxt.content.impl.EmoteUnlocks.unlocks.size - skipped.size} emote(s) over ${writes.size} varp(s), $sent written now" +
            (if (skipped.isNotEmpty()) "; ${skipped.size} skipped: " + skipped.take(5).joinToString { it.emoteName } else "") +
            ". Reopen the emotes panel.")
        logger.warn { "MOD ${player.name} unlocked emotes: ${writes.size} varps, $sent sent, ${skipped.size} skipped" }
    }
}

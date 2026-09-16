package com.opennxt.content.skills

import com.opennxt.content.ContentPlayer
import com.opennxt.content.impl.ItemOps
import com.opennxt.content.impl.SkillingWiring
import com.opennxt.model.entity.player.PlayerInventory
import com.opennxt.model.entity.rendering.PlayerUpdates
import com.opennxt.model.entity.rendering.blocks.PlayerAnimationBlock
import com.opennxt.model.world.TileLocation
import com.opennxt.model.world.WorldPlayer
import com.opennxt.net.game.serverprot.MessageGame
import mu.KotlinLogging

object SkillsWiring {
    private val logger = KotlinLogging.logger { }

    fun bind(content: ContentPlayer, world: WorldPlayer) = SkillingWiring.bind(content, world)

    fun ownerOf(content: ContentPlayer): WorldPlayer? = SkillingWiring.ownerOf(content)

    @Volatile
    private var installed = false

    fun isInstalled(): Boolean = installed

    private val hook: (WorldPlayer, String, Int, String, Int) -> Boolean = { world, action, itemId, _, slot ->
        val content = world.contentPlayerAt()
        bind(content, world)
        val interaction = SkillInteractions.onBackpackOption(content, action, itemId, slot)
        if (interaction) true
        else {
            val r = ProductionActions.onBackpackOption(content, action, itemId, slot)
            r.outcome != ProductionActions.Outcome.NOT_MINE
        }
    }

    fun install(): String {
        ProductionActions.containerSupplier = { content ->
            val world = ownerOf(content)
            if (world == null) content.inventory else PlayerInventory.backpackOf(world)
        }
        ProductionActions.wornSupplier = { content ->
            val world = ownerOf(content)
            if (world == null) content.equipment.worn().values.map { it.id }
            else runCatching { world.worn.items().map { it.id } }.getOrDefault(emptyList())
        }
        ProductionActions.levelSupplier = { content, stat ->
            val world = ownerOf(content)
            if (world == null) 1 else runCatching { world.stats.getLevel(stat) }.getOrDefault(1)
        }
        ProductionActions.xpSink = { content, stat, amount ->
            ownerOf(content)?.stats?.addExperience(stat, amount, SkillingWiring.SKILLING_SOURCE)
        }
        ProductionActions.messageSink = { content, type, msg ->
            ownerOf(content)?.client?.write(MessageGame(type, msg))
        }
        ProductionActions.animationSink = { content, ids ->
            ownerOf(content)?.let { world -> PlayerUpdates.animate(world.entity, PlayerAnimationBlock(ids, 0)) }
        }
        ProductionActions.inventoryResend = { content ->
            val world = ownerOf(content)
            if (world != null) { PlayerInventory.sendBackpack(world); true } else false
        }
        ProductionActions.tileSupplier = { content ->
            val world = ownerOf(content)
            if (world == null) content.location
            else world.entity.location.let { TileLocation(it.x, it.y, it.plane) }
        }
        ProductionActions.nameResolver = { id -> SkillInteractions.itemName(id) }

        SkillInteractions.npcAlive = { index -> com.opennxt.content.impl.FishingWiring.npcAtIndex(index) != null }
        SkillInteractions.npcTile = { index ->
            com.opennxt.content.impl.FishingWiring.npcAtIndex(index)?.location?.let { intArrayOf(it.x, it.y, it.plane) }
        }
        SkillInteractions.messageByName = { name, msg -> byName(name)?.client?.write(MessageGame(0, msg)) }
        SkillInteractions.xpByName = { name, stat, amount ->
            byName(name)?.stats?.addExperience(stat, amount, SkillingWiring.SKILLING_SOURCE) != null
        }
        ProductionActions.nameResolverForMaterials = { id -> SkillInteractions.itemName(id) }
        MagicSpells.pouchDeposit = { content, coins ->
            val world = ownerOf(content)
            if (world == null || !com.opennxt.content.impl.MoneyPouch.enabled) false
            else if (world.coinPouch().toLong() + coins > Int.MAX_VALUE) false
            else {
                world.setCoinPouch(world.coinPouch() + coins)
                runCatching { com.opennxt.content.impl.MoneyPouch.sendTotal(world) }
                true
            }
        }
        SkillInteractions.levelByName = { name, stat -> byName(name)?.stats?.getLevel(stat, false) }
        SkillInteractions.tickHook = { attachKillListener() }

        if (!ItemOps.backpackActionHooks.contains(hook)) ItemOps.backpackActionHooks.add(hook)
        installed = true

        val bound = SkillInteractions.install()
        runCatching { MagicSpells.spells }
        val table = SkillRecipes.table
        val line = "skills: ${table.recipes.size} cache recipes over ${table.byStat.size} skills " +
            "(production engine ${if (ProductionActions.enabled) "ON" else "OFF"}, item-on-item via IF_BUTTONT), " +
            "$bound skill interaction binding(s)"
        logger.info { line }
        return line
    }

    private fun byName(name: String): WorldPlayer? {
        val world = runCatching { com.opennxt.OpenNXT.world }.getOrNull() ?: return null
        var found: WorldPlayer? = null
        world.forEachPlayer { if (found == null && it.name.equals(name, ignoreCase = true)) found = it }
        return found
    }

    @Volatile
    private var listenedTo: com.opennxt.model.world.WorldNpcs? = null

    private val killListener = com.opennxt.model.world.WorldNpcs.NpcDamageListener { npc, before, after, attacker ->
        if (before > 0 && after <= 0) SkillInteractions.onNpcKilled(attacker, npc.name)
    }

    private fun attachKillListener() {
        val npcs = runCatching { com.opennxt.OpenNXT.world.npcs }.getOrNull() ?: return
        if (listenedTo === npcs) return
        listenedTo?.removeDamageListener(killListener)
        npcs.addDamageListener(killListener)
        listenedTo = npcs
    }

    fun uninstall() {
        ItemOps.backpackActionHooks.remove(hook)
        listenedTo?.removeDamageListener(killListener)
        listenedTo = null
        SkillInteractions.uninstallListeners()
        MagicSpells.resetSeams()
        ProductionActions.resetSeams()
        SkillInteractions.resetSeams()
        installed = false
    }
}

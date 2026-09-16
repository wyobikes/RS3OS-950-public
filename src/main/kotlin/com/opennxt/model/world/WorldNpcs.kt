package com.opennxt.model.world

import com.opennxt.api.stat.Stat
import com.opennxt.model.combat.NpcDeathTransmission
import com.opennxt.model.combat.AttackDelay
import com.opennxt.model.combat.CombatFormulas
import com.opennxt.model.combat.Provenance
import com.opennxt.model.combat.SeededValue
import com.opennxt.model.combat.SeedData
import com.opennxt.model.combat.LifepointsLayer
import com.opennxt.model.drops.DropCategory
import com.opennxt.model.drops.DropData
import com.opennxt.model.drops.DropLine
import com.opennxt.model.entity.movement.CompassPoint
import com.opennxt.model.map.CollisionMap
import java.util.Random

class WorldNpcs {
    companion object {
        private val logger = mu.KotlinLogging.logger("WorldNpcs")

        const val WORLD_INDEX_BASE = 2

        val spawnsEnabled: Boolean = System.getProperty("opennxt.experiment.npcs.spawns") != "false"

        val combatDemoSpawn: TileLocation?
            get() {
                val raw = System.getProperty("opennxt.experiment.combat.demospawn")?.trim()
                    ?: return DEFAULT_COMBAT_DEMO_TILE
                if (raw.equals("off", true) || raw.equals("none", true) || raw.equals("false", true)) return null
                val parts = raw.split(',').map { it.trim().toIntOrNull() }
                if (parts.size < 2 || parts[0] == null || parts[1] == null) return null
                return TileLocation(parts[0]!!, parts[1]!!, parts.getOrNull(2)?.let { it } ?: 0)
            }

        val DEFAULT_COMBAT_DEMO_TILE: TileLocation
            get() = TileLocation(
                com.opennxt.model.entity.updating.NpcInfoEncoder.DEMO_X,
                com.opennxt.model.entity.updating.NpcInfoEncoder.DEMO_Y,
                com.opennxt.model.entity.updating.NpcInfoEncoder.DEMO_PLANE
            )

        val combatDemoSpawnCount: Int get() = if (combatDemoSpawn == null) 0 else 1

        val combatDemoWander: Boolean
            get() = System.getProperty("opennxt.experiment.combat.demospawn.wander") != "false"

        const val COMBAT_DEMO_NPC = 41

        const val COMBAT_DEMO_PROVENANCE =
            "combat demo npc $COMBAT_DEMO_NPC is placed near the login tile; set its tile with -Dopennxt.experiment.combat.demospawn"
    }

    @Volatile private var npcs: List<WorldNpc> = emptyList()
    @Volatile private var bySquare: Map<Int, List<WorldNpc>> = emptyMap()
    @Volatile private var byId: Map<Int, List<WorldNpc>> = emptyMap()

    @Volatile private var byIndex: Map<Int, WorldNpc> = emptyMap()

    @Volatile private var demoNpc: WorldNpc? = null

    fun combatDemoNpc(): WorldNpc? = demoNpc

    var tickCount = 0L
        private set

    fun ticks(): Long = tickCount

    fun populate(): Int {
        val demo = combatDemoSpawns()
        val count = populateFrom(if (spawnsEnabled) NpcSpawnData.spawns() + demo else demo)
        if (demo.isNotEmpty()) {
            val last = npcs.last()
            check(last.gameId == COMBAT_DEMO_NPC &&
                last.location.x == demo[0].x && last.location.y == demo[0].y) {
                "populate() appended the combat demo spawn last but npcs.last() is $last"
            }
            demoNpc = last
        }
        return count
    }

    private fun combatDemoSpawns(): List<NpcSpawnData.Spawn> {
        val tile = combatDemoSpawn ?: return emptyList()
        return listOf(
            NpcSpawnData.Spawn(
                npcId = COMBAT_DEMO_NPC,
                x = tile.x, y = tile.y, plane = tile.plane,
                squareId = (tile.x / 64) or ((tile.y / 64) shl 7),
                name = com.opennxt.resources.sqlite.SqliteNpcCodec.load(COMBAT_DEMO_NPC)?.name
            )
        )
    }

    fun populateFrom(spawns: List<NpcSpawnData.Spawn>): Int {
        val newNpcs = ArrayList<WorldNpc>(spawns.size)
        val newBySquare = HashMap<Int, MutableList<WorldNpc>>()
        val newById = HashMap<Int, MutableList<WorldNpc>>()
        NpcMorph.warm()
        for (spawn in spawns) {
            val npc = WorldNpc(spawn)
            npc.infoIndex = WORLD_INDEX_BASE + newNpcs.size
            newNpcs.add(npc)
            newBySquare.getOrPut(spawn.squareId) { ArrayList() }.add(npc)
            newById.getOrPut(spawn.npcId) { ArrayList() }.add(npc)
        }
        NpcWalkPose.warm(spawns.map { it.npcId }.toSet())
        NpcMovementDef.warm()
        tickCount = 0L
        demoNpc = null
        publishIndexes(newNpcs)
        npcs = newNpcs
        return newNpcs.size
    }

    private fun publishIndexes(list: List<WorldNpc>) {
        publishes++
        val newBySquare = HashMap<Int, MutableList<WorldNpc>>()
        val newById = HashMap<Int, MutableList<WorldNpc>>()
        val newByIndex = HashMap<Int, WorldNpc>(list.size * 2)
        var collisions = 0
        for (n in list) {
            newBySquare.getOrPut(n.spawn.squareId) { ArrayList() }.add(n)
            newById.getOrPut(n.spawn.npcId) { ArrayList() }.add(n)
            val prev = newByIndex.putIfAbsent(n.infoIndex, n)
            if (prev != null) {
                collisions++
                logger.error {
                    "infoIndex collision: slot ${n.infoIndex} is held by npc ${prev.gameId} " +
                        "(${prev.name ?: "unnamed"}) and npc ${n.gameId} (${n.name ?: "unnamed"}); keeping the first"
                }
            }
        }
        bySquare = newBySquare
        byId = newById
        byIndex = newByIndex
        infoIndexCollisions = collisions
    }

    @Volatile
    var infoIndexCollisions: Int = 0
        private set

    @Volatile
    var publishes: Long = 0L
        private set

    fun byInfoIndex(index: Int): WorldNpc? = byIndex[index]

    fun spawnAt(npcId: Int, tile: TileLocation, count: Int = 1): Int = spawnNpcsAt(npcId, tile, count).size

    fun spawnNpcsAt(npcId: Int, tile: TileLocation, count: Int = 1, slots: IntArray? = null): List<WorldNpc> {
        if (count <= 0) return emptyList()
        return spawnNpcsAt(List(count) { i -> SpawnRequest(npcId, tile, slots?.getOrNull(i)) }, quiet = false)
    }

    data class SpawnRequest(val npcId: Int, val tile: TileLocation, val slot: Int? = null)

    fun spawnNpcsAt(requests: List<SpawnRequest>, quiet: Boolean = true): List<WorldNpc> {
        if (requests.isEmpty()) return emptyList()
        val current = npcs
        val newList = ArrayList<WorldNpc>(current.size + requests.size)
        newList.addAll(current)
        var nextIndex = (current.maxOfOrNull { it.infoIndex } ?: (WORLD_INDEX_BASE - 1)) + 1
        val wantsSlots = requests.any { it.slot != null }
        val live = if (!wantsSlots) emptySet() else current.mapTo(HashSet()) { it.infoIndex }
        val added = ArrayList<WorldNpc>(requests.size)
        val taken = HashSet<Int>()
        for (req in requests) {
            val def = com.opennxt.resources.sqlite.SqliteNpcCodec.load(req.npcId)
            val npc = WorldNpc(
                NpcSpawnData.Spawn(
                    npcId = req.npcId,
                    x = req.tile.x, y = req.tile.y, plane = req.tile.plane,
                    squareId = (req.tile.x / 64) or ((req.tile.y / 64) shl 7),
                    name = def?.name
                )
            )
            val wanted = req.slot
            npc.infoIndex = if (wanted != null && wanted >= WORLD_INDEX_BASE &&
                wanted !in live && taken.add(wanted)
            ) {
                wanted
            } else {
                if (wanted != null) {
                    slotRefusals++
                    logger.error {
                        "spawnAt: slot $wanted unavailable for npc ${req.npcId}; using $nextIndex"
                    }
                }
                nextIndex++
            }
            newList.add(npc)
            added.add(npc)
        }
        publishIndexes(newList)
        npcs = newList
        if (!quiet) {
            val first = requests.first()
            logger.warn {
                "spawnAt: ${added.size} x npc ${first.npcId} (${added.firstOrNull()?.name ?: "unnamed"}) at " +
                    "(${first.tile.x}, ${first.tile.y}, ${first.tile.plane}); population is now ${newList.size}"
            }
        }
        return added
    }

    var slotRefusals: Int = 0
        private set

    var containedNpcFailures: Int = 0
        private set

    fun tick() {
        try {
            tickFault?.invoke()
            for (npc in npcs) {
                try {
                    tickOne(npc)
                } catch (t: Throwable) {
                    containedNpcFailures++
                    logger.error(t) { "npc ${npc.gameId} (${npc.name}) failed to tick" }
                }
            }
        } finally {
            tickCount++
        }
    }

    private fun tickOne(npc: WorldNpc) {
        val frozen = if (combatDemoWander) null else demoNpc
        run {
            if (npc.tickRespawn()) {
                if (npc.deathAnimation != null) {
                    npc.pendingUpdates.animate(com.opennxt.model.entity.rendering.npc.blocks.NpcAnimationBlock.stop())
                }
                npc.deathAnimation = null
            }
            if (npc.alive && npc !== frozen) NpcWander.queueStep(npc, tickCount)
            npc.movement.process()
            if (npc.pendingUpdates.offered || NpcDeathTransmission.shouldRetire(npc)) npc.pendingUpdates.clear()
        }
    }

    fun despawn(npc: WorldNpc): Boolean = despawnAll(listOf(npc)).size == 1

    fun despawnAll(victims: Collection<WorldNpc>): List<WorldNpc> {
        if (victims.isEmpty()) return emptyList()
        val current = npcs
        val wanted: MutableSet<WorldNpc> =
            java.util.Collections.newSetFromMap(java.util.IdentityHashMap(victims.size * 2))
        wanted.addAll(victims)
        val newList = ArrayList<WorldNpc>(current.size)
        val removed = ArrayList<WorldNpc>(victims.size)
        for (n in current) {
            if (n in wanted) removed.add(n) else newList.add(n)
        }
        if (removed.isEmpty()) return emptyList()
        for (npc in removed) {
            npc.markDespawned()
            npc.pendingUpdates.clear()
            npc.movement.reset()
            if (demoNpc === npc) demoNpc = null
            com.opennxt.model.combat.PlayerCombat.forgetNpc(npc)
            com.opennxt.model.combat.BossEncounters.forget(npc)
        }
        publishIndexes(newList)
        npcs = newList
        despawned += removed.size
        if (removed.size == 1) {
            val npc = removed[0]
            logger.info { "despawn: npc ${npc.gameId} (${npc.name ?: "unnamed"}) index ${npc.infoIndex} removed; population is now ${newList.size}" }
        } else {
            logger.info {
                "despawn: ${removed.size} npc(s) removed (ids " +
                    "${removed.map { it.gameId }.distinct().take(6)}${if (removed.map { it.gameId }.distinct().size > 6) ", ..." else ""}); " +
                    "population is now ${newList.size}"
            }
        }
        return removed
    }
    var despawned: Int = 0
        private set

    fun all(): List<WorldNpc> = npcs

    fun count(): Int = npcs.size

    fun aliveCount(): Int = npcs.count { it.alive }

    fun deadCount(): Int = npcs.count { !it.alive }

    fun npcsInSquare(squareId: Int): List<WorldNpc> = bySquare[squareId] ?: emptyList()

    fun npcAt(x: Int, y: Int, plane: Int = 0): WorldNpc? =
        npcs.firstOrNull {
            it.alive && it.location.x == x && it.location.y == y && it.location.plane == plane
        }

    fun byGameId(gameId: Int): List<WorldNpc> = byId[gameId] ?: emptyList()

    fun lifepointProvenanceBreakdown(): Map<String, Int> {
        val m = linkedMapOf<String, Int>()
        for (p in Provenance.values()) m[p.name] = 0
        m["null"] = 0
        for (npc in npcs) {
            val key = npc.lifepoints?.provenance?.name ?: "null"
            m[key] = m[key]!! + 1
        }
        return m
    }

    fun lifepointLayerBreakdown(): Map<String, Int> {
        val m = linkedMapOf<String, Int>()
        for (l in LifepointsLayer.values()) m[l.name] = 0
        m["null"] = 0
        for (npc in npcs) {
            val key = SeedData.layerOf(npc.gameId)?.name ?: "null"
            m[key] = m[key]!! + 1
        }
        return m
    }

    fun applyDamage(npc: WorldNpc, dmg: Int, random: Random): DeathResult? =
        applyDamage(npc, dmg, random, groundItems = null, owner = null)

    var lastDeathAnimation: com.opennxt.model.combat.NpcAnimTable.DeathAnimation? = null
        private set

    internal var tickFault: (() -> Unit)? = null

    fun interface NpcDamageListener {
        fun onDamage(npc: WorldNpc, before: Int, after: Int, attacker: String?)
    }
    private val damageListeners = java.util.concurrent.CopyOnWriteArrayList<NpcDamageListener>()
    fun addDamageListener(listener: NpcDamageListener) { damageListeners.add(listener) }
    fun removeDamageListener(listener: NpcDamageListener) { damageListeners.remove(listener) }
    fun damageListenerCount(): Int = damageListeners.size
    var containedListenerFailures: Int = 0
        private set

    fun applyDamage(
        npc: WorldNpc,
        dmg: Int,
        random: Random,
        groundItems: GroundItems?,
        owner: String? = null,
        style: com.opennxt.model.combat.CombatStyle? = null,
        necromancy: Boolean = false
    ): DeathResult? {
        val before = npc.currentLifepoints ?: 0
        val applied = npc.damage(dmg)
        val after = npc.currentLifepoints!!
        if (applied > 0) npc.queueLifepointsBlock()
        if (owner != null) npc.recordDamage(owner, applied, style, necromancy)
        if (damageListeners.isNotEmpty() && applied > 0) {
            for (l in damageListeners) {
                try { l.onDamage(npc, before, after, owner) } catch (t: Throwable) {
                    containedListenerFailures++
                    logger.error(t) { "npc damage listener failed for npc ${npc.gameId}" }
                }
            }
        }
        if (!npc.alive || npc.despawned) return null
        if (npc.currentLifepoints!! > 0) return null
        val lootOwner = npc.topDamageDealer() ?: owner

        val bossRespawn = if (com.opennxt.model.combat.BossEncounters.respawnEnabled)
            com.opennxt.model.combat.NpcBossData.respawnTicks(npc.gameId)?.value else null
        val respawnTicks = com.opennxt.model.combat.NpcCombatDefs.respawnDelay(npc.gameId)?.value
            ?: bossRespawn ?: WorldNpc.RESPAWN_TICKS
        npc.die(respawnTicks)

        npc.deathAnimation = null
        com.opennxt.model.combat.NpcAnimTable.deathAnimation(npc.gameId)?.let {
            npc.pendingUpdates.animate(it.sequence)
            npc.deathAnimation = it
            lastDeathAnimation = it
        }

        val deathTile = TileLocation(npc.location.x, npc.location.y, npc.location.plane)

        val provenance = npc.lifepoints!!
        val table = DropData.monsterForNpc(npc.gameId)
        if (table == null) {
            val death = DeathResult(
                npcGameId = npc.gameId, npcName = npc.name,
                dropped = emptyList(), unexpandedTableRolls = emptyList(),
                unrolledLines = emptyList(), tertiaryLinesNotRolled = 0,
                lifepointsProvenance = provenance,
                respawnTicks = npc.respawnTotal,
                dropTableNote = "no documented drop table covers game id ${npc.gameId}" +
                    (npc.name?.let { " ($it)" } ?: ""),
                lootOwner = lootOwner, damageLedger = LinkedHashMap(npc.damageBy), styleLedger = LinkedHashMap(npc.styleBy), necromancyLedger = LinkedHashSet(npc.necromancyBy)
            )
            return death.copy(groundSpawn = groundItems?.spawn(death, deathTile, lootOwner))
        }

        val dropped = ArrayList<DroppedItem>()
        val unexpanded = ArrayList<DropLine>()
        val unrolled = ArrayList<DropLine>()

        for (line in table.drops.filter { it.category == DropCategory.ALWAYS }) {
            if (line.isTableRef) unexpanded.add(line) else dropped.add(toDrop(line, random))
        }

        val main = table.drops.filter { it.category == DropCategory.MAIN }
        val rollable = main.filter { it.rarityNum != null && it.rarityDen != null && it.rarityDen != 0.0 }
        unrolled += main.filterNot { it in rollable }
        if (rollable.isNotEmpty()) {
            val u = random.nextDouble()
            var cum = 0.0
            for (line in rollable) {
                cum += line.rarityNum!! / line.rarityDen!!
                if (u < cum) {
                    if (line.isTableRef) {
                        unexpanded.add(line)
                    } else {
                        dropped.add(toDrop(line, random))
                    }
                    break
                }
            }
        }

        val death = DeathResult(
            npcGameId = npc.gameId, npcName = npc.name,
            dropped = dropped, unexpandedTableRolls = unexpanded,
            unrolledLines = unrolled,
            tertiaryLinesNotRolled = table.drops.count { it.category == DropCategory.TERTIARY },
            lifepointsProvenance = provenance,
            respawnTicks = npc.respawnTotal,
            dropTableNote = table.note,
            lootOwner = lootOwner, damageLedger = LinkedHashMap(npc.damageBy), styleLedger = LinkedHashMap(npc.styleBy), necromancyLedger = LinkedHashSet(npc.necromancyBy)
        )
        return death.copy(groundSpawn = groundItems?.spawn(death, deathTile, lootOwner))
    }

    private fun toDrop(line: DropLine, random: Random): DroppedItem {
        val qty = line.quantityRange?.takeIf { it.last >= it.first }?.let { r -> r.first + random.nextInt(r.last - r.first + 1) }
        return DroppedItem(
            itemName = line.itemName,
            itemIds = line.itemIds,
            quantity = qty,
            category = line.category,
            rarity = line.rarity,
            source = line.source
        )
    }
}

data class DroppedItem(
    val itemName: String,
    val itemIds: List<Int>,
    val itemId: Int? = com.opennxt.model.drops.DropData.chosenItemId(itemName, itemIds),
    val quantity: Int?,
    val category: DropCategory,
    val rarity: String,
    val source: String
) {
    override fun toString() = "$itemName x${quantity?.toString() ?: "?"} ($rarity) ids=$itemIds"
}

data class DeathResult(
    val npcGameId: Int,
    val npcName: String?,
    val dropped: List<DroppedItem>,
    val unexpandedTableRolls: List<DropLine>,
    val unrolledLines: List<DropLine>,
    val tertiaryLinesNotRolled: Int,
    val lifepointsProvenance: SeededValue,
    val respawnTicks: Int,
    val dropTableNote: String?,
    val groundSpawn: SpawnResult? = null,
    val lootOwner: String? = null,
    val damageLedger: Map<String, Int> = emptyMap(),
    val styleLedger: Map<String, com.opennxt.model.combat.CombatStyle> = emptyMap(),
    val necromancyLedger: Set<String> = emptySet()
) {
    override fun toString() =
        "DeathResult(${npcName ?: "npc$npcGameId"}($npcGameId): dropped=$dropped, " +
            "unexpanded=${unexpandedTableRolls.map { it.itemName + " @ " + it.rarity }}, " +
            "lp=${lifepointsProvenance}, respawn=${respawnTicks}t" +
            (groundSpawn?.let { ", ground=$it" } ?: "") + ")"
}

class NpcRetaliation {
    companion object {
        const val PLAYER_AFFINITY = 55
    }

    private var clock = 0

    private val nextAttackAt = HashMap<WorldNpc, Int>()
    fun forget(npc: WorldNpc) { nextAttackAt.remove(npc) }
    fun tracks(npc: WorldNpc): Boolean = nextAttackAt.containsKey(npc)

    fun tick() {
        clock++
    }

    fun retaliate(npc: WorldNpc, player: CombatDefender, random: Random): RetaliationOutcome {
        if (!npc.alive) return RetaliationOutcome.Refused(
            npc, "npc is dead (respawns in ${npc.respawnTicksRemaining} ticks)"
        )
        if (npc.lifepoints == null) return RetaliationOutcome.Refused(
            npc, "npc ${npc.gameId} has no lifepoints"
        )
        val combat = npc.combat ?: return RetaliationOutcome.Refused(
            npc, "no combat definition for game id ${npc.gameId}"
        )
        val style = combat.combatStyle ?: return RetaliationOutcome.Refused(
            npc, "npc ${npc.gameId} has no combat style param"
        )
        val accuracy = combat.accuracyFor(style) ?: return RetaliationOutcome.Refused(
            npc, "npc ${npc.gameId} has no $style accuracy param"
        )
        val maxHitX10 = combat.damageFor(style) ?: return RetaliationOutcome.Refused(
            npc, "npc ${npc.gameId} has no $style damage param"
        )
        val rawSpeed = combat.attackSpeed ?: return RetaliationOutcome.Refused(
            npc, "npc ${npc.gameId} has no attack-speed param (14)"
        )
        val speed = when (val delay = CombatFormulas.tickDelay(rawSpeed)) {
            is AttackDelay.Ticks -> delay.ticks
            is AttackDelay.OutOfRange -> return RetaliationOutcome.Refused(
                npc, "npc ${npc.gameId} attack speed ${delay.rawValue} exceeds ${CombatFormulas.MAX_TICKS}"
            )
        }

        val due = nextAttackAt[npc] ?: clock
        if (clock < due) return RetaliationOutcome.NotDue(npc, ticksUntilDue = due - clock)
        com.opennxt.content.ability.StatusEffects.npcStunRemaining(npc)?.let { return RetaliationOutcome.NotDue(npc, ticksUntilDue = it) }
        nextAttackAt[npc] = clock + speed

        val loadout = player.combatLoadout()
        val playerDefence = if (loadout == null) PlayerCombatStats.defence(player.level(Stat.DEFENCE))
            else PlayerCombatStats.defenceRating(player.level(Stat.DEFENCE), loadout)
        val playerAffinity = if (loadout == null) PLAYER_AFFINITY else PlayerCombatStats.defenderAffinity(style, loadout)
        val chance = CombatFormulas.hitChance(accuracy, playerDefence, playerAffinity)
        if (random.nextDouble() >= chance) return RetaliationOutcome.Missed(npc, chance)

        val rolled = CombatFormulas.damageRoll(maxHitX10 / 10, random)
        val damage = com.opennxt.content.ability.StatusEffects.scaleNpcHit(npc, player, rolled)
        val death = player.takeDamage(damage)
        return if (death != null) RetaliationOutcome.KilledPlayer(npc, damage, death)
        else RetaliationOutcome.Hit(npc, damage, player.currentLifepoints)
    }
}

sealed class RetaliationOutcome {
    data class Refused(val npc: WorldNpc, val reason: String) : RetaliationOutcome()

    data class NotDue(val npc: WorldNpc, val ticksUntilDue: Int) : RetaliationOutcome()

    data class Missed(val npc: WorldNpc, val hitChance: Double) : RetaliationOutcome()

    data class Hit(val npc: WorldNpc, val damage: Int, val playerLifepointsLeft: Int) : RetaliationOutcome()

    data class KilledPlayer(val npc: WorldNpc, val damage: Int, val death: PlayerDeath) : RetaliationOutcome()
}

object NpcMovementDef {
    private val logger = mu.KotlinLogging.logger { }

    val wanderGateEnabled: Boolean = System.getProperty("opennxt.experiment.npcs.wander.capability") != "false"

    val crawlEnabled: Boolean = System.getProperty("opennxt.npc.gait.crawl") != "false"

    const val WANDER_BIT = 0x2

    const val MOVEMENT_TYPE_CRAWL = 0

    private const val ABSENT = Int.MIN_VALUE

    private class Table(
        val capabilities: it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap,
        val movementType: it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap
    )

    private val table: Table by lazy {
        val cap = it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap().apply { defaultReturnValue(ABSENT) }
        val type = it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap().apply { defaultReturnValue(ABSENT) }
        if (com.opennxt.resources.sqlite.RsDatabase.available) {
            runCatching {
                com.opennxt.resources.sqlite.RsDatabase.queryAll("SELECT id, movementCapabilities, movementType FROM npcs") { rs ->
                    val c = rs.getInt(2)
                    val cAbsent = rs.wasNull()
                    val t = rs.getInt(3)
                    val tAbsent = rs.wasNull()
                    val id = rs.getInt(1)
                    if (!cAbsent) cap.put(id, c)
                    if (!tAbsent) type.put(id, t)
                }
            }.onFailure {
                logger.warn { "could not read npc movement data (${it.message}); using defaults" }
                cap.clear()
                type.clear()
            }
        }
        Table(cap, type)
    }

    fun capabilitiesOf(gameId: Int): Int? =
        table.capabilities.get(gameId).takeIf { it != ABSENT }
            ?: morphTargetOf(gameId)?.let { table.capabilities.get(it).takeIf { v -> v != ABSENT } }

    fun movementTypeOf(gameId: Int): Int? =
        table.movementType.get(gameId).takeIf { it != ABSENT }
            ?: morphTargetOf(gameId)?.let { table.movementType.get(it).takeIf { v -> v != ABSENT } }

    private fun morphTargetOf(gameId: Int): Int? {
        if (!NpcMorph.movementFillEnabled) return null
        return NpcMorph.effectiveId(gameId).takeIf { it != gameId }
    }

    fun capabilitiesAllowWander(capabilities: Int?): Boolean =
        capabilities == null || capabilities == 0 || (capabilities and WANDER_BIT) != 0

    fun mayWander(gameId: Int): Boolean = !wanderGateEnabled || capabilitiesAllowWander(capabilitiesOf(gameId))

    fun gaitForType(movementType: Int?): com.opennxt.model.entity.updating.NpcInfoEncoder.Gait =
        if (movementType == MOVEMENT_TYPE_CRAWL) com.opennxt.model.entity.updating.NpcInfoEncoder.Gait.CRAWL
        else com.opennxt.model.entity.updating.NpcInfoEncoder.Gait.WALK

    fun singleStepGait(gameId: Int): com.opennxt.model.entity.updating.NpcInfoEncoder.Gait =
        if (!crawlEnabled) com.opennxt.model.entity.updating.NpcInfoEncoder.Gait.WALK
        else gaitForType(movementTypeOf(gameId))

    fun warm(): Int {
        NpcMorph.warm()
        return table.capabilities.size
    }
}

object NpcWander {
    val enabled: Boolean = System.getProperty("opennxt.experiment.npcs.wander") != "false"

    const val LEASH_RADIUS = 3

    const val INTERVAL_TICKS = 8

    const val SEED = 0x4E5043L

    private fun mix(value: Long): Long {
        var z = value + -0x61c8864680b583ebL
        z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
        z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
        return z xor (z ushr 31)
    }

    fun phaseOf(index: Int): Int = (mix(SEED + index) ushr 8).toInt() and (INTERVAL_TICKS - 1)

    fun isDue(index: Int, tick: Long): Boolean =
        ((tick + phaseOf(index)) % INTERVAL_TICKS) == 0L

    fun directionOf(index: Int, tick: Long): Int =
        (mix(mix(SEED + index) + tick) ushr 17).toInt() and 7

    fun queueStep(npc: WorldNpc, tick: Long): Boolean {
        if (!enabled) return false
        if (npc.infoIndex < 0) return false
        if (!NpcWalkPose.mayWander(npc.gameId)) return false
        if (!NpcMovementDef.mayWander(npc.gameId)) return false
        if (npc.movement.hasSteps) return false
        if (com.opennxt.content.ability.StatusEffects.blocksStep(npc)) return false
        if (com.opennxt.model.combat.PlayerCombat.isInCombat(npc)) return false
        if (!isDue(npc.infoIndex, tick)) return false

        val dir = CompassPoint.getById(directionOf(npc.infoIndex, tick)) ?: return false
        val from = npc.location
        val tx = from.x + dir.dx
        val ty = from.y + dir.dy

        val spawn = npc.spawn
        if (Math.abs(tx - spawn.x) > LEASH_RADIUS) return false
        if (Math.abs(ty - spawn.y) > LEASH_RADIUS) return false
        if (from.plane != spawn.plane) return false

        return npc.movement.addStep(tx, ty)
    }

    fun project(index: Int, spawn: TileLocation, ticks: Int): List<TileLocation> {
        val out = ArrayList<TileLocation>(ticks)
        var cur = spawn
        for (t in 0 until ticks) {
            if (isDue(index, t.toLong())) {
                val dir = CompassPoint.getById(directionOf(index, t.toLong()))
                if (dir != null) {
                    val tx = cur.x + dir.dx
                    val ty = cur.y + dir.dy
                    if (Math.abs(tx - spawn.x) <= LEASH_RADIUS &&
                        Math.abs(ty - spawn.y) <= LEASH_RADIUS &&
                        CollisionMap.canStep(cur.x, cur.y, dir.dx, dir.dy, cur.plane)
                    ) {
                        cur = TileLocation(tx, ty, cur.plane)
                    }
                }
            }
            out.add(cur)
        }
        return out
    }
}

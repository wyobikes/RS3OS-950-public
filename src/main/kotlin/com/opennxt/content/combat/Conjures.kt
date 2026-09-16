package com.opennxt.content.combat

import com.opennxt.OpenNXT
import com.opennxt.content.ability.AbilityActivation
import com.opennxt.content.ability.AbilityDefinitions
import com.opennxt.content.ability.AbilityDefinitions.Style
import com.opennxt.content.ability.CombatVitals
import com.opennxt.content.ability.StatusEffects
import com.opennxt.model.combat.CombatXp
import com.opennxt.model.combat.PlayerCombat
import com.opennxt.model.combat.TargetHud
import com.opennxt.model.entity.rendering.npc.blocks.NpcHitsBlock
import com.opennxt.model.map.CollisionMap
import com.opennxt.model.tick.Tickable
import com.opennxt.model.world.DeathResult
import com.opennxt.model.world.GroundItems
import com.opennxt.model.world.TileLocation
import com.opennxt.model.world.WorldNpc
import com.opennxt.model.world.WorldNpcs
import com.opennxt.model.world.WorldPlayer
import com.opennxt.net.game.serverprot.RunClientScript
import mu.KotlinLogging
import java.util.Collections
import java.util.EnumMap
import java.util.IdentityHashMap
import java.util.Random

object Conjures {
    private val logger = KotlinLogging.logger { }

    const val FLAG = "opennxt.combat.conjures"
    val enabled: Boolean get() = System.getProperty(FLAG) != "off"

    val phantomDamageTaken: Double = if (System.getProperty(FLAG) == "off") 1.0 else 0.95

    const val DURATION_TICKS = 70
    const val LIFE_TRANSFER_TICKS = 35
    const val LIFE_TRANSFER_STRUCT = 48328
    const val FOLLOW_DISTANCE = 2
    const val TELEPORT_DISTANCE = 16
    const val RAGE_PERCENT_PER_STACK = 3
    const val GHOST_HEAL_PERCENT = 140
    const val ZOMBIE_POISON_FIRST_TICK = 9
    const val ZOMBIE_POISON_EVERY_TICKS = 3
    const val ZOMBIE_POISON_MIN_PCT = 8.0
    const val ZOMBIE_POISON_MAX_PCT = 12.0
    const val ZOMBIE_POISON_RANGE = 1
    const val SPLAT_AMOUNT_MAX = 0x7fff
    const val MAX_SPLATS_PER_BLOCK = 255
    const val SCRIPT_BUFF_ICON = 10623
    const val CONJURE_SEED = 0x434F4E4AL

    enum class Kind(
        val structId: Int, val npcId: Int, val label: String, val varp: Int, val buffName: String, val buffStruct: Int,
        val cadence: Int, val firstHit: Int, val minPct: Double, val maxPct: Double, val reach: Int
    ) {
        SKELETON(48302, 30265, "Skeleton Warrior", 10994, "skeleton_warrior", 48335, 5, 7, 22.0, 28.0, 2),
        ZOMBIE(48304, 30266, "Putrid Zombie", 11006, "putrid_zombie", 48336, 6, 7, 18.0, 22.0, 1),
        GHOST(48306, 30267, "Vengeful Ghost", 11018, "vengeful_ghost", 48337, 7, 6, 18.0, 22.0, 1),
        PHANTOM(31820, 31142, "Phantom Guardian", 11820, "phantom_guardian", 32349, 0, 0, 0.0, 0.0, 0);

        val attacks: Boolean get() = cadence > 0
        val ownBuff: Boolean get() = this != PHANTOM

        companion object {
            fun forStruct(structId: Int): Kind? = values().firstOrNull { it.structId == structId }
            fun forNpcId(npcId: Int): Kind? = values().firstOrNull { it.npcId == npcId }
        }
    }

    enum class Dismissal { EXPIRED, RECONJURED, OWNER_LEFT, OWNER_DIED, DIED, DISABLED, CHECK }

    class Conjure(val owner: WorldPlayer, val kind: Kind, val npc: WorldNpc, val spawnTick: Long, val damage: Double, val sentinel: Boolean) {
        var expiresAt: Long = spawnTick + DURATION_TICKS
        var nextHitAt: Long = spawnTick + kind.firstHit
        var nextPoisonAt: Long = spawnTick + ZOMBIE_POISON_FIRST_TICK
        var hits = 0
        var poisonTicks = 0
        var rage = 0
        var healed = 0
        var steps = 0
        var teleports = 0
        var kills = 0
        var extensions = 0
        val remaining: Long get() = expiresAt - AbilityActivation.clock
        override fun toString() = "${kind.label} of ${owner.name} [npc ${npc.gameId} slot ${npc.infoIndex}] spawned $spawnTick expires $expiresAt hits $hits"
    }

    private val byOwner = IdentityHashMap<WorldPlayer, EnumMap<Kind, Conjure>>()
    private val byNpc = IdentityHashMap<WorldNpc, Conjure>()
    private var listenedTo: WorldNpcs? = null
    private var installed = false
    private var tickableSubmitted = false

    var random: Random = Random(PlayerCombat.bootSeed xor CONJURE_SEED)

    var spawned = 0; private set
    var spawnsRefused = 0; private set
    var scheduleHitsDropped = 0; private set
    var hitsApplied = 0; private set
    var poisonApplied = 0; private set
    var lifepointsHealed = 0; private set
    var kills = 0; private set
    var stepsQueued = 0; private set
    var teleports = 0; private set
    var extensions = 0; private set
    var containedFailures = 0; private set
    private val dismissals = EnumMap<Dismissal, Int>(Dismissal::class.java)
    fun dismissed(reason: Dismissal): Int = dismissals[reason] ?: 0
    var tickDriver: String = "not installed"; private set

    fun conjuresOf(owner: WorldPlayer): List<Conjure> = byOwner[owner]?.values?.toList().orEmpty()
    fun conjureFor(owner: WorldPlayer, kind: Kind): Conjure? = byOwner[owner]?.get(kind)
    fun conjureOf(npc: WorldNpc): Conjure? = byNpc[npc]
    fun standing(): Int = byNpc.size

    object Phase : Tickable {
        override fun tick() {
            val world = runCatching { OpenNXT.world }.getOrNull() ?: return
            val players = ArrayList<WorldPlayer>()
            world.forEachPlayer { players += it }
            Conjures.tick(world.npcs, world.groundItems, players)
        }
    }

    fun install() {
        if (installed) return
        installed = true
        val prevCast = AbilityActivation.onAbilityCast
        AbilityActivation.onAbilityCast = { p, d -> prevCast?.invoke(p, d); onCast(p, d) }
        if (!tickableSubmitted) {
            val engine = runCatching { OpenNXT.tickEngine }.getOrNull()
            if (engine != null) {
                engine.submitTickable(Phase)
                tickableSubmitted = true
                tickDriver = "tickEngine"
            } else tickDriver = "no tick engine"
        }
        logger.info { "conjures: installed (${if (enabled) "on" else "off, -D$FLAG=off"}); tick driver: $tickDriver" }
    }

    fun attach(npcs: WorldNpcs) {
        if (listenedTo === npcs) return
        listenedTo?.removeDamageListener(damageListener)
        npcs.addDamageListener(damageListener)
        listenedTo = npcs
    }

    private val damageListener = WorldNpcs.NpcDamageListener { npc, before, after, _ ->
        if (before > 0 && after <= 0) byNpc[npc]?.let { dismiss(it, Dismissal.DIED, "its lifepoints reached 0") }
    }

    fun onCast(player: WorldPlayer, def: AbilityDefinitions.Definition) {
        if (!enabled) return
        try {
            val kind = Kind.forStruct(def.structId)
            if (kind != null) {
                dropSchedule(player, def)
                val npcs = runCatching { OpenNXT.world.npcs }.getOrNull() ?: listenedTo
                if (npcs == null) { spawnsRefused++; logger.warn { "conjures: ${player.name} cast ${def.name} with no world to spawn into" } }
                else spawn(player, kind, npcs)
            } else if (def.structId == LIFE_TRANSFER_STRUCT) {
                extendAll(player, LIFE_TRANSFER_TICKS, def.name)
            }
            NecromancyHud.syncSouls(player)
            NecromancyHud.syncNecrosis(player)
        } catch (t: Throwable) {
            containedFailures++
            logger.error(t) { "conjures: onCast failed for ${player.name}'s ${def.name}" }
        }
    }

    private fun dropSchedule(player: WorldPlayer, def: AbilityDefinitions.Definition): Int {
        val st = AbilityActivation.stateOrNull(player) ?: return 0
        val id = st.pending.filter { it.structId == def.structId }.maxOfOrNull { it.activationId } ?: return 0
        val n = st.pending.count { it.activationId == id }
        st.pending.removeAll { it.activationId == id }
        scheduleHitsDropped += n
        logger.info { "conjures: ${player.name}'s ${def.name} [#$id]: replaced $n queued hit(s) with the conjure" }
        return n
    }

    fun spawn(owner: WorldPlayer, kind: Kind, npcs: WorldNpcs): Conjure? {
        attach(npcs)
        val now = AbilityActivation.clock
        val damage = if (kind.attacks) AbilityActivation.abilityDamage(owner, Style.NECROMANCY) else 0.0
        if (damage == null) {
            spawnsRefused++
            logger.warn { "conjures: ${owner.name}'s ${kind.label} rejected, no necromancy ability damage (weapon missing?)" }
            return null
        }
        byOwner[owner]?.get(kind)?.let { dismiss(it, Dismissal.RECONJURED, "re-conjured") }
        val ownerLoc = owner.entity.location
        val tile = adjacentFreeTile(ownerLoc)
        val before = npcs.count()
        val npc = npcs.spawnNpcsAt(kind.npcId, tile, 1).singleOrNull()
        if (npc == null || npcs.count() != before + 1 || npc.gameId != kind.npcId || npc.location.x != tile.x || npc.location.y != tile.y) {
            spawnsRefused++
            logger.error { "conjures: failed to spawn ${kind.label} (population $before -> ${npcs.count()}, got ${npc})" }
            return null
        }
        var sentinel = false
        if (StatusEffects.enabled) {
            if (kind.ownBuff) sentinel = StatusEffects.buff(owner, kind.buffName, DURATION_TICKS, "Conjure ${kind.label}", null, kind.buffStruct) != null
            else sentinel = StatusEffects.hasBuff(owner, kind.buffName)
        }
        val c = Conjure(owner, kind, npc, now, damage, sentinel)
        byOwner.getOrPut(owner) { EnumMap(Kind::class.java) }[kind] = c
        byNpc[npc] = c
        spawned++
        NecromancyHud.conjureFlag(owner, kind, true)
        runCatching { npc.pendingUpdates.facePlayer(owner.entity.index) }
        logger.info {
            "conjures: ${owner.name} conjured ${kind.label} (npc ${kind.npcId}, slot ${npc.infoIndex}) at (${tile.x},${tile.y},${tile.plane}) tick $now: " +
                "expires ${c.expiresAt}, hits ${if (kind.attacks) "from ${c.nextHitAt} every ${kind.cadence}" else "none"}, " +
                "damage ${"%.1f".format(damage)}, band ${kind.minPct}-${kind.maxPct}%, reach ${kind.reach}, " +
                "buff ${kind.buffName}${if (sentinel) " (struct ${kind.buffStruct})" else " not applied"}"
        }
        return c
    }

    fun dismiss(c: Conjure, reason: Dismissal, why: String) {
        val map = byOwner[c.owner]
        if (map?.get(c.kind) !== c) return
        map.remove(c.kind)
        if (map.isEmpty()) byOwner.remove(c.owner)
        byNpc.remove(c.npc)
        dismissals[reason] = (dismissals[reason] ?: 0) + 1
        val npcs = listenedTo
        val removed = if (npcs != null && !c.npc.despawned) npcs.despawn(c.npc) else false
        NecromancyHud.conjureFlag(c.owner, c.kind, false)
        val early = reason != Dismissal.EXPIRED && reason != Dismissal.OWNER_LEFT && reason != Dismissal.OWNER_DIED
        if (early && c.sentinel && c.kind.ownBuff && StatusEffects.enabled && StatusEffects.hasBuff(c.owner, c.kind.buffName)) {
            AbilityActivation.sendPacket(c.owner, RunClientScript(SCRIPT_BUFF_ICON, arrayOf<Any>(c.kind.buffStruct, 0)))
        }
        logger.info { "conjures: ${c.owner.name}'s ${c.kind.label} dismissed ($reason: $why) at tick ${AbilityActivation.clock} after ${c.hits} hit(s), ${c.kills} kill(s); despawned=$removed" }
    }

    fun extendAll(owner: WorldPlayer, ticks: Int, source: String = "Life Transfer"): Int {
        if (ticks <= 0) return 0
        var n = 0
        for (c in conjuresOf(owner)) {
            c.expiresAt += ticks
            c.extensions++
            extensions++
            n++
            if (c.sentinel && StatusEffects.enabled) StatusEffects.buff(owner, c.kind.buffName, c.remaining.toInt(), source, null, if (c.kind.ownBuff) c.kind.buffStruct else c.kind.structId)
            logger.info { "conjures: ${owner.name}'s ${c.kind.label} extended by $ticks to ${c.expiresAt} ($source)" }
        }
        if (n == 0) logger.info { "conjures: ${owner.name}'s $source found no conjure to extend" }
        return n
    }

    fun tick(npcs: WorldNpcs, groundItems: GroundItems?, players: List<WorldPlayer>) {
        if (!enabled) {
            if (byNpc.isNotEmpty()) for (c in byNpc.values.toList()) dismiss(c, Dismissal.DISABLED, "-D$FLAG=off")
            return
        }
        attach(npcs)
        val now = AbilityActivation.clock
        if (byOwner.isNotEmpty()) {
            val live = Collections.newSetFromMap(IdentityHashMap<WorldPlayer, Boolean>()).also { it.addAll(players) }
            for ((owner, map) in byOwner.entries.map { it.key to it.value }) {
                for (c in map.values.toList()) {
                    try {
                        tickOne(c, owner in live, now, npcs, groundItems, players)
                    } catch (t: Throwable) {
                        containedFailures++
                        logger.error(t) { "conjures: tick failed for ${c}" }
                    }
                }
            }
        }
        try { NecromancyHud.tick(players) } catch (t: Throwable) { containedFailures++; logger.error(t) { "conjures: HUD sync failed" } }
    }

    private fun tickOne(c: Conjure, ownerLive: Boolean, now: Long, npcs: WorldNpcs, groundItems: GroundItems?, players: List<WorldPlayer>) {
        val owner = c.owner
        val npc = c.npc
        if (!ownerLive) return dismiss(c, Dismissal.OWNER_LEFT, "the owner is no longer in the world")
        if (npc.despawned || !npc.alive) return dismiss(c, Dismissal.DIED, "the npc is dead or gone")
        if (now >= c.expiresAt) return dismiss(c, Dismissal.EXPIRED, "duration reached (${now - c.spawnTick} ticks)")
        if (c.sentinel && StatusEffects.enabled && !StatusEffects.hasBuff(owner, c.kind.buffName)) {
            return dismiss(c, Dismissal.OWNER_DIED, "owner died")
        }
        val ownerLoc = owner.entity.location
        val target = if (c.kind.attacks && PlayerCombat.enabled) PlayerCombat.targetOf(owner)?.takeIf { it.alive && !it.despawned && it.location.plane == ownerLoc.plane } else null

        val fromOwner = maxOf(Math.abs(npc.location.x - ownerLoc.x), Math.abs(npc.location.y - ownerLoc.y))
        if (fromOwner >= TELEPORT_DISTANCE || npc.location.plane != ownerLoc.plane) {
            val to = adjacentFreeTile(ownerLoc)
            npc.movement.teleport(to)
            c.teleports++; teleports++
            logger.info { "conjures: ${owner.name}'s ${c.kind.label} teleports to (${to.x},${to.y}) - $fromOwner tiles from the owner" }
        } else if (!npc.movement.hasSteps) {
            if (target != null) {
                if (PlayerCombat.footprintDistance(target, npc.location.x, npc.location.y) > c.kind.reach) {
                    val ring = PlayerCombat.nearestAttackTile(target, npc.location.x, npc.location.y, c.kind.reach)
                    if (ring != null && stepToward(npc, ring[0], ring[1])) { c.steps++; stepsQueued++ }
                }
            } else if (fromOwner > FOLLOW_DISTANCE) {
                if (stepToward(npc, ownerLoc.x, ownerLoc.y)) { c.steps++; stepsQueued++ }
            }
        }

        if (target != null && now >= c.nextHitAt && PlayerCombat.footprintDistance(target, npc.location.x, npc.location.y) <= c.kind.reach) {
            val stacks = if (c.kind == Kind.SKELETON) c.rage else 0
            val scale = 1.0 + stacks * RAGE_PERCENT_PER_STACK / 100.0
            hit(c, target, c.kind.minPct, c.kind.maxPct, scale, npcs, groundItems, players, "hit ${c.hits + 1}" + (if (stacks > 0) " (rage $stacks)" else ""))
            c.hits++
            if (c.kind == Kind.SKELETON) c.rage++
            c.nextHitAt = now + c.kind.cadence
        }
        if (c.kind == Kind.ZOMBIE && now >= c.nextPoisonAt) {
            c.nextPoisonAt = now + ZOMBIE_POISON_EVERY_TICKS
            if (target != null && PlayerCombat.footprintDistance(target, npc.location.x, npc.location.y) <= ZOMBIE_POISON_RANGE) {
                hit(c, target, ZOMBIE_POISON_MIN_PCT, ZOMBIE_POISON_MAX_PCT, 1.0, npcs, groundItems, players, "poison tick ${c.poisonTicks + 1}")
                c.poisonTicks++; poisonApplied++
            }
        }
    }

    private fun hit(c: Conjure, target: WorldNpc, minPct: Double, maxPct: Double, scale: Double, npcs: WorldNpcs, groundItems: GroundItems?, players: List<WorldPlayer>, what: String) {
        if (!target.alive) return
        val before = target.currentLifepoints ?: return
        val percent = if (maxPct <= minPct) minPct else minPct + random.nextDouble() * (maxPct - minPct)
        val requested = Math.floor(c.damage * percent * scale / 100.0).toInt().coerceIn(0, SPLAT_AMOUNT_MAX)
        val death: DeathResult? = npcs.applyDamage(target, requested, random, groundItems, c.owner.name, null, necromancy = true)
        val applied = before - (target.currentLifepoints ?: 0)
        hitsApplied++
        PlayerCombat.npcAttackAnimation(c.npc)?.let { anim -> runCatching { c.npc.pendingUpdates.animate(anim) } }
        if (applied > 0) {
            splat(target, applied)
            runCatching { TargetHud.update(c.owner, target) }
            if (c.kind == Kind.GHOST) {
                val heal = Math.floor(applied * GHOST_HEAL_PERCENT / 100.0).toInt()
                val healed = CombatVitals.heal(c.owner, heal, "Vengeful Ghost (140% of $applied)")
                c.healed += healed; lifepointsHealed += healed
            }
        }
        logger.info {
            "conjures: ${c.owner.name}'s ${c.kind.label} $what on ${target.name ?: "npc"} ${target.gameId} at tick ${AbilityActivation.clock}: " +
                "${"%.1f".format(percent)}% x ${"%.2f".format(scale)} of ${"%.1f".format(c.damage)} = $requested requested, $applied applied" +
                (if (death != null) ", killed" else "")
        }
        if (death != null) {
            kills++; c.kills++
            awardKill(death, target, players)
            if (PlayerCombat.targetOf(c.owner) === target) PlayerCombat.disengage(c.owner)
        }
    }

    private fun awardKill(death: DeathResult, npc: WorldNpc, players: List<WorldPlayer>) {
        for (name in death.damageLedger.keys) {
            val p = players.firstOrNull { it.name == name } ?: continue
            try {
                for ((stat, xp) in CombatXp.killAward(death, name, npc.gameId)) p.stats.addExperience(stat, xp)
            } catch (t: Throwable) {
                logger.error(t) { "conjures: kill xp award to $name failed" }
            }
        }
    }

    private fun splat(npc: WorldNpc, amount: Int) {
        val type = PlayerCombat.splatType ?: return
        val updates = npc.pendingUpdates
        val existing = if (updates.offered) null else updates.hits
        val hits = existing?.hits.orEmpty()
        if (hits.size >= MAX_SPLATS_PER_BLOCK) return
        runCatching { updates.hit(NpcHitsBlock(hits + NpcHitsBlock.Hit(type, amount.coerceAtMost(SPLAT_AMOUNT_MAX), 0), existing?.bars.orEmpty())) }
            .onFailure { logger.warn(it) { "conjures: splat of $amount on npc ${npc.gameId} not queued" } }
    }

    private val DIRECTIONS = arrayOf(intArrayOf(-1, 0), intArrayOf(1, 0), intArrayOf(0, -1), intArrayOf(0, 1), intArrayOf(-1, -1), intArrayOf(1, -1), intArrayOf(-1, 1), intArrayOf(1, 1))

    fun adjacentFreeTile(at: TileLocation): TileLocation {
        for (d in DIRECTIONS) {
            if (CollisionMap.canStep(at.x, at.y, d[0], d[1], at.plane)) return TileLocation(at.x + d[0], at.y + d[1], at.plane)
        }
        return TileLocation(at.x, at.y, at.plane)
    }

    private fun stepToward(npc: WorldNpc, x: Int, y: Int): Boolean {
        val from = npc.location
        val dx = Integer.signum(x - from.x)
        val dy = Integer.signum(y - from.y)
        if (dx == 0 && dy == 0) return false
        val m = npc.movement
        if (m.addStep(from.x + dx, from.y + dy)) return true
        if (dx != 0 && dy != 0) {
            if (m.addStep(from.x + dx, from.y)) return true
            if (m.addStep(from.x, from.y + dy)) return true
        }
        return false
    }
}

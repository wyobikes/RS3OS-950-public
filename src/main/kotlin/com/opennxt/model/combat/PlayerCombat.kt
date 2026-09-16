package com.opennxt.model.combat

import com.opennxt.model.entity.rendering.PlayerUpdates
import com.opennxt.model.entity.rendering.npc.blocks.NpcHitsBlock
import com.opennxt.model.world.DeathResult
import com.opennxt.model.world.GroundItems
import com.opennxt.model.world.WorldNpc
import com.opennxt.model.world.WorldNpcs
import com.opennxt.model.world.WorldPlayer
import mu.KotlinLogging
import java.util.IdentityHashMap
import java.util.Random

object PlayerCombat {
    private val logger = KotlinLogging.logger { }

    val enabled: Boolean
        get() = System.getProperty("opennxt.experiment.combat") == "true"

    val faceEnabled: Boolean
        get() = System.getProperty("opennxt.experiment.combat.face") != "false"

    val splatType: Int?
        get() {
            val raw = System.getProperty("opennxt.experiment.combat.splattype")?.trim()
            if (raw.isNullOrEmpty()) return DEFAULT_HITMARK
            if (raw.equals("none", ignoreCase = true)) return null
            return raw.toIntOrNull() ?: DEFAULT_HITMARK
        }

    const val DEFAULT_HITMARK = 0

    const val HITMARK_PROVENANCE =
        "hitmark $DEFAULT_HITMARK"

    const val DEMO_HIT = 48

    const val BOUNDING_WEAPON_ITEM = 1277
    const val BOUNDING_WEAPON_PARAM_641 = 480

    const val ATTACK_INTERVAL_TICKS = 4

    const val ATTACK_RANGE = 1

    val realDamageEnabled: Boolean
        get() = System.getProperty("opennxt.experiment.combat.realdamage") == "true"

    const val REAL_DAMAGE_SEED = 0x52454144L

    val seedProperty: Long? = System.getProperty("opennxt.combat.seed")?.trim()?.let { raw ->
        raw.toLongOrNull().also { if (it == null) logger.warn { "-Dopennxt.combat.seed=$raw is not a number; using a random seed" } }
    }
    val bootSeed: Long = seedProperty ?: java.security.SecureRandom().nextLong()
    val seedPinned: Boolean get() = seedProperty != null
    private var seedLogged = false

    val splatDelayMode: String
        get() = when (System.getProperty("opennxt.experiment.combat.splatDelay")) {
            "anim" -> "ANIM"
            "zero" -> "ZERO"
            else -> "BUILD"
        }

    fun npcSplatDelay(animId: Int): Int {
        val zero = when (splatDelayMode) {
            "ANIM" -> false
            "ZERO" -> true
            else -> com.opennxt.model.entity.rendering.UpdateBlockType.experimentalBuild()
        }
        if (zero) return 0
        val seqDuration = com.opennxt.model.definitions.SeqDefinitions.get(animId)?.durationInTicks ?: 1
        return (seqDuration / 2).coerceAtLeast(1) * 30
    }

    private val combatRandom = Random(bootSeed xor REAL_DAMAGE_SEED)

    private var realHits: Int = 0

    private var realMisses: Int = 0

    private var realRefusals: Int = 0

    private var lastRealRefusal: String? = null

    fun realHitCount(): Int = realHits
    fun realMissCount(): Int = realMisses
    fun realRefusalCount(): Int = realRefusals
    fun lastRealDamageRefusal(): String? = lastRealRefusal

    private val loggedRefusals = java.util.Collections.synchronizedSet(HashSet<String>())
    private val loggedAmmoNote = HashSet<Int>()
    private var lastRealRefusalTick = -1

    fun resolveHit(player: WorldPlayer, npc: WorldNpc, random: Random): HitResolution {
        val weapon = player.wornWeapon
        val derived = com.opennxt.model.world.PlayerCombatStats.derive(
            attackLevel = player.level(com.opennxt.api.stat.Stat.ATTACK),
            strengthLevel = player.level(com.opennxt.api.stat.Stat.STRENGTH),
            magicLevel = player.level(com.opennxt.api.stat.Stat.MAGIC),
            rangedLevel = player.level(com.opennxt.api.stat.Stat.RANGED),
            defenceLevel = player.level(com.opennxt.api.stat.Stat.DEFENCE),
            weapon = weapon,
            loadout = com.opennxt.model.world.PlayerCombatStats.loadoutOf(player)
        )
        val accuracy = derived.effectiveAccuracy
            ?: return HitResolution.NotComputable(
                "${player.name} has no derivable accuracy: ${derived.gaps.joinToString("; ")}"
            )
        val maxHitX10 = derived.effectiveMaxHitX10
            ?: if (swingMaxHitOwner?.invoke(player, derived.style) == true && swingMaxHitX10Override != null) 0
            else return HitResolution.NotComputable(
                "${player.name} has no derivable max hit: ${derived.gaps.joinToString("; ")}"
            )
        val combat = npc.combat
            ?: return HitResolution.NotComputable("no combat definition for npc ${npc.gameId}")
        val armour = combat.armour
            ?: return HitResolution.NotComputable(
                "npc ${npc.gameId} has no armour param (${NpcCombatParams.ARMOUR})"
            )
        if (derived.style != CombatStyle.MELEE && derived.attackRange == null) {
            return HitResolution.NotComputable(
                "${player.name}'s ${derived.style} weapon has no attack-range param (13): ${derived.gaps.joinToString("; ")}"
            )
        }
        val affinity = combat.affinityFor(derived.style)
            ?: return HitResolution.NotComputable(
                "npc ${npc.gameId} has no ${derived.style} affinity"
            )
        if (derived.style == CombatStyle.RANGED && (swingGate == null || !com.opennxt.content.combat.RangedAmmo.enabled) && loggedAmmoNote.add(weapon?.definition?.id ?: -1)) {
            logger.info { "COMBAT ${player.name} fires ${weapon?.definition?.name} without using ammunition (-D${com.opennxt.content.combat.RangedAmmo.FLAG})" }
        }
        swingGate?.let { gate -> gate(player, npc, derived.style)?.let { return HitResolution.NotComputable(it) } }
        val cappedMaxHitX10 = swingMaxHitX10Override?.invoke(player, derived.style, maxHitX10) ?: maxHitX10
        swingConsumer?.let { consume -> runCatching { consume(player, npc, derived.style) }.onFailure { logger.error(it) { "COMBAT swing consumer failed for ${player.name}" } } }

        val chance = CombatFormulas.hitChance(accuracy, armour, affinity)
        if (random.nextDouble() >= chance) return HitResolution.Missed(chance)
        val band = CombatFormulas.damageRoll(cappedMaxHitX10 / 10, random, CombatFormulas.PLAYER_AUTO_ATTACK_FLOOR_PERCENT)
        if (!CombatFormulas.criticalEnabled) return HitResolution.Landed(band, chance)
        val critLevel = player.level(when (derived.style) {
            CombatStyle.MELEE -> com.opennxt.api.stat.Stat.STRENGTH
            CombatStyle.RANGED -> com.opennxt.api.stat.Stat.RANGED
            CombatStyle.MAGIC -> com.opennxt.api.stat.Stat.MAGIC
        })
        return HitResolution.Landed(CombatFormulas.applyCritical(band, critLevel, random), chance)
    }

    var swingGate: ((WorldPlayer, WorldNpc, CombatStyle) -> String?)? = null
    var swingMaxHitX10Override: ((WorldPlayer, CombatStyle, Int) -> Int)? = null
    var swingConsumer: ((WorldPlayer, WorldNpc, CombatStyle) -> Unit)? = null
    var swingMaxHitOwner: ((WorldPlayer, CombatStyle) -> Boolean)? = null

    fun intervalFor(player: WorldPlayer): Int {
        val explicit = System.getProperty("opennxt.experiment.combat.interval")?.trim()?.toIntOrNull()
        if (explicit != null) return explicit.coerceAtLeast(1)
        if (!realDamageEnabled) return interval()
        if (basicAttackOnGcd) return BASIC_ATTACK_INTERVAL_TICKS
        val fromWeapon = player.wornWeapon?.attackSpeedTicks ?: return interval()
        return fromWeapon.coerceAtLeast(1)
    }

    const val BASIC_ATTACK_INTERVAL_TICKS = 3

    val basicAttackOnGcd: Boolean
        get() = System.getProperty("opennxt.combat.weaponspeed")?.trim()?.lowercase() == "gcd"

    val REAL_DAMAGE_PROVENANCE: String =
        "real damage (-Dopennxt.experiment.combat.realdamage=true): hit chance and damage derived from " +
            "weapon, level and npc params; swings that cannot be computed deal no damage"

    val chaseEnabled: Boolean
        get() = System.getProperty("opennxt.experiment.combat.chase") != "false"

    const val CHASE_REPATH_COOLDOWN_TICKS = 2

    const val CHASE_CANDIDATES = 3

    const val CHASE_SEARCH_CAP = 32

    const val CHASE_GIVE_UP_DISTANCE = 16

    private var chasePathfinds: Int = 0

    private var chaseNodes: Long = 0

    private var chaseRepaths: Int = 0

    private var chaseGiveUps: Int = 0

    fun chasePathfindCount(): Int = chasePathfinds
    fun chaseNodeCount(): Long = chaseNodes
    fun chaseRepathCount(): Int = chaseRepaths
    fun chaseGiveUpCount(): Int = chaseGiveUps

    fun attackTilesFor(npc: WorldNpc, fromX: Int, fromZ: Int, range: Int = ATTACK_RANGE): List<IntArray> {
        val loc = npc.location
        val size = (npc.combat?.size ?: 1).coerceAtLeast(1)
        val x0 = loc.x
        val z0 = loc.y
        val x1 = loc.x + size - 1
        val z1 = loc.y + size - 1
        val out = ArrayList<IntArray>()
        for (x in (x0 - range)..(x1 + range)) {
            for (z in (z0 - range)..(z1 + range)) {
                if (x in x0..x1 && z in z0..z1) continue
                if (!com.opennxt.model.map.CollisionMap.walkable(x, z, loc.plane)) continue
                out.add(intArrayOf(x, z))
            }
        }
        return out.sortedWith(
            compareBy(
                { maxOf(Math.abs(it[0] - fromX), Math.abs(it[1] - fromZ)) },
                { it[1] },
                { it[0] }
            )
        )
    }

    const val ATTACK_OPTION = 2

    const val ATTACK_ACTION = "Attack"

    const val DROP_SEED = 0x43424154L

    private class Engagement(val npc: WorldNpc, nextAttackAt: Int) {
        var nextAttackAt: Int = nextAttackAt

        var pursuit: AggroTarget? = null

        var pathedTargetX: Int = Int.MIN_VALUE
        var pathedTargetZ: Int = Int.MIN_VALUE

        var chaseDestX: Int = Int.MIN_VALUE
        var chaseDestZ: Int = Int.MIN_VALUE

        var lastRepathAt: Int = Int.MIN_VALUE
    }

    private val engagements = IdentityHashMap<WorldPlayer, Engagement>()

    internal object COMBAT_SLOT : com.opennxt.content.ActionSlot.Owner {
        override val actionName: String = "combat"
        override fun cancelSlot(player: com.opennxt.content.ContentPlayer, why: String) {
            val fighter = engagements.keys.firstOrNull { it.contentPlayer === player } ?: return
            logger.info { "COMBAT ${fighter.name}: fight ended - $why" }
            disengage(fighter)
        }
    }

    private val swingReadyAt = IdentityHashMap<WorldPlayer, Int>()

    var onPlayerSwing: ((WorldPlayer, WorldNpc) -> Unit)? = null

    private var swingHookFailures = 0
    fun swingHookFailureCount(): Int = swingHookFailures

    private var swingHolds = 0
    fun swingHoldCount(): Int = swingHolds

    fun swingReadyTick(player: WorldPlayer): Int? =
        maxOf(engagements[player]?.nextAttackAt ?: Int.MIN_VALUE, swingReadyAt[player] ?: Int.MIN_VALUE).takeIf { it > clock }

    fun holdSwing(player: WorldPlayer, untilTick: Int): Boolean {
        if (untilTick <= clock) return false
        var moved = false
        engagements[player]?.let { if (it.nextAttackAt < untilTick) { it.nextAttackAt = untilTick; moved = true } }
        if ((swingReadyAt[player] ?: Int.MIN_VALUE) < untilTick) { swingReadyAt[player] = untilTick; moved = true }
        if (moved) swingHolds++
        return moved
    }

    private fun notifySwing(player: WorldPlayer, npc: WorldNpc) {
        val hook = onPlayerSwing ?: return
        try {
            hook(player, npc)
        } catch (t: Throwable) {
            swingHookFailures++
            logger.error(t) { "COMBAT onPlayerSwing failed for ${player.name} on npc ${npc.gameId} [tick $clock]" }
        }
    }

    private val engagedNpcCounts = IdentityHashMap<WorldNpc, Int>()

    fun isInCombat(npc: WorldNpc): Boolean = engagedNpcCounts.containsKey(npc) || aggroTargets.containsKey(npc)

    private val diedThisTick = java.util.Collections.newSetFromMap(IdentityHashMap<WorldPlayer, Boolean>())

    private var planeDisengages = 0
    fun planeDisengageCount(): Int = planeDisengages

    private var retargetsHeld = 0
    fun retargetsHeldCount(): Int = retargetsHeld

    private var clock: Int = 0

    private val random = Random(bootSeed xor DROP_SEED)

    private var hitsApplied: Int = 0

    private var kills: Int = 0

    fun hitCount(): Int = hitsApplied
    fun killCount(): Int = kills
    fun ticks(): Int = clock
    fun engagementCount(): Int = engagements.size

    fun targetOf(player: WorldPlayer): WorldNpc? = engagements[player]?.npc

    fun reset() {
        engagements.clear()
        swingReadyAt.clear(); retargetsHeld = 0; swingHolds = 0; swingHookFailures = 0
        engagedNpcCounts.clear(); diedThisTick.clear(); planeDisengages = 0; returningHome.clear(); aggroHomeAttempts.clear(); aggroHomePathfinds = 0
        retaliationAttempts = 0; pursuits = 0
        loggedAmmoNote.clear(); loggedRangeless.clear(); lastRealRefusalTick = -1
        aggroTargets.clear(); aggroFirstSeen.clear()
        aggroAcquired = 0; aggroDropped = 0; aggroSwings = 0; aggroTolerated = 0; aggroPathfinds = 0; aggroPathFailures = 0; blockAnimations = 0
        clock = 0
        hitsApplied = 0
        kills = 0
        xpAwarded = 0.0
        random.setSeed(DROP_SEED)
        chasePathfinds = 0
        chaseNodes = 0
        chaseRepaths = 0
        chaseGiveUps = 0
        realHits = 0
        realMisses = 0
        realRefusals = 0
        lastRealRefusal = null
        loggedRefusals.clear()
        combatRandom.setSeed(REAL_DAMAGE_SEED)
        retaliationHits = 0
        retaliationDamage = 0
        playerDeaths = 0
        lastRetaliationRefusal = null
        retaliation = com.opennxt.model.world.NpcRetaliation()
        retaliationRandom.setSeed(RETALIATION_SEED)
    }

    fun shouldEngage(action: String?): Boolean = action != null && action.equals(ATTACK_ACTION, ignoreCase = true)

    fun engage(player: WorldPlayer, npc: WorldNpc): EngageResult {
        if (!enabled) return EngageResult.Disabled
        com.opennxt.content.ActionLock.reasonFor(player.contentPlayer)?.let {
            return EngageResult.Refused("${player.name} cannot fight while locked: $it")
        }
        if (!npc.alive) return EngageResult.Refused(
            "npc ${npc.gameId} is dead (respawns in ${npc.respawnTicksRemaining} ticks)"
        )
        if (npc.lifepoints == null) return EngageResult.Refused(
            "npc ${npc.gameId} (${npc.name ?: "unnamed"}) has no lifepoints"
        )
        if (npc.infoIndex < 0) return EngageResult.Refused(
            "npc ${npc.gameId} has no NPC_INFO slot"
        )

        val readyAt = maxOf(engagements[player]?.nextAttackAt ?: 0, swingReadyAt[player] ?: 0)
        if (readyAt > clock) retargetsHeld++
        val previous = engagements[player]
        if (previous != null && previous.npc === npc) {
            previous.nextAttackAt = readyAt
            if (faceEnabled) {
                npc.pendingUpdates.facePlayer(player.entity.index)
                PlayerUpdates.faceNpc(player.entity, npc.infoIndex)
            }
            TargetHud.show(player, npc)
            com.opennxt.content.ActionSlot.claim(player.contentPlayer, COMBAT_SLOT)
            return EngageResult.Engaged(npc)
        }
        if (previous != null) disengage(player)
        engagements[player] = Engagement(npc, readyAt)
        engagedNpcCounts[npc] = (engagedNpcCounts[npc] ?: 0) + 1
        com.opennxt.content.ActionSlot.claim(player.contentPlayer, COMBAT_SLOT)

        if (faceEnabled) {
            npc.pendingUpdates.facePlayer(player.entity.index)
            PlayerUpdates.faceNpc(player.entity, npc.infoIndex)
        }

        TargetHud.show(player, npc)
        return EngageResult.Engaged(npc)
    }

    fun disengage(player: WorldPlayer): Boolean {
        val engagement = engagements.remove(player) ?: return false
        com.opennxt.content.ActionSlot.release(player.contentPlayer, COMBAT_SLOT)
        engagedNpcCounts[engagement.npc]?.let { n -> if (n <= 1) engagedNpcCounts.remove(engagement.npc) else engagedNpcCounts[engagement.npc] = n - 1 }
        if (faceEnabled) {
            if (engagement.npc.alive) engagement.npc.pendingUpdates.faceNothing()
            PlayerUpdates.faceNothing(player.entity)
        }
        TargetHud.hide(player)
        return true
    }

    fun disengageForWalk(entity: com.opennxt.model.entity.PlayerEntity): Boolean {
        if (engagements.isEmpty()) return false
        val fighter = engagements.keys.firstOrNull { it.entity === entity } ?: return false
        return disengage(fighter)
    }

    fun forgetNpc(npc: WorldNpc) {
        val engaged = engagements.entries.filter { it.value.npc === npc }.map { it.key }
        for (player in engaged) disengage(player)
        engagedNpcCounts.remove(npc)
        aggroTargets.remove(npc)
        aggroFirstSeen.remove(npc)
        returningHome.remove(npc)
        aggroHomeAttempts.remove(npc)
        retaliationTargets.remove(npc)
        retaliation.forget(npc)
    }
    fun retaliationTracks(npc: WorldNpc): Boolean = retaliation.tracks(npc)

    private val retaliationTargets = IdentityHashMap<WorldNpc, WorldPlayer>()
    fun retaliationTargetOf(npc: WorldNpc): WorldPlayer? = retaliationTargets[npc]
    var retaliationDeferred: Int = 0
        private set

    private fun chooseRetaliationTargets(players: List<WorldPlayer>) {
        retaliationTargets.clear()
        val candidates = IdentityHashMap<WorldNpc, ArrayList<WorldPlayer>>()
        for (player in players) {
            val e = engagements[player] ?: continue
            val npc = e.npc
            if (!npc.alive) continue
            val loc = player.entity.location
            if (loc.plane != npc.location.plane) continue
            if (footprintDistance(npc, loc.x, loc.y) > npcAttackRange(npc)) continue
            candidates.getOrPut(npc) { ArrayList() }.add(player)
        }
        for ((npc, list) in candidates) {
            val aggro = aggroTargets[npc]?.player?.takeIf { a -> list.any { it === a } }
            retaliationTargets[npc] = aggro ?: list.maxByOrNull { npc.damageBy[it.name] ?: 0 } ?: list.first()
        }
    }

    fun tick(npcs: WorldNpcs, groundItems: GroundItems, players: List<WorldPlayer>): List<DeathResult> {
        if (!enabled) return emptyList()
        clock++
        if (!seedLogged) {
            seedLogged = true
            logger.info {
                "COMBAT rng seed=$bootSeed " + (if (seedPinned) "(pinned by -Dopennxt.combat.seed)"
                else "(random; -Dopennxt.combat.seed=$bootSeed to replay)")
            }
        }
        diedThisTick.clear()
        if (swingReadyAt.isNotEmpty()) swingReadyAt.values.removeIf { it <= clock }
        retaliation.tick()

        if (engagements.isNotEmpty()) {
            val live = java.util.Collections.newSetFromMap(IdentityHashMap<WorldPlayer, Boolean>())
            live.addAll(players)
            val gone = engagements.keys.filter { it !in live }
            for (player in gone) disengage(player)
        }

        val deaths = ArrayList<DeathResult>()
        chooseRetaliationTargets(players)
        for (player in players) {
            val engagement = engagements[player] ?: continue
            val npc = engagement.npc

            if (!npc.alive) {
                disengage(player)
                continue
            }

            if (com.opennxt.content.ActionLock.isLocked(player.contentPlayer)) continue

            val location = player.entity.location
            val target = npc.location
            if (location.plane != target.plane) {
                planeDisengages++
                disengage(player)
                continue
            }
            val distance = footprintDistance(npc, location.x, location.y)
            val playerRange = playerAttackRange(player)
            if (distance > playerRange) {
                if (!chase(player, engagement, npc, distance, playerRange)) {
                    continue
                }
                continue
            }

            val dest = player.entity.movement.destination()
            if (dest != null && dest.first == engagement.chaseDestX && dest.second == engagement.chaseDestZ) {
                player.entity.movement.reset()
                engagement.chaseDestX = Int.MIN_VALUE
                engagement.chaseDestZ = Int.MIN_VALUE
            }

            if (clock >= engagement.nextAttackAt) {
                engagement.nextAttackAt = clock + intervalFor(player)
                swingReadyAt[player] = engagement.nextAttackAt

                val animId = player.wornWeapon?.definition?.id?.let { itemId -> 
                    val params = WeaponAnimations.sequenceParamsFor(itemId)
                    params[4385] ?: params[2914] ?: params.values.firstOrNull()
                } ?: WeaponAnimations.selected() ?: 422

                var swingRefused = false
                val ledgerStyle = playerStyle(player)
                val ledgerNecromancy = isNecromancyWeapon(player.wornWeapon)
                val rolled: Int? = if (!realDamageEnabled) damage() else {
                    when (val resolution = resolveHit(player, npc, combatRandom)) {
                        is HitResolution.Landed -> { realHits++; resolution.damage }
                        is HitResolution.Missed -> { realMisses++; null }
                        is HitResolution.NotComputable -> {
                            realRefusals++
                            swingRefused = true
                            lastRealRefusalTick = clock
                            lastRealRefusal = resolution.reason
                            if (loggedRefusals.add(resolution.reason)) {
                                logger.warn {
                                    "COMBAT ${player.name}: swing not computable, no damage applied: ${resolution.reason}"
                                }
                            }
                            null
                        }
                    }
                }
                val requested: Int? = rolled?.let {
                    com.opennxt.content.ability.StatusEffects.scaleOutgoing(
                        player, com.opennxt.content.ability.AbilityActivation.weaponStyle(player.wornWeapon) ?: com.opennxt.content.ability.AbilityDefinitions.Style.MELEE, it)
                }
                if (!swingRefused) {
                    com.opennxt.model.entity.rendering.PlayerUpdates.animate(player.entity, animId)
                    notifySwing(player, npc)
                }
                val before = if (requested == null) null else npc.currentLifepoints
                if (requested != null && before != null) {
                val death = npcs.applyDamage(npc, requested, random, groundItems, player.name, ledgerStyle, ledgerNecromancy)
                val applied = before - (npc.currentLifepoints ?: 0)
                if (applied > 0) hitsApplied++
                if (applied > 0) TargetHud.update(player, npc)

                val type = splatType
                if (type != null && applied > 0) {
                    npc.pendingUpdates.hit(NpcHitsBlock.Hit(type, applied, npcSplatDelay(animId)))
                }

                if (death == null && applied > 0) {
                    NpcCombatDefs.defenceAnimation(npc.gameId)?.let { npc.pendingUpdates.animate(it.value); blockAnimations++ }
                }

                if (death != null) {
                    kills++
                    deaths += death
                    awardKillExperience(death, npc, players)
                    logger.info {
                        "COMBAT ${player.name} killed ${npc.name ?: "npc${npc.gameId}"} (${npc.gameId}) - " +
                            "respawns in ${death.respawnTicks} ticks; drops: " +
                            (if (death.dropped.isEmpty()) "none" else death.dropped.joinToString()) +
                            (if (death.unexpandedTableRolls.isEmpty()) ""
                            else " [unexpanded: ${death.unexpandedTableRolls.size}]")
                    }
                    disengage(player)
                    continue
                }
                }
            }

            if (distance <= npcAttackRange(npc)) {
                if (retaliationTargets[npc] !== player) retaliationDeferred++
            } else {
                val pursuit = aggroTargets[npc]?.takeIf { it.player === player }
                    ?: engagement.pursuit ?: AggroTarget(player).also { engagement.pursuit = it }
                pursuits++
                aggroWalk(npc, pursuit)
            }
        }

        for ((npc, chosen) in ArrayList(retaliationTargets.entries).map { it.key to it.value }) {
            if (!npc.alive) continue
            if (engagements[chosen]?.npc !== npc) continue
            if (chosen in diedThisTick) continue
            val loc = chosen.entity.location
            if (loc.plane != npc.location.plane) continue
            if (footprintDistance(npc, loc.x, loc.y) > npcAttackRange(npc)) continue
            retaliate(chosen, npc)
        }

        aggressionTick(npcs, players)
        return deaths
    }

    const val AGGRO_SWITCH = "opennxt.experiment.npcs.aggro"

    const val AGGRO_TOLERANCE_TICKS = 1000

    const val AGGRO_TOLERANCE_RESET_DISTANCE = 32

    const val AGGRO_REPATH_TICKS = 4

    const val AGGRO_ALWAYS_LEVEL = 76

    val aggroRadius: Int?
        get() {
            val raw = System.getProperty(AGGRO_SWITCH)?.trim() ?: return null
            if (raw.equals("off", ignoreCase = true)) return null
            return raw.toIntOrNull()?.takeIf { it > 0 }
        }

    fun aggressiveTowards(npcLevel: Int, playerLevel: Int): Boolean =
        npcLevel >= AGGRO_ALWAYS_LEVEL || playerLevel < 2 * npcLevel + 1

    fun isAggressive(npc: WorldNpc): Boolean =
        (SeedData.refCombat(npc.gameId)?.aggressive ?: NpcProfiles.aggressive(npc.gameId) ?: NpcCombatDefs.aggressive(npc.gameId)) == true

    private class AggroTarget(val player: WorldPlayer) {
        var lastPathTick = -1_000_000
        var lastPathX = Int.MIN_VALUE
        var lastPathY = Int.MIN_VALUE
    }

    private val aggroTargets = IdentityHashMap<WorldNpc, AggroTarget>()

    private val aggroFirstSeen = IdentityHashMap<WorldNpc, IdentityHashMap<WorldPlayer, Int>>()

    private var aggroAcquired = 0
    private var aggroDropped = 0
    private var aggroSwings = 0
    private var aggroTolerated = 0

    fun aggroAcquiredCount(): Int = aggroAcquired
    fun aggroDroppedCount(): Int = aggroDropped
    fun aggroSwingCount(): Int = aggroSwings
    fun aggroToleratedCount(): Int = aggroTolerated
    fun aggroTargetOf(npc: WorldNpc): WorldPlayer? = aggroTargets[npc]?.player
    fun aggroTargetCount(): Int = aggroTargets.size

    private fun combatLevelOf(player: WorldPlayer): Int =
        com.opennxt.model.world.PlayerCombatStats.combatLevel(player)

    private fun aggressionTick(npcs: WorldNpcs, players: List<WorldPlayer>) {
        val radius = aggroRadius ?: run {
            if (aggroTargets.isNotEmpty()) { aggroTargets.clear(); aggroFirstSeen.clear() }
            return
        }
        if (players.isEmpty()) { aggroTargets.clear(); aggroFirstSeen.clear(); return }
        if (aggroFirstSeen.isNotEmpty()) {
            val live = java.util.Collections.newSetFromMap(IdentityHashMap<WorldPlayer, Boolean>())
            live.addAll(players)
            val outer = aggroFirstSeen.entries.iterator()
            while (outer.hasNext()) {
                val (_, seen) = outer.next()
                seen.keys.removeAll { it !in live }
                if (seen.isEmpty()) outer.remove()
            }
        }

        val it = aggroTargets.entries.iterator()
        while (it.hasNext()) {
            val (npc, target) = it.next()
            val player = target.player
            val drop = when {
                !npc.alive -> "npc died"
                player !in players -> "player left the world"
                player.currentLifepoints <= 0 -> "player is dead"
                player in diedThisTick -> "player died this tick"
                player.entity.location.plane != npc.location.plane -> "plane changed"
                distance(npc, player) > radius * 2 -> "player beyond twice the radius"
                beyondLeash(npc) -> "npc beyond its leash; walking home"
                tolerated(npc, player) -> "tolerance"
                else -> null
            }
            if (drop != null) {
                it.remove(); aggroDropped++
                npc.pendingUpdates.faceNothing()
                if (npc.alive && beyondLeash(npc)) {
                    returningHome[npc] = clock; aggroHomePathfinds++
                    npc.movement.walkTo(npc.spawn.x, npc.spawn.y, nearest = true, search = CHASE_SEARCH_CAP)
                }
                logger.info { "AGGRO npc ${npc.gameId} (${npc.name}) dropped ${player.name}: $drop [tick $clock]" }
                continue
            }
            val d = distance(npc, player)
            if (d > npcAttackRange(npc)) {
                aggroWalk(npc, target)
            } else {
                aggroSwings++
                retaliate(player, npc)
                if (player in diedThisTick) { it.remove(); aggroDropped++ }
            }
        }

        if (returningHome.isNotEmpty()) {
            val home = returningHome.entries.iterator()
            while (home.hasNext()) {
                val entry = home.next()
                val npc = entry.key
                if (!npc.alive) { home.remove(); continue }
                if (npc.movement.hasSteps) continue
                if (beyondLeash(npc)) {
                    if (clock - entry.value < AGGRO_REPATH_TICKS) continue
                    if (aggroHomeAttempts.merge(npc, 1, Int::plus)!! > AGGRO_HOME_ATTEMPTS) {
                        home.remove(); aggroHomeAttempts.remove(npc)
                        logger.info { "AGGRO npc ${npc.gameId} (${npc.name}) found no route home after $AGGRO_HOME_ATTEMPTS attempts [tick $clock]" }
                        continue
                    }
                    entry.setValue(clock); aggroHomePathfinds++
                    npc.movement.walkTo(npc.spawn.x, npc.spawn.y, nearest = true, search = CHASE_SEARCH_CAP)
                } else { home.remove(); aggroHomeAttempts.remove(npc) }
            }
        }

        for (npc in npcs.all()) {
            if (!npc.alive || aggroTargets.containsKey(npc) || npc.lifepoints == null) continue
            if (returningHome.containsKey(npc)) continue
            val level = npc.combat?.combatLevel ?: 0
            if (level <= 0) continue
            var candidate: WorldPlayer? = null
            var best = Int.MAX_VALUE
            val npcRadius = aggroRadiusFor(npc, radius)
            for (player in players) {
                if (player.currentLifepoints <= 0) continue
                if (player in diedThisTick) continue
                if (player.entity.location.plane != npc.location.plane) continue
                val d = distance(npc, player)
                if (d > npcRadius) {
                    if (d > AGGRO_TOLERANCE_RESET_DISTANCE) aggroFirstSeen[npc]?.remove(player)
                    continue
                }
                if (!isAggressive(npc)) continue
                aggroFirstSeen.getOrPut(npc) { IdentityHashMap() }.putIfAbsent(player, clock)
                if (tolerated(npc, player)) continue
                if (!aggressiveTowards(level, combatLevelOf(player))) continue
                if (d < best) { best = d; candidate = player }
            }
            val chosen = candidate ?: continue
            aggroTargets[npc] = AggroTarget(chosen)
            aggroAcquired++
            chosen.entity.index.let { idx -> if (idx >= 0) npc.pendingUpdates.facePlayer(idx) }
            logger.info {
                "AGGRO npc ${npc.gameId} (${npc.name}, level $level) targets ${chosen.name} " +
                    "(level ${combatLevelOf(chosen)}) at distance $best [tick $clock, radius $radius]"
            }
        }
    }

    private fun beyondLeash(npc: WorldNpc): Boolean =
        maxOf(Math.abs(npc.location.x - npc.spawn.x), Math.abs(npc.location.y - npc.spawn.y)) > com.opennxt.model.world.NpcWander.LEASH_RADIUS

    fun aggroFirstSeenCount(): Int = aggroFirstSeen.values.sumOf { it.size }

    private fun distance(npc: WorldNpc, player: WorldPlayer): Int =
        footprintDistance(npc, player.entity.location.x, player.entity.location.y)

    fun footprintDistance(npc: WorldNpc, px: Int, pz: Int): Int {
        val loc = npc.location
        val size = (npc.combat?.size ?: 1).coerceAtLeast(1)
        val dx = maxOf(loc.x - px, px - (loc.x + size - 1), 0)
        val dz = maxOf(loc.y - pz, pz - (loc.y + size - 1), 0)
        return maxOf(dx, dz)
    }

    private fun tolerated(npc: WorldNpc, player: WorldPlayer): Boolean {
        val first = aggroFirstSeen[npc]?.get(player) ?: return false
        val docile = clock - first >= AGGRO_TOLERANCE_TICKS
        if (docile) aggroTolerated++
        return docile
    }

    private var blockAnimations = 0
    fun blockAnimationCount(): Int = blockAnimations

    fun npcAttackRange(npc: WorldNpc): Int =
        NpcCombatDefs.attackRange(npc.gameId)?.value?.takeIf { it > ATTACK_RANGE } ?: ATTACK_RANGE

    fun aggroRadiusFor(npc: WorldNpc, switchRadius: Int): Int =
        NpcCombatDefs.aggroDistance(npc.gameId)?.value?.takeIf { it > 0 } ?: switchRadius

    private val returningHome = IdentityHashMap<WorldNpc, Int>()
    fun returningHomeCount(): Int = returningHome.size

    const val AGGRO_HOME_ATTEMPTS = 5

    private var aggroHomePathfinds = 0
    private val aggroHomeAttempts = IdentityHashMap<WorldNpc, Int>()
    fun aggroHomePathfindCount(): Int = aggroHomePathfinds

    private var aggroPathfinds = 0
    private var aggroPathFailures = 0
    fun aggroPathfindCount(): Int = aggroPathfinds
    fun aggroPathFailureCount(): Int = aggroPathFailures

    private fun aggroWalk(npc: WorldNpc, target: AggroTarget) {
        val loc = target.player.entity.location
        if (com.opennxt.content.ability.StatusEffects.blocksStep(npc)) return
        if (npc.movement.hasSteps) return
        if (clock - target.lastPathTick < AGGRO_REPATH_TICKS) return
        target.lastPathTick = clock
        target.lastPathX = loc.x
        target.lastPathY = loc.y
        val from = npc.location
        val candidates = ArrayList<IntArray>()
        val leash = com.opennxt.model.world.NpcWander.LEASH_RADIUS
        for (dx in -ATTACK_RANGE..ATTACK_RANGE) for (dy in -ATTACK_RANGE..ATTACK_RANGE) {
            if (dx == 0 && dy == 0) continue
            val cx = loc.x + dx; val cy = loc.y + dy
            if (Math.abs(cx - npc.spawn.x) > leash || Math.abs(cy - npc.spawn.y) > leash) continue
            candidates.add(intArrayOf(cx, cy))
        }
        candidates.sortBy { maxOf(Math.abs(it[0] - from.x), Math.abs(it[1] - from.y)) }
        var tried = 0
        for (c in candidates) {
            if (tried >= CHASE_CANDIDATES) break
            tried++
            aggroPathfinds++
            if (npc.movement.walkTo(c[0], c[1], nearest = false, search = CHASE_SEARCH_CAP)) return
        }
        aggroPathFailures++
    }

    private fun chase(player: WorldPlayer, engagement: Engagement, npc: WorldNpc, distance: Int, range: Int = ATTACK_RANGE): Boolean {
        if (distance > CHASE_GIVE_UP_DISTANCE) {
            chaseGiveUps++
            logger.info {
                "COMBAT ${player.name} disengaged from ${npc.name ?: "npc${npc.gameId}"}: $distance tiles away " +
                    "(limit $CHASE_GIVE_UP_DISTANCE)"
            }
            disengage(player)
            return false
        }
        if (!chaseEnabled) return true

        val target = npc.location
        val targetMoved = target.x != engagement.pathedTargetX || target.y != engagement.pathedTargetZ
        val idle = !player.entity.movement.hasSteps
        if (!targetMoved && !idle) return true
        if (engagement.lastRepathAt != Int.MIN_VALUE &&
            clock - engagement.lastRepathAt < CHASE_REPATH_COOLDOWN_TICKS
        ) return true

        engagement.lastRepathAt = clock
        engagement.pathedTargetX = target.x
        engagement.pathedTargetZ = target.y
        chaseRepaths++

        val from = player.entity.location
        val window = (2 * distance + 2).coerceIn(8, CHASE_SEARCH_CAP)
        val candidates = attackTilesFor(npc, from.x, from.y, range)
        var tried = 0
        for (c in candidates) {
            if (tried >= CHASE_CANDIDATES) break
            tried++
            val queued = player.entity.movement.walkTo(c[0], c[1], nearest = false, search = window)
            chasePathfinds++
            chaseNodes += com.opennxt.model.map.PathFinder.lastNodesVisited()
            if (queued) {
                engagement.chaseDestX = c[0]
                engagement.chaseDestZ = c[1]
                return true
            }
        }
        engagement.chaseDestX = Int.MIN_VALUE
        engagement.chaseDestZ = Int.MIN_VALUE
        return true
    }

    val retaliationEnabled: Boolean
        get() = System.getProperty("opennxt.experiment.combat.retaliate") != "false"

    private var retaliation = com.opennxt.model.world.NpcRetaliation()

    const val RETALIATION_SEED = 0x5254414cL

    private val retaliationRandom = Random(bootSeed xor RETALIATION_SEED)

    private var retaliationHits: Int = 0

    private var retaliationDamage: Int = 0

    private var playerDeaths: Int = 0

    private var lastRetaliationRefusal: String? = null

    fun retaliationHitCount(): Int = retaliationHits
    fun retaliationDamageTotal(): Int = retaliationDamage
    fun playerDeathCount(): Int = playerDeaths
    fun lastRetaliationRefusal(): String? = lastRetaliationRefusal

    private var retaliationAttempts = 0
    fun retaliationAttemptCount(): Int = retaliationAttempts

    private var pursuits = 0
    fun pursuitCount(): Int = pursuits

    fun playerAttackRange(player: WorldPlayer): Int {
        val weapon = player.wornWeapon ?: return ATTACK_RANGE
        val range = weapon.attackRange
        if (range == null && loggedRangeless.add(weapon.definition.id)) {
            logger.warn { "COMBAT weapon ${weapon.definition.id} (${weapon.definition.name}, ${weapon.style ?: "no style"}) has no attack-range param (13) [tick $clock]" }
        }
        return range ?: ATTACK_RANGE
    }
    private val loggedRangeless = HashSet<Int>()

    fun playerStyle(player: WorldPlayer): CombatStyle = player.wornWeapon?.style ?: CombatStyle.MELEE

    fun isNecromancyWeapon(weapon: com.opennxt.model.world.EquippedWeapon?): Boolean =
        weapon?.requirementSkill == com.opennxt.api.stat.Stat.NECROMANCY.id

    fun recordClickWalk(player: WorldPlayer, destX: Int, destZ: Int) {
        val e = engagements[player] ?: return
        e.chaseDestX = destX; e.chaseDestZ = destZ
    }

    fun nearestAttackTile(npc: WorldNpc, fromX: Int, fromZ: Int, range: Int): IntArray? =
        attackTilesFor(npc, fromX, fromZ, range).firstOrNull()

    private fun retaliate(player: WorldPlayer, npc: WorldNpc) {
        if (!retaliationEnabled) return
        retaliationAttempts++
        try {
            val before = player.currentLifepoints
            when (val outcome = retaliation.retaliate(npc, player, retaliationRandom)) {
                is com.opennxt.model.world.RetaliationOutcome.Refused -> {
                    lastRetaliationRefusal = outcome.reason
                }
                is com.opennxt.model.world.RetaliationOutcome.NotDue -> {}
                is com.opennxt.model.world.RetaliationOutcome.Missed -> {
                    val npcAnimId = npcAttackAnimation(npc)
                    if (npcAnimId != null) npc.pendingUpdates.animate(npcAnimId)
                }
                is com.opennxt.model.world.RetaliationOutcome.Hit -> {
                    val npcAnimId = npcAttackAnimation(npc)
                    if (npcAnimId != null) npc.pendingUpdates.animate(npcAnimId)
                    onRetaliationDamage(player, npc, before - player.currentLifepoints, npcAnimId ?: -1)
                }
                is com.opennxt.model.world.RetaliationOutcome.KilledPlayer -> {
                    val npcAnimId = npcAttackAnimation(npc)
                    if (npcAnimId != null) npc.pendingUpdates.animate(npcAnimId)
                    onRetaliationDamage(player, npc, before)
                    playerDeaths++
                    diedThisTick.add(player)
                    logger.info {
                        "COMBAT ${player.name} was killed by ${npc.name ?: "npc${npc.gameId}"} " +
                            "(${npc.gameId}) at (${outcome.death.diedAt.x},${outcome.death.diedAt.y}), " +
                            "respawned at (${outcome.death.respawnedAt.x},${outcome.death.respawnedAt.y}) " +
                            "with ${outcome.death.lifepointsRestored} lp"
                    }
                    disengage(player)
                }
            }
        } catch (t: Throwable) {
            logger.error(t) { "Retaliation by npc ${npc.gameId} against ${player.name} failed" }
        }
    }

    fun npcAttackAnimation(npc: WorldNpc): Int? =
        WeaponAnimations.npcSelected(npc.gameId)
            ?: NpcProfiles.attackAnimation(npc.gameId)
            ?: WeaponAnimations.npcSequenceParams(npc.gameId)[2914]
            ?: WeaponAnimations.npcSequenceParams(npc.gameId).values.firstOrNull()

    private fun onRetaliationDamage(player: WorldPlayer, npc: WorldNpc, amount: Int, animId: Int = 422) {
        if (amount <= 0) return
        retaliationHits++
        retaliationDamage += amount
        val type = splatType ?: return
        if (!faceEnabled) return
        val seqDuration = com.opennxt.model.definitions.SeqDefinitions.get(animId)?.durationInTicks ?: 1
        val splatDelay = (seqDuration / 2).coerceAtLeast(1) * 30
        PlayerUpdates.hit(player.entity, com.opennxt.model.entity.rendering.blocks.PlayerHitsBlock.Hit(type, amount, splatDelay))
    }

    val RETALIATION_PROVENANCE =
        "retaliation: npcs attack with their own accuracy, damage and speed params; players have " +
            "${Lifepoints.PER_CONSTITUTION_LEVEL} lp per Constitution level and respawn at " +
            "(${com.opennxt.model.world.HeadlessPlayer.LUMBRIDGE_RESPAWN_X}," +
            "${com.opennxt.model.world.HeadlessPlayer.LUMBRIDGE_RESPAWN_Y})"

    const val XP_TENTHS_PER_DAMAGE = com.opennxt.model.world.HeadlessPlayer.COMBAT_XP_TENTHS_PER_DAMAGE

    const val CONSTITUTION_XP_TENTHS_PER_100 =
        com.opennxt.model.world.HeadlessPlayer.CONSTITUTION_XP_TENTHS_PER_100_DAMAGE

    val XP_STAT = com.opennxt.api.stat.Stat.ATTACK

    val XP_PROVENANCE =
        CombatXp.PROVENANCE + " Awarded to the weapon's style skill ($XP_STAT unarmed) and Constitution."

    private var xpAwarded: Double = 0.0

    fun experienceAwarded(): Double = xpAwarded

    fun xpStatFor(style: CombatStyle): com.opennxt.api.stat.Stat = when (style) {
        CombatStyle.MELEE -> XP_STAT
        CombatStyle.RANGED -> com.opennxt.api.stat.Stat.RANGED
        CombatStyle.MAGIC -> com.opennxt.api.stat.Stat.MAGIC
    }

    private fun awardKillExperience(death: DeathResult, npc: WorldNpc, players: List<WorldPlayer>) {
        if (death.damageLedger.isEmpty()) return
        val byName = HashMap<String, WorldPlayer>()
        for (p in players) byName[p.name] = p
        for (name in death.damageLedger.keys) {
            val player = byName[name] ?: continue
            val award = CombatXp.killAward(death, name, npc.gameId)
            try {
                for ((stat, xp) in award) {
                    val levels = player.stats.addExperience(stat, xp)
                    xpAwarded += xp
                    if (levels > 0) logger.info {
                        "COMBAT ${player.name} gained $levels ${stat.name} level(s) -> ${player.stats.getLevel(stat)} " +
                            "(${player.stats[stat].experience} xp) killing ${npc.name ?: "npc${npc.gameId}"}"
                    }
                }
                killAwards++
            } catch (t: Throwable) {
                logger.error(t) { "Kill xp award to ${player.name} failed" }
            }
        }
    }
    var killAwards: Int = 0
        private set

    @Suppress("unused")
    private fun awardExperience(player: WorldPlayer, damage: Int, npc: WorldNpc, style: CombatStyle = CombatStyle.MELEE) {
        val styleXp = CombatXp.styleXp(npc.gameId, damage)
        val constitutionXp = CombatXp.constitutionXp(styleXp)
        val stat = xpStatFor(style)
        try {
            val levels = player.stats.addExperience(stat, styleXp)
            player.stats.addExperience(com.opennxt.api.stat.Stat.CONSTITUTION, constitutionXp)
            xpAwarded += styleXp + constitutionXp
            if (levels > 0) {
                logger.info {
                    "COMBAT ${player.name} gained $levels ${stat.name} level(s) -> " +
                        "${player.stats.getLevel(stat)} (${player.stats[stat].experience} xp)"
                }
            }
        } catch (t: Throwable) {
            logger.error(t) { "Awarding $styleXp xp to ${player.name} failed" }
        }
    }

    fun damage(): Int =
        System.getProperty("opennxt.experiment.combat.hit")?.trim()?.toIntOrNull() ?: DEMO_HIT

    fun interval(): Int =
        (System.getProperty("opennxt.experiment.combat.interval")?.trim()?.toIntOrNull()
            ?: ATTACK_INTERVAL_TICKS).coerceAtLeast(1)

    val PROVENANCE =
        "flat combat: $DEMO_HIT damage every $ATTACK_INTERVAL_TICKS ticks, $HITMARK_PROVENANCE. $XP_PROVENANCE"

    const val PROVENANCE_SHORT = "[flat combat]"

    data class CombatFlag(
        val property: String,
        val default: String,
        val group: String,
        val meaning: String,
        val resolve: () -> String
    )

    private fun onOff(b: Boolean): String = if (b) "ON" else "OFF"

    private fun defaultOnProp(name: String): String = onOff(System.getProperty(name) != "false")

    private fun defaultOffProp(name: String): String = onOff(System.getProperty(name) == "true")

    val FLAGS: List<CombatFlag> = listOf(
        CombatFlag(
            "opennxt.experiment.combat", "OFF", "loop",
            "enables combat"
        ) { onOff(enabled) },
        CombatFlag(
            "opennxt.experiment.combat.realdamage", "OFF", "loop",
            "derives hit chance and damage from stats and equipment instead of the flat hit"
        ) { onOff(realDamageEnabled) },
        CombatFlag(
            "opennxt.experiment.combat.chase", "ON", "loop",
            "player follows a moving target"
        ) { onOff(chaseEnabled) },
        CombatFlag(
            "opennxt.experiment.combat.retaliate", "ON", "loop",
            "npcs hit back"
        ) { onOff(retaliationEnabled) },
        CombatFlag(
            "opennxt.experiment.combat.face", "ON", "loop",
            "player and npc turn to face each other"
        ) { onOff(faceEnabled) },
        CombatFlag(
            "opennxt.experiment.combat.hit", DEMO_HIT.toString(), "loop",
            "damage per hit when realdamage is off"
        ) { damage().toString() },
        CombatFlag(
            "opennxt.experiment.combat.interval", ATTACK_INTERVAL_TICKS.toString(), "loop",
            "ticks between swings; when unset, realdamage uses the weapon's attack speed"
        ) { interval().toString() },
        CombatFlag(
            "opennxt.experiment.combat.splattype", DEFAULT_HITMARK.toString(), "loop",
            "hitmark id for damage splats; none disables splats"
        ) { splatType?.toString() ?: "NOSPLAT" },
        CombatFlag(
            "opennxt.experiment.combat.splatDelay", "BUILD", "loop",
            "npc splat delay: BUILD picks per build, anim or zero forces one"
        ) { splatDelayMode },
        CombatFlag(
            "opennxt.experiment.combat.deathlinger", "0", "loop",
            "extra ticks a corpse stays visible"
        ) { NpcDeathTransmission.lingerTicks.toString() },
        CombatFlag(
            "opennxt.experiment.combat.anim.npcslot", "UNSET", "loop",
            "param id for npc attack animations"
        ) { WeaponAnimations.npcSlotConfigured()?.toString() ?: "UNSET" },
        CombatFlag(
            NpcAnimTable.DEATH_SWITCH, "TABLE", "loop",
            "npc death animation: TABLE, off, or a sequence id for every npc"
        ) { NpcAnimTable.mode },
        CombatFlag(
            "opennxt.experiment.combat.anim.weapon", "UNSET", "loop",
            "item:param for player weapon animations"
        ) { WeaponAnimations.configured()?.let { "${it.first}:${it.second}" } ?: "UNSET" },

        CombatFlag(
            "opennxt.experiment.combat.demospawn",
            "${com.opennxt.model.entity.updating.NpcInfoEncoder.DEMO_X}," +
                "${com.opennxt.model.entity.updating.NpcInfoEncoder.DEMO_Y}," +
                "${com.opennxt.model.entity.updating.NpcInfoEncoder.DEMO_PLANE}",
            "target",
            "tile of the combat demo npc near the login tile"
        ) {
            WorldNpcs.combatDemoSpawn?.let { "${it.x},${it.y},${it.plane}" } ?: "NOSPAWN"
        },
        CombatFlag(
            "opennxt.experiment.combat.demospawn.wander", "ON", "target",
            "demo npc wanders like other npcs"
        ) { onOff(WorldNpcs.combatDemoWander) },
        CombatFlag(
            "opennxt.experiment.npcs.spawns", "ON", "target",
            "spawns npcs from the cache"
        ) { defaultOnProp("opennxt.experiment.npcs.spawns") },
        CombatFlag(
            "opennxt.experiment.npcs.wander", "ON", "target",
            "npcs wander"
        ) { defaultOnProp("opennxt.experiment.npcs.wander") },
        CombatFlag(
            AGGRO_SWITCH, "OFF", "target",
            "aggression radius in tiles for aggressive npcs"
        ) { aggroRadius?.toString() ?: "OFF" },
        CombatFlag(
            "opennxt.experiment.npcs", "ON", "target",
            "sends NPC_INFO"
        ) { defaultOnProp("opennxt.experiment.npcs") },
        CombatFlag(
            "opennxt.experiment.seed.variantDisambiguation", "ON", "target",
            "per-variant npc lifepoints"
        ) { onOff(SeedData.variantDisambiguationEnabled) },

        CombatFlag(
            "opennxt.experiment.npcs.extended", "ON", "visible",
            "npc extended-info blocks (splats, facing)"
        ) { defaultOnProp("opennxt.experiment.npcs.extended") },
        CombatFlag(
            "opennxt.experiment.npcs.block.hits", "ON", "visible",
            "npc hit splats"
        ) { defaultOnProp("opennxt.experiment.npcs.block.hits") },
        CombatFlag(
            "opennxt.experiment.npcs.block.face_entity", "ON", "visible",
            "npc face-entity block"
        ) { defaultOnProp("opennxt.experiment.npcs.block.face_entity") },
        CombatFlag(
            "opennxt.experiment.npcs.block.lifepoints", "ON", "visible",
            "npc lifepoints block for the target panel"
        ) { defaultOnProp("opennxt.experiment.npcs.block.lifepoints") },
        CombatFlag(
            "opennxt.experiment.playerExtended", "ON", "visible",
            "player extended-info blocks (splats, facing)"
        ) { defaultOnProp("opennxt.experiment.playerExtended") },
        CombatFlag(
            "opennxt.experiment.player.block.hits", "ON", "visible",
            "player hit splats"
        ) { defaultOnProp("opennxt.experiment.player.block.hits") },
        CombatFlag(
            "opennxt.experiment.player.block.face_entity", "ON", "visible",
            "player face-entity block"
        ) { defaultOnProp("opennxt.experiment.player.block.face_entity") },
        CombatFlag(
            "opennxt.experiment.npcs.healthbars", "ON", "visible",
            "npc health bars"
        ) { defaultOnProp("opennxt.experiment.npcs.healthbars") },
        CombatFlag(
            "opennxt.experiment.player.healthbars", "ON", "visible",
            "player health bars"
        ) { defaultOnProp("opennxt.experiment.player.healthbars") },
        CombatFlag(
            "opennxt.experiment.combat.targethud", "ON", "visible",
            "target information panel"
        ) { defaultOnProp("opennxt.experiment.combat.targethud") },

        CombatFlag(
            "opennxt.experiment.equip", "OFF", "weapon",
            "allows equipping items; realdamage needs it"
        ) { defaultOffProp("opennxt.experiment.equip") },
        CombatFlag(
            "opennxt.equip.command", "::equip", "weapon",
            "chat prefix that equips an item id"
        ) { System.getProperty("opennxt.equip.command")?.trim()?.takeIf { it.isNotEmpty() } ?: "::equip" },

        CombatFlag(
            "opennxt.experiment.sendStats", "OFF", "xp",
            "sends UPDATE_STAT on xp gain (not supported by the 949 client)"
        ) { defaultOffProp("opennxt.experiment.sendStats") }
    )

    fun resolvedValueOf(flag: CombatFlag): String =
        try {
            flag.resolve()
        } catch (t: Throwable) {
            "UNRESOLVED-${t.javaClass.simpleName}"
        }

    fun tokenFor(flag: CombatFlag): String = "${flag.property}=${resolvedValueOf(flag)};"

    fun flagEffectLine(): String {
        if (!enabled) {
            return "combat.flags[EFFECT]: combat is disabled"
        }
        if (WorldNpcs.combatDemoSpawn == null && System.getProperty("opennxt.experiment.npcs.spawns") == "false") {
            return "combat.flags[EFFECT]: combat is enabled but no npcs are spawned"
        }
        if (WorldNpcs.combatDemoSpawn == null) {
            return "combat.flags[EFFECT]: combat is enabled; demo spawn is off, so there are no npcs near the login tile"
        }
        if (System.getProperty("opennxt.experiment.npcs") == "false" ||
            System.getProperty("opennxt.experiment.npcs.extended") == "false"
        ) {
            return "combat.flags[EFFECT]: combat is enabled but npc updates are off, so hits will not be shown"
        }
        if (realDamageEnabled && System.getProperty("opennxt.experiment.equip") != "true") {
            return "combat.flags[EFFECT]: realdamage is on but equip is off; every swing will deal no damage"
        }
        if (realDamageEnabled) {
            return "combat.flags[EFFECT]: combat is enabled with derived damage and weapon attack speed"
        }
        return "combat.flags[EFFECT]: combat is enabled with flat damage, ${damage()} every ${interval()} tick(s); " +
            "demo npc ${WorldNpcs.COMBAT_DEMO_NPC} has " +
            "${SeedData.lifepoints(WorldNpcs.COMBAT_DEMO_NPC)?.value ?: -1} lp (${killHitsForDemoTarget()} hit(s) to kill)"
    }

    fun killHitsForDemoTarget(): Int {
        val lp = SeedData.lifepoints(WorldNpcs.COMBAT_DEMO_NPC)?.value ?: return -1
        val d = damage()
        if (d <= 0) return -1
        return (lp + d - 1) / d
    }

    fun flagStateLines(): List<String> {
        val out = ArrayList<String>()
        out.add(
            "combat flags: ${FLAGS.size} switches"
        )
        for (group in FLAGS.map { it.group }.distinct()) {
            out.add(
                "combat.flags[$group]: " +
                    FLAGS.filter { it.group == group }.joinToString(" ") { tokenFor(it) }
            )
        }
        out.add(try { flagEffectLine() } catch (t: Throwable) { "combat.flags[EFFECT]: UNRESOLVED-${t.javaClass.simpleName}" })
        return out
    }

    @Volatile
    private var flagStateLogged = false

    fun logFlagStateOnce() {
        if (flagStateLogged) return
        synchronized(this) {
            if (flagStateLogged) return
            flagStateLogged = true
            flagStateLines().forEach { line -> logger.info { line } }
        }
    }

    @Volatile
    private var provenanceLogged = false

    fun logProvenanceOnce() {
        if (provenanceLogged) return
        synchronized(this) {
            if (provenanceLogged) return
            provenanceLogged = true
            logFlagStateOnce()
            logger.info { "combat: $PROVENANCE" }
        }
    }
}

sealed class EngageResult {
    object Disabled : EngageResult()

    data class Refused(val reason: String) : EngageResult()

    data class Engaged(val npc: WorldNpc) : EngageResult()
}

sealed class HitResolution {
    data class Landed(val damage: Int, val hitChance: Double) : HitResolution()

    data class Missed(val hitChance: Double) : HitResolution()

    data class NotComputable(val reason: String) : HitResolution()
}

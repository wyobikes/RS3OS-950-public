package com.opennxt.model.combat

import com.opennxt.content.ability.StatusEffects
import com.opennxt.model.entity.rendering.PlayerUpdates
import com.opennxt.model.entity.rendering.UpdateBlockType
import com.opennxt.model.entity.rendering.blocks.PlayerHitsBlock
import com.opennxt.model.world.PlayerCombatStats
import com.opennxt.model.world.WorldNpc
import com.opennxt.model.world.WorldPlayer
import com.opennxt.api.stat.Stat
import mu.KotlinLogging
import java.util.IdentityHashMap
import java.util.Random

object BossEncounters {
    private val logger = KotlinLogging.logger { }

    val specialsEnabled: Boolean
        get() = onUnless("opennxt.experiment.npcs.boss.special")

    val phasesEnabled: Boolean
        get() = onUnless("opennxt.experiment.npcs.boss.phases")

    val respawnEnabled: Boolean
        get() = onUnless("opennxt.experiment.npcs.boss.respawn")

    private fun onUnless(prop: String): Boolean =
        System.getProperty(prop)?.let { !it.equals("off", true) && !it.equals("false", true) } ?: true

    const val SPECIAL_FLOOR_PERCENT = 20

    const val MAX_SPLATS_PER_BLOCK = 4

    const val SEED = 0x424F5353L

    private var clock = 0
    private val nextSpecialAt = IdentityHashMap<WorldNpc, Int>()
    private val phaseIndex = IdentityHashMap<WorldNpc, Int>()
    private var random = Random(SEED)

    var ticks = 0L; private set
    var specialsFired = 0L; private set
    var specialsMissed = 0L; private set
    var specialsRefused = 0L; private set
    var specialDamage = 0L; private set
    var phaseFlips = 0L; private set
    var phaseResets = 0L; private set
    var killsBySpecial = 0L; private set
    var failures = 0L; private set

    var splatsDropped = 0L; private set

    var lastRefusal: String? = null
        private set

    fun phaseOf(npc: WorldNpc): Int = phaseIndex[npc] ?: 0

    fun nextSpecialTick(npc: WorldNpc): Int? = nextSpecialAt[npc]

    fun clock(): Int = clock

    fun liveBossesByTier(npcs: Iterable<WorldNpc>): Map<String, Int> {
        val m = LinkedHashMap<String, Int>()
        for (t in listOf("T1", "T2", "T3", "T4")) m[t] = 0
        for (n in npcs) {
            if (!n.alive) continue
            val boss = NpcBossData.bossFor(n.gameId) ?: continue
            m[boss.tier] = (m[boss.tier] ?: 0) + 1
        }
        return m
    }

    fun reset() {
        clock = 0
        nextSpecialAt.clear(); phaseIndex.clear()
        random = Random(SEED)
        ticks = 0; specialsFired = 0; specialsMissed = 0; specialsRefused = 0
        specialDamage = 0; phaseFlips = 0; phaseResets = 0; killsBySpecial = 0; failures = 0
        lastRefusal = null
    }

    fun forget(npc: WorldNpc) {
        nextSpecialAt.remove(npc)
        phaseIndex.remove(npc)
    }

    fun tick(players: List<WorldPlayer>) {
        clock++
        ticks++
        if (phaseIndex.isNotEmpty() || nextSpecialAt.isNotEmpty()) {
            val dead = nextSpecialAt.keys.filter { !it.alive || it.despawned } +
                phaseIndex.keys.filter { !it.alive || it.despawned }
            for (n in dead) {
                if (phaseIndex.remove(n) != null) phaseResets++
                nextSpecialAt.remove(n)
            }
        }
        if (!NpcBossData.loaded) return
        if (players.isEmpty()) return

        val engaged = LinkedHashMap<WorldNpc, WorldPlayer>()
        for (player in players) {
            val npc = PlayerCombat.targetOf(player) ?: continue
            if (!npc.alive || npc.despawned) continue
            if (!NpcBossData.isBoss(npc.gameId)) continue
            val held = engaged[npc]
            if (held == null) { engaged[npc] = player; continue }
            val top = npc.topDamageDealer()
            if (top != null && player.name.equals(top, true) && !held.name.equals(top, true)) engaged[npc] = player
        }

        for ((npc, player) in engaged) {
            try {
                val forced = if (phasesEnabled) advancePhase(npc, player) else false
                if (specialsEnabled) maybeSpecial(npc, player, forced)
            } catch (t: Throwable) {
                failures++
                logger.error(t) { "boss: encounter tick for npc ${npc.gameId} failed [tick $clock]" }
            }
        }
    }

    private fun advancePhase(npc: WorldNpc, player: WorldPlayer): Boolean {
        val thresholds = NpcBossData.phases(npc.gameId)
        if (thresholds.isEmpty()) return false
        val max = npc.lifepoints?.value ?: return false
        val current = npc.currentLifepoints ?: return false
        if (max <= 0) return false
        val crossed = thresholds.count { current < max * it }
        val was = phaseIndex[npc] ?: 0
        if (crossed <= was) return false
        phaseIndex[npc] = crossed
        phaseFlips += (crossed - was)
        val boss = NpcBossData.bossFor(npc.gameId)
        logger.info {
            "boss: ${boss?.name ?: "npc${npc.gameId}"} (${npc.gameId}) enters phase $crossed of " +
                "${thresholds.size} at $current/$max lp (threshold " +
                "${"%.4f".format(thresholds[crossed - 1])}, source ${boss?.phaseSource}) " +
                "while fighting ${player.name} [tick $clock]"
        }
        return true
    }

    private fun maybeSpecial(npc: WorldNpc, player: WorldPlayer, forced: Boolean) {
        val maxHit = NpcBossData.specialMaxHit(npc.gameId) ?: return
        val cadence = cadenceTicks(npc) ?: run {
            refuse("npc ${npc.gameId} attack speed is outside ${CombatFormulas.MAX_TICKS}")
            return
        }
        val due = nextSpecialAt[npc]
        if (due == null) {
            nextSpecialAt[npc] = clock + cadence
            return
        }
        if (!forced && clock < due) return

        if (player.currentLifepoints <= 0) { refuse("the target is dead"); return }
        val loc = player.entity.location
        if (loc.plane != npc.location.plane) { refuse("the target changed plane"); return }
        val reach = PlayerCombat.npcAttackRange(npc)
        if (PlayerCombat.footprintDistance(npc, loc.x, loc.y) > reach) {
            refuse("the target is out of the boss's reach ($reach tiles)")
            return
        }
        StatusEffects.npcStunRemaining(npc)?.let { refuse("the boss is stunned for $it more tick(s)"); return }

        nextSpecialAt[npc] = clock + cadence
        val animation = specialAnimation(npc)
        if (animation != null) npc.pendingUpdates.animate(animation)
        npc.pendingUpdates.facePlayer(player.entity.index)

        val combat = npc.combat
        val style = combat?.combatStyle
        val accuracy = if (style == null) null else combat.accuracyFor(style)
        if (accuracy == null) {
            refuse("npc ${npc.gameId} has no accuracy param for style $style")
            return
        }
        val loadout = PlayerCombatStats.loadoutOf(player)
        val chance = CombatFormulas.hitChance(
            accuracy, PlayerCombatStats.defenceRating(player.level(Stat.DEFENCE), loadout),
            if (style == null) com.opennxt.model.world.NpcRetaliation.PLAYER_AFFINITY
            else PlayerCombatStats.defenderAffinity(style, loadout))
        if (random.nextDouble() >= chance) {
            specialsMissed++
            logger.info {
                "boss: ${NpcBossData.bossFor(npc.gameId)?.name} special missed ${player.name} " +
                    "(chance ${"%.3f".format(chance)}, anim ${animation ?: "none"}) [tick $clock]"
            }
            return
        }

        val rolled = CombatFormulas.damageRoll(maxHit.value, random, SPECIAL_FLOOR_PERCENT)
        val damage = StatusEffects.scaleNpcHit(npc, player, rolled)
        if (damage <= 0) { refuse("the roll and the defender's buffs left nothing to apply"); return }
        val before = player.currentLifepoints
        val death = player.takeDamage(damage)
        val applied = if (death != null) before else before - player.currentLifepoints
        specialsFired++
        specialDamage += applied
        splat(player, applied, animation)
        val boss = NpcBossData.bossFor(npc.gameId)
        if (death != null) {
            killsBySpecial++
            PlayerCombat.disengage(player)
            logger.info {
                "boss: ${boss?.name} (${npc.gameId}) killed ${player.name} with its special for " +
                    "$applied (max ${maxHit.value}); respawned at ${death.respawnedAt} [tick $clock]"
            }
        } else {
            logger.info {
                "boss: ${boss?.name} (${npc.gameId}) special hit ${player.name} for $applied " +
                    "(max ${maxHit.value}, ${maxHit.provenance}), phase ${phaseOf(npc)}" +
                    (if (forced) ", triggered by phase change" else ", cadence $cadence ticks") +
                    ", ${player.currentLifepoints} lp left [tick $clock]"
            }
        }
    }

    private fun refuse(why: String) {
        specialsRefused++
        lastRefusal = why
    }

    fun cadenceTicks(npc: WorldNpc): Int? {
        val raw = npc.combat?.attackSpeed ?: return null
        val swings = NpcBossData.specialEverySwings
        if (swings <= 0) return null
        return when (val delay = CombatFormulas.tickDelay(raw)) {
            is AttackDelay.Ticks -> delay.ticks * swings
            is AttackDelay.OutOfRange -> null
        }
    }

    fun specialAnimation(npc: WorldNpc): Int? {
        val ordinary = PlayerCombat.npcAttackAnimation(npc)
        val params = WeaponAnimations.npcSequenceParams(npc.gameId)
        val other = params.entries.sortedBy { it.key }.firstOrNull { it.value != ordinary }?.value
        return other ?: ordinary
    }

    private fun splat(player: WorldPlayer, amount: Int, animId: Int?) {
        if (amount <= 0) return
        val type = PlayerCombat.splatType ?: return
        if (!PlayerCombat.faceEnabled) return
        val seqDuration = com.opennxt.model.definitions.SeqDefinitions.get(animId ?: 422)?.durationInTicks ?: 1
        val delay = (seqDuration / 2).coerceAtLeast(1) * 30
        val existing = player.entity.renderer.blocks[UpdateBlockType.HITS.playerPos] as? PlayerHitsBlock
        val hits = existing?.hits.orEmpty()
        if (hits.size >= MAX_SPLATS_PER_BLOCK) {
            splatsDropped++
            logger.warn {
                "boss: ${player.name}'s hit splats are full ($MAX_SPLATS_PER_BLOCK); $amount damage " +
                    "applied without a splat (dropped=$splatsDropped)"
            }
            return
        }
        runCatching {
            PlayerUpdates.hit(player.entity,
                PlayerHitsBlock(hits + PlayerHitsBlock.Hit(type, amount, delay), existing?.bars.orEmpty()))
        }.onFailure {
            failures++
            logger.warn(it) { "boss: splat of $amount on ${player.name} not queued; damage still applied" }
        }
    }

    fun counters(): Map<String, Long> = linkedMapOf(
        "tick" to clock.toLong(),
        "specials_fired" to specialsFired,
        "specials_missed" to specialsMissed,
        "specials_refused" to specialsRefused,
        "special_damage" to specialDamage,
        "kills_by_special" to killsBySpecial,
        "phase_flips" to phaseFlips,
        "phase_resets" to phaseResets,
        "armed_npcs" to nextSpecialAt.size.toLong(),
        "phased_npcs" to phaseIndex.size.toLong(),
        "failures" to failures,
        "splats_dropped" to splatsDropped
    )

    fun statusLine(): String =
        "boss: " + counters().entries.joinToString(", ") { "${it.key}=${it.value}" } +
            (lastRefusal?.let { "; last refusal: $it" } ?: "")
}

package com.opennxt.model.combat

import java.util.Random

object CombatFormulas {
    fun hitChance(attackerAccuracy: Int, defenderArmour: Int, defenderAffinity: Int): Double {
        val accuracy = attackerAccuracy.coerceAtLeast(0).toDouble()
        val affinity = defenderAffinity.coerceAtLeast(0).toDouble()
        if (accuracy == 0.0 || affinity == 0.0) return 0.0
        if (defenderArmour <= 0) return 1.0
        val raw = (affinity / 100.0) * (accuracy / defenderArmour.toDouble())
        return raw.coerceIn(0.0, 1.0)
    }

    const val PLAYER_AUTO_ATTACK_FLOOR_PERCENT: Int = 80

    fun damageRoll(maxHit: Int, random: Random, floorPercent: Int = 20): Int {
        if (maxHit <= 0) return 0
        val floor = maxHit * floorPercent.coerceIn(0, 100) / 100
        return floor + random.nextInt(maxHit - floor + 1)
    }

    val criticalEnabled: Boolean
        get() = System.getProperty("opennxt.combat.crit")?.trim()?.lowercase().let { it == "on" || it == "true" }

    const val CRITICAL_BASE_CHANCE_PERCENT = 10

    fun criticalBonusPercent(level: Int): Int {
        val l = level.coerceAtLeast(1)
        if (l < 20) return 10
        return 15 + 5 * ((l.coerceAtMost(90) - 20) / 10)
    }

    fun applyCritical(damage: Int, level: Int, random: Random): Int {
        if (random.nextInt(100) >= CRITICAL_BASE_CHANCE_PERCENT) return damage
        return damage * (100 + criticalBonusPercent(level)) / 100
    }

    fun tickDelay(attackSpeedTicks: Int): AttackDelay =
        if (attackSpeedTicks in MAX_TICKS) AttackDelay.Ticks(attackSpeedTicks)
        else AttackDelay.OutOfRange(attackSpeedTicks)

    val MAX_TICKS = 1..8

    const val TICK_MILLIS = 600

    fun npcVsNpcPreview(attackerGameId: Int, defenderGameId: Int): NpcVsNpcPreview? {
        val attacker = NpcCombat.load(attackerGameId) ?: return null
        val defender = NpcCombat.load(defenderGameId) ?: return null
        val style = attacker.combatStyle ?: return null
        val accuracy = attacker.accuracyFor(style) ?: return null
        val maxHitX10 = attacker.damageFor(style) ?: return null
        val armour = defender.armour ?: return null
        val affinity = defender.affinityFor(style) ?: return null
        val speed = when (val delay = tickDelay(attacker.attackSpeed ?: return null)) {
            is AttackDelay.Ticks -> delay
            is AttackDelay.OutOfRange -> return null
        }
        return NpcVsNpcPreview(
            attackerId = attacker.id,
            defenderId = defender.id,
            style = style,
            hitChance = hitChance(accuracy, armour, affinity),
            maxHitX10 = maxHitX10,
            attackSpeed = speed
        )
    }

    fun beats(style: CombatStyle): CombatStyle = when (style) {
        CombatStyle.MAGIC -> CombatStyle.MELEE
        CombatStyle.MELEE -> CombatStyle.RANGED
        CombatStyle.RANGED -> CombatStyle.MAGIC
    }

    fun affinitySlot(style: CombatStyle, weakTo: CombatStyle): Int = when (style) {
        weakTo -> 1
        beats(weakTo) -> 2
        else -> 3
    }
}

sealed class AttackDelay {
    data class Ticks(val ticks: Int) : AttackDelay() {
        val millis: Int get() = ticks * CombatFormulas.TICK_MILLIS
    }

    data class OutOfRange(val rawValue: Int) : AttackDelay()
}

data class NpcVsNpcPreview(
    val attackerId: Int,
    val defenderId: Int,
    val style: CombatStyle,
    val hitChance: Double,
    val maxHitX10: Int,
    val attackSpeed: AttackDelay.Ticks
)

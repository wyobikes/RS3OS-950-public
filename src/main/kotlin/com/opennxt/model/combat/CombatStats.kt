package com.opennxt.model.combat

import com.opennxt.api.stat.Stat

enum class CombatStyle {
    MELEE,
    RANGED,
    MAGIC;

    val beats: CombatStyle
        get() = when (this) {
            MELEE -> RANGED
            RANGED -> MAGIC
            MAGIC -> MELEE
        }

    val weakTo: CombatStyle
        get() = when (this) {
            MELEE -> MAGIC
            RANGED -> MELEE
            MAGIC -> RANGED
        }
}

object Lifepoints {
    const val PER_CONSTITUTION_LEVEL = 100

    fun forConstitutionLevel(constitutionLevel: Int, bonus: Int = 0): Int =
        (constitutionLevel.coerceAtLeast(1) * PER_CONSTITUTION_LEVEL + bonus).coerceAtLeast(1)

    const val CURRENT_LIFEPOINTS_VARP = 13537
}

val COMBAT_STATS: List<Stat> = listOf(
    Stat.ATTACK,
    Stat.STRENGTH,
    Stat.DEFENCE,
    Stat.CONSTITUTION,
    Stat.RANGED,
    Stat.MAGIC,
    Stat.PRAYER,
    Stat.SUMMONING
)

class CombatStats(
    maximumLifepoints: Int,
    var lifepointBonus: Int = 0
) {
    private val baseLevels = IntArray(Stat.values().size) { 1 }
    private val currentLevels = IntArray(Stat.values().size) { 1 }

    var maximumLifepoints: Int = maximumLifepoints.coerceAtLeast(1)
        private set

    var currentLifepoints: Int = this.maximumLifepoints
        private set

    val isDead: Boolean get() = currentLifepoints <= 0

    val lifepointPercent: Int
        get() = if (maximumLifepoints <= 0) 0 else (currentLifepoints * 100) / maximumLifepoints

    fun baseLevel(stat: Stat): Int = baseLevels[stat.id]

    fun currentLevel(stat: Stat): Int = currentLevels[stat.id]

    fun setBaseLevel(stat: Stat, level: Int) {
        val clamped = level.coerceAtLeast(1)
        val delta = clamped - baseLevels[stat.id]
        baseLevels[stat.id] = clamped
        currentLevels[stat.id] = (currentLevels[stat.id] + delta).coerceAtLeast(0)
    }

    fun setLevel(stat: Stat, level: Int) {
        val clamped = level.coerceAtLeast(1)
        baseLevels[stat.id] = clamped
        currentLevels[stat.id] = clamped
    }

    fun boost(stat: Stat, amount: Int) {
        if (amount <= 0) return
        currentLevels[stat.id] = currentLevels[stat.id] + amount
    }

    fun drain(stat: Stat, amount: Int): Int {
        if (amount <= 0) return 0
        val before = currentLevels[stat.id]
        currentLevels[stat.id] = (before - amount).coerceAtLeast(0)
        return before - currentLevels[stat.id]
    }

    fun restore(stat: Stat) {
        currentLevels[stat.id] = baseLevels[stat.id]
    }

    fun restoreAllLevels() {
        for (stat in Stat.values()) currentLevels[stat.id] = baseLevels[stat.id]
    }

    fun modifier(stat: Stat): Int = currentLevels[stat.id] - baseLevels[stat.id]

    fun recalculateMaximum() {
        setMaximumLifepoints(Lifepoints.forConstitutionLevel(baseLevel(Stat.CONSTITUTION), lifepointBonus))
    }

    fun setMaximumLifepoints(maximum: Int) {
        maximumLifepoints = maximum.coerceAtLeast(1)
        if (currentLifepoints > maximumLifepoints) currentLifepoints = maximumLifepoints
    }

    fun damage(amount: Int): Int {
        if (amount <= 0) return 0
        val applied = minOf(amount, currentLifepoints)
        currentLifepoints -= applied
        return applied
    }

    fun heal(amount: Int): Int {
        if (amount <= 0) return 0
        val applied = minOf(amount, maximumLifepoints - currentLifepoints)
        if (applied <= 0) return 0
        currentLifepoints += applied
        return applied
    }

    fun setLifepoints(value: Int) {
        currentLifepoints = value.coerceIn(0, maximumLifepoints)
    }

    fun healToFull() {
        currentLifepoints = maximumLifepoints
    }

    override fun toString() =
        "CombatStats(lp=$currentLifepoints/$maximumLifepoints, " +
            COMBAT_STATS.joinToString(", ") { "${it.name.take(3)}=${currentLevel(it)}/${baseLevel(it)}" } + ")"

    companion object {
        const val STARTING_CONSTITUTION_LEVEL = 10

        fun newPlayer(): CombatStats {
            val stats = CombatStats(Lifepoints.forConstitutionLevel(STARTING_CONSTITUTION_LEVEL))
            COMBAT_STATS.forEach { stats.setLevel(it, 1) }
            stats.setLevel(Stat.CONSTITUTION, STARTING_CONSTITUTION_LEVEL)
            stats.recalculateMaximum()
            stats.healToFull()
            return stats
        }

        fun forConstitution(level: Int, bonus: Int = 0): CombatStats {
            val stats = CombatStats(1, bonus)
            stats.setLevel(Stat.CONSTITUTION, level)
            stats.recalculateMaximum()
            stats.healToFull()
            return stats
        }
    }
}

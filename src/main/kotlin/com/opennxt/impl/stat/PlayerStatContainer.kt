package com.opennxt.impl.stat

import com.opennxt.api.stat.ExperienceSource
import com.opennxt.api.stat.Stat
import com.opennxt.api.stat.StatContainer
import com.opennxt.api.stat.StatData
import com.opennxt.model.entity.BasePlayer
import com.opennxt.net.game.serverprot.UpdateStat
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet
import mu.KotlinLogging
import java.util.*
import kotlin.math.min

class PlayerStatContainer(val player: BasePlayer, initialXp: Map<Stat, Double>? = null) : StatContainer {
    private val logger = KotlinLogging.logger {}
    private val stats = EnumMap<Stat, PlayerStatData>(Stat::class.java)
    private val dirty = ObjectOpenHashSet<Stat>()

    private var fanfareTick: Long? = null

    override fun init() {
        for (stat in Stat.values()) {
            refresh(stat)
        }
    }

    override fun markDirty() {
        dirty.addAll(Stat.values())
    }

    override fun isDirty(): Boolean = dirty.isNotEmpty()

    override fun clean() {
        for (stat in dirty) {
            refresh(stat)
        }

        dirty.clear()
    }

    init {
        Stat.values().forEach { stat ->
            val data = if (initialXp == null) {
                val d = PlayerStatData(stat, 0.0, 1, 1)
                if (stat == Stat.CONSTITUTION) {
                    d.experience = 1154.0
                    d.actualLevel = 10
                    d.boostedLevel = 10
                }
                d
            } else {
                val xp = initialXp[stat]
                    ?: throw IllegalArgumentException(
                        "initial xp map is missing $stat - a PlayerSave always carries all ${Stat.values().size} stats"
                    )
                val d = PlayerStatData(stat, xp)
                val level = d.calculateLevel()
                d.actualLevel = level
                d.boostedLevel = level
                d
            }

            stats[stat] = data
        }
    }

    override fun get(stat: Stat): StatData = stats.getValue(stat)

    override fun set(stat: Stat, data: StatData) {
        if (stat != data.stat) {
            throw IllegalArgumentException("'stat' and 'data#stat' do not match ($stat vs ${data.stat})")
        }

        if (data !is PlayerStatData) {
            stats[stat] = PlayerStatData(
                stat,
                data.experience,
                data.actualLevel,
                data.boostedLevel
            )
            return
        }

        stats[stat] = data
    }

    override fun addExperience(stat: Stat, amount: Double, source: ExperienceSource): Int {
        require(amount >= 0.0) { "xp award must be non-negative; got $amount for $stat" }
        val data = stats.getValue(stat)

        var actualAmount = amount * source.boostFactor

        val bonusExp = data.bonusExp
        if (bonusExp > 0) {
            val toAdd = min(bonusExp, actualAmount)
            data.bonusExp -= toAdd
            actualAmount += toAdd
        }

        val levelsGained = data.addExperience(actualAmount)

        refresh(data)

        if (levelsGained > 0) {
            levelUps++
            fanfareTick = LevelUpFanfare.play(player, stat, fanfareTick)
            if (levelUpMessageEnabled) {
                val line = levelUpLine(stat, data.actualLevel)
                runCatching { player.client.write(com.opennxt.net.game.serverprot.MessageGame(0, line)) }
                    .onFailure { logger.warn(it) { "could not send the level-up line to ${player.name}" } }
                logger.info { "${player.name}: $line (+$levelsGained)" }
            }
        }

        return levelsGained
    }

    fun refresh(stat: Stat) {
        refresh(stats.getValue(stat))
    }

    fun refresh(data: StatData) {
        if (!sendStatsEnabled) {
            suppressed++
            return
        }
        val packet =
            UpdateStat(stat = data.stat.id, level = data.boostedLevel, experience = data.experience.toInt())

        player.client.write(packet)
    }

    companion object {
        val sendStatsEnabled: Boolean
            get() = System.getProperty("opennxt.experiment.sendStats") == "true"

        private var suppressed = 0

        val levelUpMessageEnabled: Boolean
            get() = System.getProperty("opennxt.stats.levelUpMessage") != "off"

        @Volatile
        private var levelUps = 0

        fun levelUpCount(): Int = levelUps

        fun levelUpLine(stat: Stat, level: Int): String {
            val name = runCatching { stat.display }.getOrNull()?.takeIf { it.isNotBlank() && it != "null" }
                ?: stat.name.lowercase().replaceFirstChar { it.uppercase() }
            val article = if (name.first().lowercaseChar() in "aeiou") "an" else "a"
            return "You've just advanced $article $name level! You have reached level $level."
        }

        fun suppressedRefreshes(): Int = suppressed

        fun resetSuppressedRefreshes() {
            suppressed = 0
        }
    }

    override fun boostStat(stat: Stat, boostBy: Int) {
        TODO("Not yet implemented")
    }

    override fun hasLevel(stat: Stat, level: Int, boostAble: Boolean): Boolean {
        return getLevel(stat, boostAble) >= level
    }

    override fun getLevel(stat: Stat, boosted: Boolean): Int {
        val data = stats.getValue(stat)
        return if (boosted) {
            data.boostedLevel
        } else {
            data.actualLevel
        }
    }
}

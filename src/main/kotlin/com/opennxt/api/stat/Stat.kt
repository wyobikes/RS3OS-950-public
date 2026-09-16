package com.opennxt.api.stat

import com.opennxt.resources.FilesystemResources
import com.opennxt.resources.config.enums.EnumDefinition
import com.opennxt.resources.defaults.stats.StatDefaults
import com.opennxt.resources.defaults.stats.StatDefinition
import com.opennxt.resources.defaults.stats.StatExperienceTable
import mu.KotlinLogging

enum class Stat(val id: Int) {
    ATTACK(0),
    DEFENCE(1),
    STRENGTH(2),
    CONSTITUTION(3),
    RANGED(4),
    PRAYER(5),
    MAGIC(6),
    COOKING(7),
    WOODCUTTING(8),
    FLETCHING(9),
    FISHING(10),
    FIREMAKING(11),
    CRAFTING(12),
    SMITHING(13),
    MINING(14),
    HERBLORE(15),
    AGILITY(16),
    THIEVING(17),
    SLAYER(18),
    FARMING(19),
    RUNECRAFTING(20),
    HUNTER(21),
    CONSTRUCTION(22),
    SUMMONING(23),
    DUNGEONEERING(24),
    DIVINATION(25),
    INVENTION(26),
    ARCHAEOLOGY(27),

    NECROMANCY(28),
    ;

    lateinit var def: StatDefinition
    lateinit var table: StatExperienceTable
    lateinit var display: String

    val loaded: Boolean
        get() = this::def.isInitialized && this::table.isInitialized

    companion object {
        private val logger = KotlinLogging.logger { }
        private val VALUES = values()

        fun reload() {
            val defaults = FilesystemResources.instance.defaults.get<StatDefaults>()

            val names = FilesystemResources.instance.get<EnumDefinition>(680)
            if (names == null) {
                logger.warn {
                    "Enum 680 (skill display names) could not be loaded - cache index 17 " +
                        "is probably absent. Stats will work; their names will read 'skill<id>'."
                }
            }

            defaults.stats.forEach { def ->
                if (def.id !in VALUES.indices) {
                    logger.warn {
                        "Cache declares skill id ${def.id}, but this build knows only " +
                                "${VALUES.size} (0..${VALUES.size - 1}). Skipping it."
                    }
                    return@forEach
                }
                val enum = VALUES[def.id]
                enum.def = def
                enum.table = def.table
                enum.display = if (names == null) "skill${def.id}"
                    else ((names.values[def.id] as? String) ?: names.defaultString) ?: "null"
            }
        }
    }
}

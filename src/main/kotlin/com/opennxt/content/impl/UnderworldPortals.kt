package com.opennxt.content.impl

import com.opennxt.model.map.LocInteraction
import com.opennxt.model.world.TileLocation
import com.opennxt.model.world.WorldPlayer
import com.opennxt.resources.sqlite.RsDatabase
import mu.KotlinLogging

object UnderworldPortals {
    private val logger = KotlinLogging.logger { }

    val enabled: Boolean get() = System.getProperty("opennxt.content.underworldportal") != "off"

    const val DRAYNOR_PARENT = 127139

    val DRAYNOR_CHILDREN = listOf(127140, 127141)

    const val OVERWORLD = 127269

    val TO_UM = TileLocation(1023, 1761, 1)

    val TO_DRAYNOR = TileLocation(3104, 3311, 0)

    const val DELAY_TICKS = 1

    const val OPTION_ENTER = 1

    const val OPTION_EXAMINE = 6

    @Volatile
    var accepted = 0
        private set

    @Volatile
    var refused = 0
        private set

    @Volatile
    var examined = 0
        private set

    internal fun resetCounters() { accepted = 0; refused = 0; examined = 0 }

    fun isPortal(locId: Int): Boolean = locId == DRAYNOR_PARENT || locId == OVERWORLD

    fun destinationFor(locId: Int): TileLocation? = when (locId) {
        DRAYNOR_PARENT -> TO_UM
        OVERWORLD -> TO_DRAYNOR
        else -> null
    }

    fun handleLocClick(player: WorldPlayer, locId: Int, option: Int, x: Int, y: Int, plane: Int): Boolean {
        if (!enabled || !isPortal(locId)) return false
        val dest = destinationFor(locId) ?: return false
        val tag = "underworld portal: ${player.name} OPLOC$option loc $locId at ($x,$y,plane $plane)"

        if (RsDatabase.available && LocInteraction.placementOf(locId, x, y, plane) == null) {
            refused++
            logger.warn { "$tag: loc not found in map_loc; ignored" }
            return true
        }
        val at = player.entity.location
        if (!LocInteraction.adjacentToFootprint(at.x, at.y, locId, x, y, plane)) {
            refused++
            logger.info { "$tag: player at (${at.x},${at.y},plane ${at.plane}) is not adjacent; ignored" }
            return true
        }
        when (option) {
            OPTION_ENTER -> {
                val armed = Teleports.schedule(player, dest, DELAY_TICKS, "loc $locId 'Enter'")
                if (armed) accepted++ else refused++
                logger.info {
                    "$tag 'Enter' -> (${dest.x},${dest.y},plane ${dest.plane}) " +
                        (if (armed) "in $DELAY_TICKS tick(s)" else "not scheduled (teleport pending or disabled)")
                }
            }
            OPTION_EXAMINE -> {
                examined++
                logger.info { "$tag: Examine (client-side)" }
            }
            else -> {
                refused++
                logger.info { "$tag: option $option is not supported" }
            }
        }
        return true
    }
}

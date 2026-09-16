package com.opennxt.content.impl

import com.opennxt.model.world.TileLocation
import mu.KotlinLogging

object TeleportRegistry {
    private val logger = KotlinLogging.logger { }

    private val manualDestinations = mapOf(
        "Climb-up 3204_3207_0" to TileLocation(3205, 3209, 0),
        "Climb-down 3205_3209_0" to TileLocation(3205, 3206, 0)
    )

    fun getDestination(action: String, locX: Int, locY: Int, plane: Int): TileLocation? {
        val key = "$action ${locX}_${locY}_$plane"
        val destination = manualDestinations[key]
        if (destination != null) {
            logger.info { "TeleportRegistry: routed $key to $destination" }
        }
        return destination
    }
}

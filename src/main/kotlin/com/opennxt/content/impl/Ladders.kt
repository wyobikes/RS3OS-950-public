package com.opennxt.content.impl

import com.opennxt.content.ContentRegistry
import com.opennxt.content.LocContext
import com.opennxt.model.map.CollisionMap
import com.opennxt.model.world.TileLocation
import mu.KotlinLogging

object Ladders {
    private val logger = KotlinLogging.logger { }

    const val CLIMB_UP = "Climb-up"
    const val CLIMB_DOWN = "Climb-down"
    const val CLIMB = "Climb"

    const val MIN_PLANE = 0
    const val MAX_PLANE = 3

    private fun destinationWalkable(x: Int, y: Int, plane: Int): Boolean =
        !CollisionMap.available || CollisionMap.walkable(x, y, plane)

    private fun climb(ctx: LocContext, direction: Int): Any? {
        val action = if (direction > 0) CLIMB_UP else CLIMB_DOWN
        val customDest = TeleportRegistry.getDestination(action, ctx.x, ctx.z, ctx.plane)
        if (customDest != null) {
            ctx.player.location = customDest
            return if (direction > 0) "climbed-up" else "climbed-down"
        }

        val from = ctx.player.location
        val target = from.plane + direction
        if (target > MAX_PLANE) return "at-top"
        if (target < MIN_PLANE) return "at-bottom"
        
        var destX = ctx.x
        var destY = ctx.z
        if (!destinationWalkable(destX, destY, target)) {
            val dx = intArrayOf(1, -1, 0, 0, 1, -1, 1, -1)
            val dy = intArrayOf(0, 0, 1, -1, 1, 1, -1, -1)
            var found = false
            for (i in 0 until 8) {
                if (destinationWalkable(ctx.x + dx[i], ctx.z + dy[i], target)) {
                    destX = ctx.x + dx[i]
                    destY = ctx.z + dy[i]
                    found = true
                    break
                }
            }
            if (!found) return "destination-blocked"
        }
        ctx.player.location = TileLocation(destX, destY, target)
        return if (direction > 0) "climbed-up" else "climbed-down"
    }

    fun onClimbUp(ctx: LocContext): Any? = climb(ctx, +1)

    fun onClimbDown(ctx: LocContext): Any? = climb(ctx, -1)

    fun onClimb(ctx: LocContext): Any? {
        val customDest = TeleportRegistry.getDestination(CLIMB, ctx.x, ctx.z, ctx.plane)
        if (customDest != null) {
            ctx.player.location = customDest
            return "climbed"
        }

        val from = ctx.player.location
        if (from.plane + 1 <= MAX_PLANE && destinationWalkable(from.x, from.y, from.plane + 1)) {
            return climb(ctx, +1)
        }
        if (from.plane - 1 >= MIN_PLANE && destinationWalkable(from.x, from.y, from.plane - 1)) {
            return climb(ctx, -1)
        }
        return "nowhere-to-climb"
    }

    fun install(): Triple<Int, Int, Int> {
        val up = ContentRegistry.onLocAction(CLIMB_UP, ::onClimbUp)
        val down = ContentRegistry.onLocAction(CLIMB_DOWN, ::onClimbDown)
        val climb = ContentRegistry.onLocAction(CLIMB, ::onClimb)
        logger.info { "ladders: bound Climb-up across $up locs, Climb-down across $down, Climb across $climb" }
        return Triple(up, down, climb)
    }
}

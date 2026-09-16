package com.opennxt.content.impl

import com.opennxt.content.ContentRegistry
import com.opennxt.content.LocContext
import com.opennxt.model.map.CollisionMap
import com.opennxt.model.world.TileLocation
import mu.KotlinLogging

object Stairs {
    private val logger = KotlinLogging.logger { }

    const val WALK_UP = "Walk-up"
    const val WALK_DOWN = "Walk-down"

    const val MIN_PLANE = 0
    const val MAX_PLANE = 3

    private fun destinationWalkable(x: Int, y: Int, plane: Int): Boolean =
        !CollisionMap.available || CollisionMap.walkable(x, y, plane)

    private fun walk(ctx: LocContext, direction: Int): Any? {
        val action = if (direction > 0) WALK_UP else WALK_DOWN
        val customDest = TeleportRegistry.getDestination(action, ctx.x, ctx.z, ctx.plane)
        if (customDest != null) {
            ctx.player.location = customDest
            return if (direction > 0) "walked-up" else "walked-down"
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
        return if (direction > 0) "walked-up" else "walked-down"
    }

    fun onWalkUp(ctx: LocContext): Any? = walk(ctx, +1)

    fun onWalkDown(ctx: LocContext): Any? = walk(ctx, -1)

    fun install(): Pair<Int, Int> {
        val up = ContentRegistry.onLocAction(WALK_UP, ::onWalkUp)
        val down = ContentRegistry.onLocAction(WALK_DOWN, ::onWalkDown)
        logger.info { "stairs: bound Walk-up across $up locs, Walk-down across $down" }
        return up to down
    }
}

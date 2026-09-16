package com.opennxt.content.impl

import com.opennxt.content.ContentRegistry
import com.opennxt.content.LocContext
import com.opennxt.model.map.CollisionMap
import com.opennxt.model.map.LocInteraction
import com.opennxt.model.world.TileLocation
import mu.KotlinLogging

object Obstacles {
    private val logger = KotlinLogging.logger { }

    const val CLIMB_OVER_HYPHEN = "Climb-over"
    const val CLIMB_OVER_SPACED = "Climb over"

    val CROSSING_ACTIONS: List<String> = listOf(
        "Cross", "Jump", "Squeeze-through", "Swing-on", "Grapple", "Climb-into", "Vault",
    )

    const val CLIMB_ANIMATION = 839

    @Volatile
    var crossedListener: ((LocContext, TileLocation) -> Unit)? = null

    data class Crossing(val axisX: Boolean, val lo: Int, val hi: Int) {
        fun mirror(p: Int): Int = lo + hi - p
    }

    fun crossingOf(p: LocInteraction.Placed, px: Int, pz: Int): Crossing? {
        when (p.edgeMask) {
            CollisionMap.WALL_W -> return Crossing(true, p.originX - 1, p.originX)
            CollisionMap.WALL_E -> return Crossing(true, p.originX, p.originX + 1)
            CollisionMap.WALL_N -> return Crossing(false, p.originZ, p.originZ + 1)
            CollisionMap.WALL_S -> return Crossing(false, p.originZ - 1, p.originZ)
        }
        if (p.dz > p.dx) return Crossing(false, p.z0, p.z1)
        if (p.dx > p.dz) return Crossing(true, p.x0, p.x1)

        val offX = if (px < p.x0) px - p.x0 else if (px > p.x1) px - p.x1 else 0
        val offZ = if (pz < p.z0) pz - p.z0 else if (pz > p.z1) pz - p.z1 else 0
        if (offX != 0 && offZ == 0) return Crossing(true, p.x0, p.x1)
        if (offZ != 0 && offX == 0) return Crossing(false, p.z0, p.z1)
        return null
    }

    fun destinationFor(p: LocInteraction.Placed, px: Int, pz: Int, plane: Int): TileLocation? {
        val cross = crossingOf(p, px, pz) ?: return null
        return if (cross.axisX) TileLocation(cross.mirror(px), pz, plane)
        else TileLocation(px, cross.mirror(pz), plane)
    }

    fun onClimbOver(ctx: LocContext): Any? {
        val player = ctx.player
        val from = player.location

        val placed = LocInteraction.placementOf(ctx.locId, ctx.x, ctx.z, ctx.plane)
            ?: return "no-placement"

        val dest = destinationFor(placed, from.x, from.y, from.plane)
            ?: return "crossing-direction-unknown"

        if (dest.x == from.x && dest.y == from.y) return "already-across"

        if (CollisionMap.available && !CollisionMap.walkable(dest.x, dest.y, dest.plane)) {
            logger.info {
                "obstacles: refused '${ctx.action}' on loc ${ctx.locId} at (${ctx.x},${ctx.z},${ctx.plane}): " +
                    "destination (${dest.x},${dest.y}) is blocked"
            }
            return "destination-blocked"
        }

        player.location = dest
        player.onAnimate?.invoke(CLIMB_ANIMATION)
        crossedListener?.let { l -> runCatching { l(ctx, dest) } }
        return "climbed-over"
    }

    fun install(): Int {
        var count = ContentRegistry.onLocAction(CLIMB_OVER_HYPHEN, ::onClimbOver)
        count += ContentRegistry.onLocAction(CLIMB_OVER_SPACED, ::onClimbOver)
        val climbOver = count

        val bound = LinkedHashMap<String, Int>()
        val absent = ArrayList<String>()
        for (action in CROSSING_ACTIONS) {
            try {
                val n = ContentRegistry.onLocAction(action, ::onClimbOver)
                bound[action] = n
                count += n
            } catch (e: IllegalArgumentException) {
                absent += action
            }
        }
        logger.info { "obstacles: bound Climb over across $climbOver locs" }
        if (bound.isNotEmpty()) {
            logger.info {
                "obstacles: bound ${bound.size} further crossing verb(s) across " +
                    "${bound.values.sum()} locs: " + bound.entries.joinToString(", ") { "'${it.key}' ${it.value}" }
            }
        }
        if (absent.isNotEmpty()) {
            logger.info {
                "obstacles: skipped ${absent.size} crossing verb(s) with no loc: ${absent.joinToString(", ")}"
            }
        }
        return count
    }
}

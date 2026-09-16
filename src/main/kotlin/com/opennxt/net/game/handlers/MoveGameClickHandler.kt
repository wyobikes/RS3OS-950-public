package com.opennxt.net.game.handlers

import com.opennxt.model.entity.PlayerEntity
import com.opennxt.model.world.WorldPlayer
import com.opennxt.net.game.clientprot.MoveGameClick
import com.opennxt.net.game.pipeline.GamePacketHandler
import mu.KotlinLogging

object MoveGameClickHandler : GamePacketHandler<WorldPlayer, MoveGameClick> {
    private val logger = KotlinLogging.logger { }

    private const val MAX_STEPS = 64

    private fun step(from: Int, to: Int): Int = if (to > from) 1 else if (to < from) -1 else 0

    sealed class LocWalk {
        data class AlreadyInRange(val x: Int, val z: Int) : LocWalk()

        data class Walked(val steps: Int, val x: Int, val z: Int) : LocWalk()

        data class Approached(val steps: Int, val x: Int, val z: Int, val candidates: Int) : LocWalk()

        data class Unreachable(val candidates: Int, val fallbackSteps: Int) : LocWalk()

        data class NoPlacement(val fallbackSteps: Int) : LocWalk()

        val queued: Int
            get() = when (this) {
                is AlreadyInRange -> 0
                is Walked -> steps
                is Approached -> steps
                is Unreachable -> fallbackSteps
                is NoPlacement -> fallbackSteps
            }

        val refused: Boolean get() = this is NoPlacement && fallbackSteps == 0
    }

    fun walkToLoc(context: WorldPlayer, clickedX: Int, clickedY: Int, locId: Int?, tag: String): LocWalk =
        walkToLoc(context.entity, clickedX, clickedY, locId, tag)

    fun walkToLoc(context: WorldPlayer, clickedX: Int, clickedY: Int, tag: String): LocWalk =
        walkToLoc(context.entity, clickedX, clickedY, null, tag)

    fun walkToLoc(entity: PlayerEntity, clickedX: Int, clickedY: Int, tag: String): LocWalk =
        walkToLoc(entity, clickedX, clickedY, null, tag)

    fun walkToLoc(entity: PlayerEntity, clickedX: Int, clickedY: Int, locId: Int?, tag: String): LocWalk =
        walkToLocKeepingFace(entity, clickedX, clickedY, locId, tag).also {
            if (entity.movement.hasSteps) releaseFace(entity, tag)
            if (entity.movement.hasSteps) releaseFight(entity, tag)
        }

    private fun walkToLocKeepingFace(entity: PlayerEntity, clickedX: Int, clickedY: Int, locId: Int?, tag: String): LocWalk {
        val plane = entity.location.plane
        val from = entity.location

        val locPlane = if (locId != null)
            (com.opennxt.model.map.LocInteraction.placementPlane(locId, clickedX, clickedY, plane) ?: plane)
        else plane
        val placed = com.opennxt.model.map.LocInteraction
            .placementsCovering(clickedX, clickedY, locPlane)
            .firstOrNull { locId == null || it.locId == locId }
        if (placed == null) {
            val n = walk(entity, clickedX, clickedY, tag)
            logger.info {
                "$tag ($clickedX,$clickedY): no loc covers that tile; walked to it, $n step(s)"
            }
            return LocWalk.NoPlacement(n)
        }

        if (com.opennxt.model.map.LocInteraction.inRange(placed, plane, from.x, from.y)) {
            entity.movement.reset()
            logger.info {
                "$tag ($clickedX,$clickedY): already in range at (${from.x},${from.y}) - " +
                    "loc ${placed.locId} occupies ${placed.dx}x${placed.dz} at " +
                    "(${placed.x0}..${placed.x1},${placed.z0}..${placed.z1})" +
                    (if (placed.isWall) " as a wall on its 0x%02x edge".format(placed.edgeMask) else "")
            }
            return LocWalk.AlreadyInRange(from.x, from.y)
        }

        val candidates = com.opennxt.model.map.LocInteraction
            .standingTiles(placed, plane, from.x, from.y)
        entity.movement.reset()
        for (c in candidates) {
            if (entity.movement.walkTo(c[0], c[1], nearest = false)) {
                val steps = entity.movement.remainingSteps
                logger.info {
                    "$tag ($clickedX,$clickedY): loc ${placed.locId} occupies ${placed.dx}x${placed.dz} " +
                        "at (${placed.x0}..${placed.x1},${placed.z0}..${placed.z1}); walked from " +
                        "(${from.x},${from.y}) to (${c[0]},${c[1]}) - $steps step(s), " +
                        "${candidates.size} standing tile(s) offered"
                }
                return LocWalk.Walked(steps, c[0], c[1])
            }
        }

        val nearest = candidates.firstOrNull()
        if (nearest != null) {
            entity.movement.reset()
            if (entity.movement.walkTo(nearest[0], nearest[1], nearest = true)) {
                val steps = entity.movement.remainingSteps
                logger.info {
                    "$tag ($clickedX,$clickedY): no exact route to loc ${placed.locId} from (${from.x},${from.y}); " +
                        "approaching (${nearest[0]},${nearest[1]}), $steps step(s)"
                }
                return LocWalk.Approached(steps, nearest[0], nearest[1], candidates.size)
            }
        }

        val n = walk(entity, clickedX, clickedY, tag)
        logger.info {
            "$tag ($clickedX,$clickedY): loc ${placed.locId} is unreachable from (${from.x},${from.y})" +
                (if (n > 0) "; walked $n step(s) toward it" else "; staying put")
        }
        return LocWalk.Unreachable(candidates.size, n)
    }

    override fun handle(context: WorldPlayer, packet: MoveGameClick) {
        val swap = System.getProperty("opennxt.compat.gameclick.swapxy") == "true"
        val clickX = if (swap) packet.y else packet.x
        val clickY = if (swap) packet.x else packet.y
        val queued = clickWalk(context.entity, context.contentPlayer, clickX, clickY, "flags=0x${packet.flags.toString(16)} ")

        if (queued > 0) com.opennxt.content.impl.MapFlag.set(context, clickX, clickY)
        else com.opennxt.content.impl.MapFlag.clear(context)
    }

    fun clickWalk(
        entity: PlayerEntity,
        player: com.opennxt.content.ContentPlayer,
        clickX: Int,
        clickY: Int,
        detail: String = ""
    ): Int {
        if (com.opennxt.content.ActionLock.refuse(player, "MOVE_GAMECLICK to ($clickX,$clickY)")) return 0
        return walk(entity, clickX, clickY, "MOVE_GAMECLICK", detail)
    }

    fun walk(context: WorldPlayer, targetX: Int, targetY: Int, tag: String, detail: String = "", search: Int? = null, endsFight: Boolean = true): Int =
        walk(context.entity, targetX, targetY, tag, detail, search, endsFight)

    fun walk(entity: PlayerEntity, targetX: Int, targetY: Int, tag: String, detail: String = "", search: Int? = null, endsFight: Boolean = true): Int =
        walkKeepingFace(entity, targetX, targetY, tag, detail, search).also { queued ->
            if (queued > 0) releaseFace(entity, tag)
            if (queued > 0 && endsFight) releaseFight(entity, tag)
        }

    private fun releaseFace(entity: PlayerEntity, tag: String) {
        if (com.opennxt.model.entity.rendering.PlayerUpdates.releaseInteractionFace(entity)) {
            logger.info { "$tag: walking cleared the face-entity lock" }
        }
    }

    private fun releaseFight(entity: PlayerEntity, tag: String) {
        if (com.opennxt.model.combat.PlayerCombat.disengageForWalk(entity)) {
            logger.info { "$tag: walking ended combat" }
        }
    }

    private fun walkKeepingFace(entity: PlayerEntity, targetX: Int, targetY: Int, tag: String, detail: String = "", search: Int? = null): Int {
        entity.movement.reset()

        val from = entity.location

        if (com.opennxt.model.map.CollisionMap.available) {
            if (if (search == null) entity.movement.walkTo(targetX, targetY) else entity.movement.walkTo(targetX, targetY, nearest = true, search = search)) {
                logger.info {
                    "$tag from (${from.x},${from.y}) to (${targetX},${targetY}) " +
                        "-> PATHFOUND ${entity.movement.remainingSteps} step(s)"
                }
                return entity.movement.remainingSteps
            }
            val dxTotal = targetX - from.x
            val dyTotal = targetY - from.y
            val dist = maxOf(Math.abs(dxTotal), Math.abs(dyTotal))
            if (dist > 1) {
                val probes = minOf(8, dist - 1)
                for (i in 1..probes) {
                    val frac = (probes - i + 1).toDouble() / (probes + 1)
                    val tx = from.x + Math.round(dxTotal * frac).toInt()
                    val ty = from.y + Math.round(dyTotal * frac).toInt()
                    if (tx == from.x && ty == from.y) continue
                    if (entity.movement.walkTo(tx, ty)) {
                        logger.info {
                            "$tag from (${from.x},${from.y}) to (${targetX},${targetY}) " +
                                "unreachable -> walking to nearest point ($tx,$ty), " +
                                "${entity.movement.remainingSteps} step(s)"
                        }
                        return entity.movement.remainingSteps
                    }
                }
            }

            logger.info {
                "$tag from (${from.x},${from.y}) to (${targetX},${targetY}) " +
                    "-> no route; trying a straight line"
            }
        }

        var cx = from.x
        var cy = from.y
        var queued = 0

        while ((cx != targetX || cy != targetY) && queued < MAX_STEPS) {
            val nx = cx + step(cx, targetX)
            val ny = cy + step(cy, targetY)
            if (!entity.movement.addStep(nx, ny)) break
            cx = nx; cy = ny; queued++
        }

        logger.info {
            "$tag from (${from.x},${from.y}) to (${targetX},${targetY}) " +
                "$detail-> queued $queued step(s)" +
                if (queued == 0) " (already there or blocked)" else ""
        }

        return queued
    }
}

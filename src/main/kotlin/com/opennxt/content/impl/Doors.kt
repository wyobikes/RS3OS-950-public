package com.opennxt.content.impl

import com.opennxt.content.ContentRegistry
import com.opennxt.content.LocContext
import com.opennxt.content.SqliteDefinitions
import com.opennxt.model.map.CollisionMap
import com.opennxt.model.map.LocClipping
import com.opennxt.resources.sqlite.LocDefinition
import com.opennxt.resources.sqlite.RsDatabase
import mu.KotlinLogging

object Doors {
    private val logger = KotlinLogging.logger { }

    const val OPEN = "Open"
    const val CLOSE = "Close"

    val WALL_TYPES = setOf(0, 1, 2, 3, 9)

    fun clipType(def: LocDefinition): Int = LocClipping.clipType(def, op74 = false)

    fun clipTypeFor(locId: Int, def: LocDefinition): Int = LocClipping.clipTypeFor(locId, def)

    fun blocksWhenShutFor(locId: Int, def: LocDefinition): Boolean = clipTypeFor(locId, def) != 0

    fun blocksWhenShut(def: LocDefinition): Boolean = clipType(def) != 0

    fun isOpenable(def: LocDefinition): Boolean = def.actions.any { it == OPEN }

    fun isDoorway(def: LocDefinition, type: Int): Boolean = isOpenable(def) && type in WALL_TYPES

    data class Door(
        val locId: Int,
        val x: Int,
        val z: Int,
        val plane: Int,
        var open: Boolean,
        val type: Int = -1,
        val rot: Int = 0
    ) {
        val edgeMask: Int get() = LocClipping.WALL_EDGE[type]?.get(rot and 3) ?: 0

        var edgeRemoved: Boolean = false
            internal set

        override fun toString() =
            "Door($locId at $x,$z,$plane ${if (open) "open" else "shut"}" +
                (if (type >= 0) " shape $type rot $rot edge 0x%02x".format(edgeMask) else "") + ")"
    }

    private val doors = HashMap<Long, Door>()

    private fun key(x: Int, z: Int, plane: Int) =
        (plane.toLong() shl 60) or (x.toLong() shl 30) or z.toLong()

    fun at(x: Int, z: Int, plane: Int = 0): Door? = doors[key(x, z, plane)]
    fun isOpen(x: Int, z: Int, plane: Int = 0): Boolean = at(x, z, plane)?.open == true
    fun trackedCount(): Int = doors.size
    fun openCount(): Int = doors.values.count { it.open }

    fun place(
        locId: Int, x: Int, z: Int, plane: Int = 0, open: Boolean = false,
        type: Int = -1, rot: Int = 0
    ): Door? {
        val def = SqliteDefinitions.loc(locId) ?: return null
        if (!isOpenable(def) && !def.actions.any { it == CLOSE }) return null
        val door = Door(locId, x, z, plane, open = open, type = type, rot = rot)
        doors[key(x, z, plane)] = door
        applyClipping(def, door)
        return door
    }

    fun placeIfAbsent(
        locId: Int, x: Int, z: Int, plane: Int = 0, open: Boolean = false,
        type: Int = -1, rot: Int = 0
    ): Door? = at(x, z, plane) ?: place(locId, x, z, plane, open, type, rot)

    fun clear() {
        doors.values.forEach { door ->
            CollisionMap.setBlocked(door.x, door.z, door.plane, null)
            if (door.edgeRemoved && door.edgeMask != 0) {
                CollisionMap.addWall(door.x, door.z, door.plane, door.edgeMask)
                door.edgeRemoved = false
            }
        }
        doors.clear()
    }

    private fun applyClipping(def: LocDefinition, door: Door) {
        val shouldBlock = !door.open && blocksWhenShutFor(door.locId, def)
        val mask = door.edgeMask
        if (mask != 0) {
            CollisionMap.setBlocked(door.x, door.z, door.plane, null)
            if (!blocksWhenShutFor(door.locId, def)) return
            if (shouldBlock && door.edgeRemoved) {
                CollisionMap.addWall(door.x, door.z, door.plane, mask)
                door.edgeRemoved = false
            } else if (!shouldBlock && !door.edgeRemoved) {
                CollisionMap.removeWall(door.x, door.z, door.plane, mask)
                door.edgeRemoved = true
            }
            return
        }
        if (shouldBlock) CollisionMap.setBlocked(door.x, door.z, door.plane, true)
        else CollisionMap.setBlocked(door.x, door.z, door.plane, null)
    }

    fun onOpen(ctx: LocContext): Any? {
        val door = at(ctx.x, ctx.z, ctx.plane)
            ?: run {
                val squareId = (ctx.z / 64) * 128 + (ctx.x / 64)
                val sql = "SELECT type, rot FROM map_loc WHERE square_id = $squareId AND loc_id = ${ctx.locId} AND x = ${ctx.x % 64} AND y = ${ctx.z % 64} AND plane = ${ctx.plane} LIMIT 1"
                val (type, rot) = com.opennxt.resources.sqlite.RsDatabase.queryAll(sql) { rs -> rs.getInt(1) to rs.getInt(2) }.firstOrNull() ?: (-1 to 0)
                Door(ctx.locId, ctx.x, ctx.z, ctx.plane, open = false, type = type, rot = rot).also { doors[key(ctx.x, ctx.z, ctx.plane)] = it }
            }

        door.open = !door.open
        applyClipping(ctx.definition, door)
        return if (door.open) "opened" else "closed"
    }

    fun onClose(ctx: LocContext): Any? {
        val door = at(ctx.x, ctx.z, ctx.plane) ?: return "not-a-tracked-door"
        if (!door.open) return "already-closed"
        door.open = false
        applyClipping(ctx.definition, door)
        return "closed"
    }

    fun reassertOpenDoors(squareId: Int, plane: Int) {
        val baseX = (squareId and 0x7f) * CollisionMap.SIDE
        val baseZ = (squareId shr 7) * CollisionMap.SIDE
        var reasserted = 0
        doors.values.forEach { door ->
            if (door.plane != plane) return@forEach
            if (door.x !in baseX until baseX + CollisionMap.SIDE) return@forEach
            if (door.z !in baseZ until baseZ + CollisionMap.SIDE) return@forEach
            if (door.open && door.edgeRemoved && door.edgeMask != 0) {
                CollisionMap.removeWall(door.x, door.z, door.plane, door.edgeMask)
                reasserted++
            }
        }
        if (reasserted > 0) {
            logger.info {
                "doors: restored $reasserted open door edge(s) after loading square $squareId plane $plane"
            }
        }
    }

    fun install(): Pair<Int, Int> {
        val opened = ContentRegistry.onLocAction(OPEN, ::onOpen)
        val closed = ContentRegistry.onLocAction(CLOSE, ::onClose)
        if (LocClipping.afterApply.none { it == ::reassertOpenDoors })
            LocClipping.afterApply += ::reassertOpenDoors
        logger.info { "doors: bound Open across $opened locs, Close across $closed locs" }
        return opened to closed
    }

    data class Placement(
        val locId: Int, val name: String?, val x: Int, val z: Int, val plane: Int,
        val type: Int, val rot: Int
    )

    fun placements(limit: Int, wallTypesOnly: Boolean = true): List<Placement> {
        val typeFilter = if (wallTypesOnly) "AND ml.type IN (${WALL_TYPES.joinToString(",")})" else ""
        val sql = "SELECT ml.square_id, ml.plane, ml.x, ml.y, ml.loc_id, ml.type, ml.rot, l.name " +
            "FROM map_loc ml JOIN locs l ON l.id = ml.loc_id " +
            "WHERE l.actions_0 = 'Open' $typeFilter LIMIT $limit"
        return RsDatabase.queryAll(sql) { rs ->
            val sid = rs.getInt("square_id")
            Placement(
                locId = rs.getInt("loc_id"),
                name = rs.getString("name"),
                x = (sid and 0x7f) * CollisionMap.SIDE + rs.getInt("x"),
                z = (sid shr 7) * CollisionMap.SIDE + rs.getInt("y"),
                plane = rs.getInt("plane"),
                type = rs.getInt("type"),
                rot = rs.getInt("rot")
            )
        }
    }

    fun placementsByType(): Map<Int, Int> {
        val sql = "SELECT ml.type AS t, COUNT(*) AS n FROM map_loc ml JOIN locs l ON l.id = ml.loc_id " +
            "WHERE l.actions_0 = 'Open' GROUP BY ml.type"
        return RsDatabase.queryAll(sql) { it.getInt("t") to it.getInt("n") }.toMap()
    }
}

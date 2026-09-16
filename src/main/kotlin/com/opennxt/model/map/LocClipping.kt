package com.opennxt.model.map

import com.opennxt.resources.sqlite.LocDefinition
import com.opennxt.resources.sqlite.RsDatabase
import mu.KotlinLogging

object LocClipping {
    private val logger = KotlinLogging.logger { }

    val enabled: Boolean = System.getProperty("opennxt.experiment.locClipping") != "false"

    val capacity: Int = System.getProperty("opennxt.locClipping.capacity")?.toIntOrNull() ?: 256

    val WALL_EDGE: Map<Int, IntArray> = mapOf(
        0 to intArrayOf(CollisionMap.WALL_W, CollisionMap.WALL_N, CollisionMap.WALL_E, CollisionMap.WALL_S),
        2 to intArrayOf(
            CollisionMap.WALL_W or CollisionMap.WALL_N,
            CollisionMap.WALL_N or CollisionMap.WALL_E,
            CollisionMap.WALL_E or CollisionMap.WALL_S,
            CollisionMap.WALL_S or CollisionMap.WALL_W
        )
    )

    val CORNER_POST: Map<Int, IntArray> = mapOf(
        1 to intArrayOf(CollisionMap.WALL_NW, CollisionMap.WALL_NE, CollisionMap.WALL_SE, CollisionMap.WALL_SW),
        3 to intArrayOf(CollisionMap.WALL_NW, CollisionMap.WALL_NE, CollisionMap.WALL_SE, CollisionMap.WALL_SW)
    )

    const val DIAGONAL_TYPE = 9

    @Volatile
    var diagonalWallsEnabled: Boolean = System.getProperty("opennxt.experiment.map.diagonalWalls") == "true"

    val PROVENANCE: String =
        "wall mask: edges from loc types 0/2, corners from 1/3; diagonal walls (type 9) " +
            "${if (diagonalWallsEnabled) "block the whole tile" else "ignored"} (-Dopennxt.experiment.map.diagonalWalls)"

    val WALL_TYPES: Set<Int> = WALL_EDGE.keys

    val CORNER_TYPES: Set<Int> = CORNER_POST.keys

    val FOOTPRINT_TYPES: IntRange = 10..21

    fun clipType(def: LocDefinition, op74: Boolean = false): Int {
        var clip = 2
        if (def.walkable) clip = 0
        if (def.blocksMovement) clip = 1
        if (op74) clip = 0
        return clip
    }

    fun footprint(rot: Int, width: Int, length: Int): Pair<Int, Int> =
        if ((rot and 0xfd) == 0) width to length else length to width

    private class ClipDef(val clip: Int, val width: Int, val length: Int)

    private val clipDefs: Map<Int, ClipDef> by lazy {
        if (!RsDatabase.available) return@lazy emptyMap()
        val op74 = HashSet(
            RsDatabase.queryAll("SELECT id FROM locs_attr WHERE field = 'unknown_4A'") { it.getInt(1) }
        )
        val out = HashMap<Int, ClipDef>(150_000)
        RsDatabase.queryAll(
            "SELECT id, walkable, blocks_movement, width, length FROM locs"
        ) { rs ->
            val id = rs.getInt("id")
            var clip = 2
            if (rs.getInt("walkable") != 0) clip = 0
            if (rs.getInt("blocks_movement") != 0) clip = 1
            if (id in op74) clip = 0
            val w = rs.getInt("width").let { if (rs.wasNull() || it <= 0) 1 else it }
            val l = rs.getInt("length").let { if (rs.wasNull() || it <= 0) 1 else it }
            id to ClipDef(clip, w, l)
        }.forEach { (id, d) -> out[id] = d }
        logger.info {
            "loc clipping: ${out.size} loc definitions, ${op74.size} of them pass-through via opcode 74"
        }
        out
    }

    fun clipTypeFor(locId: Int, def: LocDefinition): Int =
        clipDefs[locId]?.clip ?: clipType(def, op74 = false)

    fun clipOf(locId: Int): Int = clipDefs[locId]?.clip ?: 2

    fun definitionCount(): Int = clipDefs.size

    fun op74Locs(): Int =
        if (!RsDatabase.available) 0
        else RsDatabase.queryAll("SELECT COUNT(*) FROM locs_attr WHERE field = 'unknown_4A'") { it.getInt(1) }
            .firstOrNull() ?: 0

    val afterApply = java.util.concurrent.CopyOnWriteArrayList<(Int, Int) -> Unit>()

    private class Applied(
        val squareId: Int, val plane: Int, val walls: Int, val footprints: Int,
        val corners: Boolean, val diag9: Boolean
    )

    private val loaded = LinkedHashMap<Long, Applied>()

    private fun squareKey(squareId: Int, plane: Int) = (squareId.toLong() shl 8) or plane.toLong()

    fun isLoadedAt(x: Int, z: Int, plane: Int): Boolean {
        val sid = ((z / CollisionMap.SIDE) shl 7) or (x / CollisionMap.SIDE)
        return synchronized(loaded) { loaded.containsKey(squareKey(sid, plane)) }
    }

    fun loadedSquares(): Int = synchronized(loaded) { loaded.size }

    fun applySquareAt(x: Int, z: Int, plane: Int): Int {
        if (!enabled || !RsDatabase.available) return 0
        val sid = ((z / CollisionMap.SIDE) shl 7) or (x / CollisionMap.SIDE)
        return applySquare(sid, plane)
    }

    fun applySquare(squareId: Int, plane: Int): Int {
        if (!enabled || !RsDatabase.available) return 0
        val k = squareKey(squareId, plane)
        synchronized(loaded) { if (loaded.containsKey(k)) return 0 }

        val baseX = (squareId and 0x7f) * CollisionMap.SIDE
        val baseZ = (squareId shr 7) * CollisionMap.SIDE
        val corners = CollisionMap.cornerPostsEnabled
        val diag9 = diagonalWallsEnabled
        var walls = 0
        var footprints = 0
        val rows = RsDatabase.queryAll(
            "SELECT x, y, loc_id, type, rot FROM map_loc WHERE square_id = ? AND plane = $plane", squareId
        ) { rs ->
            intArrayOf(rs.getInt("x"), rs.getInt("y"), rs.getInt("loc_id"), rs.getInt("type"), rs.getInt("rot"))
        }
        for (r in rows) {
            val (lx, lz, locId) = Triple(r[0], r[1], r[2])
            val type = r[3]
            val rot = r[4] and 3
            val def = clipDefs[locId] ?: continue
            if (def.clip == 0) continue
            val wx = baseX + lx
            val wz = baseZ + lz
            val edge = WALL_EDGE[type]
            val post = CORNER_POST[type]
            if (edge != null) {
                CollisionMap.addWall(wx, wz, plane, edge[rot])
                walls++
            } else if (post != null && corners) {
                CollisionMap.addWall(wx, wz, plane, post[rot])
                walls++
            } else if (type == DIAGONAL_TYPE && diag9) {
                CollisionMap.addOccupancy(wx, wz, plane)
                footprints++
            } else if (type in FOOTPRINT_TYPES) {
                val (dx, dz) = footprint(rot, def.width, def.length)
                for (ox in 0 until dx) for (oz in 0 until dz) {
                    CollisionMap.addOccupancy(wx + ox, wz + oz, plane)
                }
                footprints++
            }
        }
        synchronized(loaded) {
            if (loaded.containsKey(k)) {
                undo(plane, rows, baseX, baseZ, corners, diag9)
                return 0
            }
            loaded[k] = Applied(squareId, plane, walls, footprints, corners, diag9)
        }
        afterApply.forEach { it(squareId, plane) }
        evictIfNeeded()
        return walls + footprints
    }

    private fun undo(
        plane: Int, rows: List<IntArray>, baseX: Int, baseZ: Int,
        corners: Boolean, diag9: Boolean
    ) {
        for (r in rows) {
            val def = clipDefs[r[2]] ?: continue
            if (def.clip == 0) continue
            val wx = baseX + r[0]
            val wz = baseZ + r[1]
            val rot = r[4] and 3
            val edge = WALL_EDGE[r[3]]
            val post = CORNER_POST[r[3]]
            if (edge != null) {
                CollisionMap.removeWall(wx, wz, plane, edge[rot])
            } else if (post != null && corners) {
                CollisionMap.removeWall(wx, wz, plane, post[rot])
            } else if (r[3] == DIAGONAL_TYPE && diag9) {
                CollisionMap.removeOccupancy(wx, wz, plane)
            } else if (r[3] in FOOTPRINT_TYPES) {
                val (dx, dz) = footprint(rot, def.width, def.length)
                for (ox in 0 until dx) for (oz in 0 until dz) {
                    CollisionMap.removeOccupancy(wx + ox, wz + oz, plane)
                }
            }
        }
    }

    fun unload(squareId: Int, plane: Int): Boolean {
        val k = squareKey(squareId, plane)
        synchronized(loaded) { if (!loaded.containsKey(k)) return false }
        val baseX = (squareId and 0x7f) * CollisionMap.SIDE
        val baseZ = (squareId shr 7) * CollisionMap.SIDE
        val rows = RsDatabase.queryAll(
            "SELECT x, y, loc_id, type, rot FROM map_loc WHERE square_id = ? AND plane = $plane", squareId
        ) { rs ->
            intArrayOf(rs.getInt("x"), rs.getInt("y"), rs.getInt("loc_id"), rs.getInt("type"), rs.getInt("rot"))
        }
        synchronized(loaded) {
            val was = loaded.remove(k) ?: return false
            undo(plane, rows, baseX, baseZ, was.corners, was.diag9)
        }
        return true
    }

    fun unloadAt(x: Int, z: Int, plane: Int): Boolean =
        unload(((z / CollisionMap.SIDE) shl 7) or (x / CollisionMap.SIDE), plane)

    private fun evictIfNeeded() {
        while (true) {
            val victim = synchronized(loaded) {
                if (loaded.size <= capacity) return
                loaded.entries.first().value
            }
            if (!unload(victim.squareId, victim.plane)) return
        }
    }

    fun clear() {
        val all = synchronized(loaded) { loaded.values.toList() }
        all.forEach { unload(it.squareId, it.plane) }
        synchronized(loaded) { loaded.clear() }
    }

    fun applySceneAt(x: Int, z: Int, plane: Int, maxSquares: Int = Int.MAX_VALUE): Int {
        if (!enabled || !RsDatabase.available) return 0
        var n = 0
        for ((sx, sz) in SCENE_ORDER) {
            if (n >= maxSquares) break
            val px = x + sx * CollisionMap.SIDE
            val pz = z + sz * CollisionMap.SIDE
            if (px < 0 || pz < 0) continue
            if (applySquareAt(px, pz, plane) > 0) n++
        }
        return n
    }

    private val SCENE_ORDER = listOf(
        0 to 0,
        -1 to 0, 1 to 0, 0 to -1, 0 to 1,
        -1 to -1, 1 to -1, -1 to 1, 1 to 1
    )

    fun warm(): Int = definitionCount()
}

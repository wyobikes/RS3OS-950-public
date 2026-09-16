package com.opennxt.model.map

import com.opennxt.resources.sqlite.RsDatabase
import mu.KotlinLogging
import java.util.Collections

object CollisionMap {
    private val logger = KotlinLogging.logger { }

    const val SIDE = 64
    private const val BITMAP_BYTES = SIDE * SIDE / 8

    private val squareIds = HashMap<Int, Int>()

    private const val CACHE_SIZE = 4096
    private val bitmaps = Collections.synchronizedMap(
        object : LinkedHashMap<Long, ByteArray?>(CACHE_SIZE, 0.75f, true) {
            override fun removeEldestEntry(eldest: Map.Entry<Long, ByteArray?>) = size > CACHE_SIZE
        }
    )

    private val dynamic = Collections.synchronizedMap(HashMap<Long, Boolean>())

    const val WALL_NW = 0x1
    const val WALL_N = 0x2
    const val WALL_NE = 0x4
    const val WALL_E = 0x8
    const val WALL_SE = 0x10
    const val WALL_S = 0x20
    const val WALL_SW = 0x40
    const val WALL_W = 0x80

    private val BIT_ORDER = intArrayOf(WALL_N, WALL_E, WALL_S, WALL_W, WALL_NW, WALL_NE, WALL_SE, WALL_SW)

    private val wallCounts = Collections.synchronizedMap(HashMap<Long, IntArray>())

    private val occupancy = Collections.synchronizedMap(HashMap<Long, Int>())

    @Volatile
    var cornerPostsEnabled: Boolean = System.getProperty("opennxt.experiment.map.walledges") != "false"

    private fun dirIndex(bit: Int): Int = when (bit) {
        WALL_N -> 0; WALL_E -> 1; WALL_S -> 2; WALL_W -> 3
        WALL_NW -> 4; WALL_NE -> 5; WALL_SE -> 6; WALL_SW -> 7
        else -> -1
    }

    fun wallMask(x: Int, z: Int, plane: Int = 0): Int {
        val c = wallCounts[key3(x, z, plane)] ?: return 0
        var m = 0
        for (i in BIT_ORDER.indices) if (c[i] > 0) m = m or BIT_ORDER[i]
        return m
    }

    fun edgeMaskOnly(x: Int, z: Int, plane: Int = 0): Int =
        wallMask(x, z, plane) and (WALL_N or WALL_E or WALL_S or WALL_W)

    fun addWall(x: Int, z: Int, plane: Int, mask: Int) = bumpWall(x, z, plane, mask, +1)

    fun removeWall(x: Int, z: Int, plane: Int, mask: Int) = bumpWall(x, z, plane, mask, -1)

    private fun bumpWall(x: Int, z: Int, plane: Int, mask: Int, delta: Int) {
        if (mask == 0) return
        val k = key3(x, z, plane)
        synchronized(wallCounts) {
            val c = wallCounts[k] ?: IntArray(BIT_ORDER.size).also { if (delta > 0) wallCounts[k] = it }
            for (bit in BIT_ORDER) {
                if (mask and bit == 0) continue
                val i = dirIndex(bit)
                c[i] = maxOf(0, c[i] + delta)
            }
            if (c.all { it == 0 }) wallCounts.remove(k)
        }
    }

    fun addOccupancy(x: Int, z: Int, plane: Int) = bumpOccupancy(x, z, plane, +1)
    fun removeOccupancy(x: Int, z: Int, plane: Int) = bumpOccupancy(x, z, plane, -1)

    private fun bumpOccupancy(x: Int, z: Int, plane: Int, delta: Int) {
        val k = key3(x, z, plane)
        synchronized(occupancy) {
            val n = (occupancy[k] ?: 0) + delta
            if (n <= 0) occupancy.remove(k) else occupancy[k] = n
        }
    }

    fun walledTiles(): Int = wallCounts.size

    fun occupiedTiles(): Int = occupancy.size

    fun clearLocClipping() {
        wallCounts.clear()
        occupancy.clear()
    }

    fun edgeBlocked(x: Int, z: Int, dx: Int, dz: Int, plane: Int = 0): Boolean {
        val ep = BridgeFlags.effectivePlane(x, z, plane)
        val from = wallMask(x, z, ep)
        val to = wallMask(x + dx, z + dz, BridgeFlags.effectivePlane(x + dx, z + dz, plane))
        return when {
            dx > 0 && dz == 0 -> (from and WALL_E) != 0 || (to and WALL_W) != 0
            dx < 0 && dz == 0 -> (from and WALL_W) != 0 || (to and WALL_E) != 0
            dz > 0 && dx == 0 -> (from and WALL_N) != 0 || (to and WALL_S) != 0
            dz < 0 && dx == 0 -> (from and WALL_S) != 0 || (to and WALL_N) != 0
            else -> false
        }
    }

    fun cornerBlocked(x: Int, z: Int, dx: Int, dz: Int, plane: Int = 0): Boolean {
        if (!cornerPostsEnabled) return false
        if (dx == 0 || dz == 0) return false
        return when {
            dx > 0 && dz > 0 ->
                has(x, z, WALL_NE, plane) || has(x + 1, z, WALL_NW, plane) ||
                    has(x, z + 1, WALL_SE, plane) || has(x + 1, z + 1, WALL_SW, plane)
            dx > 0 && dz < 0 ->
                has(x, z, WALL_SE, plane) || has(x + 1, z, WALL_SW, plane) ||
                    has(x, z - 1, WALL_NE, plane) || has(x + 1, z - 1, WALL_NW, plane)
            dx < 0 && dz > 0 ->
                has(x, z, WALL_NW, plane) || has(x - 1, z, WALL_NE, plane) ||
                    has(x, z + 1, WALL_SW, plane) || has(x - 1, z + 1, WALL_SE, plane)
            else ->
                has(x, z, WALL_SW, plane) || has(x - 1, z, WALL_SE, plane) ||
                    has(x, z - 1, WALL_NW, plane) || has(x - 1, z - 1, WALL_NE, plane)
        }
    }

    private fun has(x: Int, z: Int, bit: Int, plane: Int): Boolean = (wallMask(x, z, plane) and bit) != 0

    @Volatile private var warnedNoCollision = false

    val available: Boolean by lazy {
        val ok = RsDatabase.available && RsDatabase.hasTable("map_blocked")
        if (!ok) logger.warn { "No map_blocked table - every tile will report blocked" }
        else loadSquareIndex()
        ok
    }

    private fun loadSquareIndex() {
        RsDatabase.queryAll("SELECT square_id, i, j FROM map_square") { rs ->
            Triple(rs.getInt("square_id"), rs.getInt("i"), rs.getInt("j"))
        }.forEach { (id, _, _) ->
            squareIds[key2(id and 0x7f, id shr 7)] = id
        }
        logger.info { "Loaded ${squareIds.size} map squares" }
    }

    fun loadedSquares(): Int = squareIds.size

    private fun key2(a: Int, b: Int) = (a shl 16) or (b and 0xffff)
    private fun key3(x: Int, z: Int, plane: Int) =
        (plane.toLong() shl 60) or (x.toLong() shl 30) or z.toLong()

    private fun squareId(x: Int, z: Int): Int? = squareIds[key2(x / SIDE, z / SIDE)]

    private fun bitmap(squareId: Int, plane: Int): ByteArray? {
        val k = (squareId.toLong() shl 8) or plane.toLong()
        synchronized(bitmaps) { if (bitmaps.containsKey(k)) return bitmaps[k] }
        val data = RsDatabase.queryOne(
            "SELECT bitmap FROM map_blocked WHERE square_id = ? AND plane = $plane", squareId
        ) { it.getBytes("bitmap") }
        if (data != null && data.size != BITMAP_BYTES) {
            logger.warn { "square $squareId plane $plane: ${data.size} bytes, expected $BITMAP_BYTES" }
        }
        bitmaps[k] = data
        return data
    }

    fun cachedBitmaps(): Int = bitmaps.size

    fun blocked(x: Int, z: Int, plane: Int = 0): Boolean {
        val ep = BridgeFlags.effectivePlane(x, z, plane)
        dynamic[key3(x, z, ep)]?.let { return it }
        if (occupancy.isNotEmpty() && (occupancy[key3(x, z, ep)] ?: 0) > 0) return true
        if (!available) {
            if (!warnedNoCollision) {
                warnedNoCollision = true
                mu.KotlinLogging.logger("CollisionMap").warn {
                    "No collision data (rs3.sqlite missing); treating the whole world as walkable"
                }
            }
            return false
        }
        val sid = squareId(x, z) ?: return true
        val bits = bitmap(sid, ep) ?: return true
        val idx = (x % SIDE) * SIDE + (z % SIDE)
        val byte = idx shr 3
        if (byte >= bits.size) return true
        return (bits[byte].toInt() shr (idx and 7)) and 1 != 0
    }

    fun walkable(x: Int, z: Int, plane: Int = 0): Boolean = !blocked(x, z, plane)

    fun setBlocked(x: Int, z: Int, plane: Int, value: Boolean?) {
        val k = key3(x, z, plane)
        if (value == null) dynamic.remove(k) else dynamic[k] = value
    }

    fun clearOverrides() = dynamic.clear()

    fun overrideCount(): Int = dynamic.size

    fun canStep(x: Int, z: Int, dx: Int, dz: Int, plane: Int = 0): Boolean {
        if (blocked(x + dx, z + dz, plane)) return false
        if (dx != 0 && dz != 0) {
            if (blocked(x + dx, z, plane)) return false
            if (blocked(x, z + dz, plane)) return false
            if (edgeBlocked(x, z, dx, 0, plane) || edgeBlocked(x + dx, z, 0, dz, plane)) return false
            if (edgeBlocked(x, z, 0, dz, plane) || edgeBlocked(x, z + dz, dx, 0, plane)) return false
            if (cornerBlocked(x, z, dx, dz, plane)) return false
            return true
        }
        return !edgeBlocked(x, z, dx, dz, plane)
    }
}

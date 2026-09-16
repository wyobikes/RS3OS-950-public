package com.opennxt.model.map

import java.util.ArrayDeque

object PathFinder {
    @Volatile
    @JvmStatic
    var lazyClippingEnabled: Boolean =
        System.getProperty("opennxt.experiment.map.lazyPathClipping") != "false"

    private val DIRS = arrayOf(
        intArrayOf(-1, 0), intArrayOf(1, 0), intArrayOf(0, -1), intArrayOf(0, 1),
        intArrayOf(-1, -1), intArrayOf(1, -1), intArrayOf(-1, 1), intArrayOf(1, 1)
    )

    const val SEARCH = 128

    data class Step(val x: Int, val z: Int)

    private fun key(x: Int, z: Int) = (x.toLong() shl 32) or (z.toLong() and 0xffffffffL)

    private val lastVisited = ThreadLocal.withInitial { 0 }

    fun lastNodesVisited(): Int = lastVisited.get()

    fun nodeBound(search: Int = SEARCH): Int = (search + 1) * (search + 1)

    fun find(
        startX: Int, startZ: Int,
        goalX: Int, goalZ: Int,
        plane: Int = 0,
        search: Int = SEARCH,
        nearest: Boolean = true
    ): List<Step>? {
        val counter = IntArray(1)
        try {
            return findCounting(startX, startZ, goalX, goalZ, plane, search, nearest, counter)
        } finally {
            lastVisited.set(counter[0])
        }
    }

    private fun findCounting(
        startX: Int, startZ: Int,
        goalX: Int, goalZ: Int,
        plane: Int,
        search: Int,
        nearest: Boolean,
        counter: IntArray
    ): List<Step>? {
        if (lazyClippingEnabled) LocClipping.applySquareAt(startX, startZ, plane)
        if (CollisionMap.blocked(startX, startZ, plane)) return null
        if (startX == goalX && startZ == goalZ) return listOf(Step(startX, startZ))

        val half = search / 2
        val cx = (startX + goalX) / 2
        val cz = (startZ + goalZ) / 2
        val x0 = cx - half; val x1 = cx + half
        val z0 = cz - half; val z1 = cz + half

        if (goalX !in x0..x1 || goalZ !in z0..z1) return null

        val came = HashMap<Long, Long>()
        val startKey = key(startX, startZ)
        came[startKey] = -1L
        val queue = ArrayDeque<IntArray>()
        queue.add(intArrayOf(startX, startZ))

        var bestX = startX; var bestZ = startZ
        var bestDist = maxOf(Math.abs(startX - goalX), Math.abs(startZ - goalZ))
        var found = false

        while (queue.isNotEmpty()) {
            val (x, z) = queue.poll()
            counter[0]++
            if (x == goalX && z == goalZ) { found = true; break }
            for (d in DIRS) {
                val nx = x + d[0]; val nz = z + d[1]
                if (nx !in x0..x1 || nz !in z0..z1) continue
                val nk = key(nx, nz)
                if (came.containsKey(nk)) continue
                if (lazyClippingEnabled) LocClipping.applySquareAt(nx, nz, plane)
                if (!CollisionMap.canStep(x, z, d[0], d[1], plane)) continue
                came[nk] = key(x, z)
                val dist = maxOf(Math.abs(nx - goalX), Math.abs(nz - goalZ))
                if (dist < bestDist) { bestDist = dist; bestX = nx; bestZ = nz }
                queue.add(intArrayOf(nx, nz))
            }
        }

        var endX = goalX; var endZ = goalZ
        if (!found) {
            if (!nearest || (bestX == startX && bestZ == startZ)) return null
            endX = bestX; endZ = bestZ
        }

        val out = ArrayList<Step>()
        var cur = key(endX, endZ)
        while (cur != -1L) {
            out.add(Step((cur shr 32).toInt(), (cur and 0xffffffffL).toInt()))
            cur = came[cur] ?: break
        }
        out.reverse()
        return out
    }
}

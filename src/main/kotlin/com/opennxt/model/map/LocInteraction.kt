package com.opennxt.model.map

import com.opennxt.resources.sqlite.RsDatabase

object LocInteraction {
    val radius: Int = System.getProperty("opennxt.locInteraction.radius")?.toIntOrNull() ?: 10

    data class Placed(
        val locId: Int,
        val originX: Int,
        val originZ: Int,
        val type: Int,
        val rot: Int,
        val width: Int,
        val length: Int
    ) {
        val dx: Int get() = LocClipping.footprint(rot, width, length).first
        val dz: Int get() = LocClipping.footprint(rot, width, length).second
        val x0: Int get() = originX
        val z0: Int get() = originZ
        val x1: Int get() = originX + dx - 1
        val z1: Int get() = originZ + dz - 1

        val isWall: Boolean get() = type in 0..3 || type == 9

        val edgeMask: Int get() = LocClipping.WALL_EDGE[type]?.get(rot and 3) ?: 0

        fun covers(x: Int, z: Int): Boolean = x in x0..x1 && z in z0..z1

        val tiles: Int get() = dx * dz

        override fun toString() =
            "Placed($locId @$originX,$originZ t$type r$rot ${dx}x$dz [$x0..$x1,$z0..$z1])"
    }

    fun placementsCovering(x: Int, z: Int, plane: Int): List<Placed> {
        if (!RsDatabase.available) return emptyList()
        val r = radius
        val out = ArrayList<Placed>()
        val squaresX = setOf((x - r) / CollisionMap.SIDE, (x + r) / CollisionMap.SIDE)
        val squaresZ = setOf((z - r) / CollisionMap.SIDE, (z + r) / CollisionMap.SIDE)
        for (sz in squaresZ) for (sx in squaresX) {
            if (sx < 0 || sz < 0) continue
            val sid = (sz shl 7) or sx
            val bx = sx * CollisionMap.SIDE
            val bz = sz * CollisionMap.SIDE
            val sql = "SELECT m.x AS lx, m.y AS lz, m.loc_id AS id, m.type AS t, m.rot AS r, " +
                "COALESCE(l.width, 1) AS w, COALESCE(l.length, 1) AS len " +
                "FROM map_loc m JOIN locs l ON l.id = m.loc_id " +
                "WHERE m.square_id = ? AND m.plane = $plane " +
                "AND m.x BETWEEN ${x - r - bx} AND ${x + r - bx} " +
                "AND m.y BETWEEN ${z - r - bz} AND ${z + r - bz}"
            RsDatabase.queryAll(sql, sid) { rs ->
                Placed(
                    locId = rs.getInt("id"),
                    originX = bx + rs.getInt("lx"),
                    originZ = bz + rs.getInt("lz"),
                    type = rs.getInt("t"),
                    rot = rs.getInt("r"),
                    width = rs.getInt("w"),
                    length = rs.getInt("len")
                )
            }.forEach { if (it.covers(x, z)) out.add(it) }
        }
        return out.sortedWith(
            compareBy(
                { if (it.originX == x && it.originZ == z) 0 else 1 },
                { it.tiles },
                { it.locId }
            )
        )
    }

    fun placementOf(locId: Int, x: Int, z: Int, plane: Int): Placed? =
        placementsCovering(x, z, plane).firstOrNull { it.locId == locId }

    fun placementPlane(locId: Int, x: Int, z: Int, playerPlane: Int): Int? {
        if (placementOf(locId, x, z, playerPlane) != null) return playerPlane
        val bridged = BridgeFlags.effectivePlane(x, z, playerPlane)
        if (bridged != playerPlane && placementOf(locId, x, z, bridged) != null) return bridged
        return null
    }

    fun adjacentToFootprint(px: Int, pz: Int, locId: Int, x: Int, z: Int, plane: Int): Boolean {
        val placement = if (RsDatabase.available) placementOf(locId, x, z, plane) else null
        val x0 = placement?.x0 ?: x
        val z0 = placement?.z0 ?: z
        val x1 = placement?.x1 ?: x
        val z1 = placement?.z1 ?: z
        return px in (x0 - 1)..(x1 + 1) && pz in (z0 - 1)..(z1 + 1)
    }

    fun standingTiles(p: Placed, plane: Int, fromX: Int, fromZ: Int): List<IntArray> {
        val cand = LinkedHashSet<Long>()
        fun add(x: Int, z: Int) { cand.add((x.toLong() shl 32) or (z.toLong() and 0xffffffffL)) }

        if (p.isWall) {
            add(p.originX, p.originZ)
            when (p.edgeMask) {
                CollisionMap.WALL_W -> add(p.originX - 1, p.originZ)
                CollisionMap.WALL_E -> add(p.originX + 1, p.originZ)
                CollisionMap.WALL_N -> add(p.originX, p.originZ + 1)
                CollisionMap.WALL_S -> add(p.originX, p.originZ - 1)
                else -> {
                    add(p.originX - 1, p.originZ); add(p.originX + 1, p.originZ)
                    add(p.originX, p.originZ - 1); add(p.originX, p.originZ + 1)
                }
            }
        } else {
            for (x in p.x0..p.x1) for (z in p.z0..p.z1) add(x, z)
            for (x in p.x0..p.x1) { add(x, p.z0 - 1); add(x, p.z1 + 1) }
            for (z in p.z0..p.z1) { add(p.x0 - 1, z); add(p.x1 + 1, z) }
        }

        return cand.asSequence()
            .map { intArrayOf((it shr 32).toInt(), (it and 0xffffffffL).toInt()) }
            .filter { CollisionMap.walkable(it[0], it[1], plane) }
            .sortedWith(
                compareBy(
                    { maxOf(Math.abs(it[0] - fromX), Math.abs(it[1] - fromZ)) },
                    { it[1] },
                    { it[0] }
                )
            )
            .toList()
    }

    fun inRange(p: Placed, plane: Int, px: Int, pz: Int): Boolean =
        standingTiles(p, plane, px, pz).any { it[0] == px && it[1] == pz }
}

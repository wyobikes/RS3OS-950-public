package com.opennxt.model.world

import com.opennxt.Constants
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager

object NpcSpawnData {
    val dbPath: Path
        get() = System.getProperty("opennxt.cachedb")
            ?.let { Path.of(it) }
            ?: Constants.DATA_PATH.resolve("rs3.sqlite")

    data class Spawn(
        val npcId: Int,
        val x: Int,
        val y: Int,
        val plane: Int,
        val squareId: Int,
        val name: String?
    ) {
        val squareX: Int get() = squareId and 0x7f
        val squareY: Int get() = squareId shr 7
        val localX: Int get() = x and 0x3f
        val localY: Int get() = y and 0x3f
        override fun toString() = "${name ?: "npc$npcId"}($npcId) @ ($x,$y,$plane)"
    }

    val ABSENT_SPAWN_FIELDS = listOf(
        "respawnTicks", "wanderRadius", "facingDirection",
        "spawnGroup", "membersOnly", "worldFlags"
    )

    const val MANIFEST_SQUARES = 8515

    const val SQUARES_WITH_FILE2 = 45

    private var loaded = false
    private val all = ArrayList<Spawn>()
    private val byNpc = HashMap<Int, MutableList<Spawn>>()
    private val bySquare = HashMap<Int, MutableList<Spawn>>()
    private val npcNames = HashMap<Int, String>()

    fun packKey(plane: Int, localX: Int, localY: Int): Int =
        (plane shl 14) or (localX shl 7) or localY

    fun unpackPlane(key: Int): Int = (key shr 14) and 0x3
    fun unpackX(key: Int): Int = (key shr 7) and 0x7f
    fun unpackY(key: Int): Int = key and 0x7f

    @Synchronized
    fun load() {
        if (loaded) return
        val p = dbPath
        require(Files.exists(p)) { "cache database not found: ${p.toAbsolutePath()}" }
        DriverManager.getConnection("jdbc:sqlite:${p.toAbsolutePath()}").use { con ->
            con.createStatement().use { st ->
                st.executeQuery("SELECT game_id, name FROM npcs WHERE game_id IS NOT NULL AND name IS NOT NULL")
                    .use { rs -> while (rs.next()) npcNames.putIfAbsent(rs.getInt(1), rs.getString(2)) }

                st.executeQuery(
                    "SELECT square_id, plane, local_x, local_y, value, key FROM map_keyed"
                ).use { rs ->
                    while (rs.next()) {
                        val square = rs.getInt(1)
                        val plane = rs.getInt(2)
                        val localX = rs.getInt(3)
                        val localY = rs.getInt(4)
                        val rawKey = rs.getInt(6)
                        check(rawKey == packKey(plane, localX, localY)) {
                            "map_keyed row (square=$square) stores key=$rawKey but its columns " +
                                "(plane=$plane, local_x=$localX, local_y=$localY) re-pack to " +
                                "${packKey(plane, localX, localY)}; rebuild rs3.sqlite"
                        }
                        val npcId = rs.getInt(5)
                        val sp = Spawn(
                            npcId = npcId,
                            x = (square and 0x7f) * 64 + localX,
                            y = (square shr 7) * 64 + localY,
                            plane = plane,
                            squareId = square,
                            name = npcNames[npcId]
                        )
                        all.add(sp)
                        byNpc.getOrPut(npcId) { ArrayList() }.add(sp)
                        bySquare.getOrPut(square) { ArrayList() }.add(sp)
                    }
                }
            }
        }
        loaded = true
    }

    fun spawns(): List<Spawn> { load(); return all }

    fun spawnsOfNpc(gameId: Int): List<Spawn> { load(); return byNpc[gameId] ?: emptyList() }

    fun spawnsInSquare(squareId: Int): List<Spawn> { load(); return bySquare[squareId] ?: emptyList() }

    fun spawnsNamed(name: String): List<Spawn> =
        spawns().filter { it.name.equals(name, ignoreCase = true) }

    fun spawnsInBox(x0: Int, y0: Int, x1: Int, y1: Int, plane: Int? = null): List<Spawn> =
        spawns().filter {
            it.x in x0..x1 && it.y in y0..y1 && (plane == null || it.plane == plane)
        }

    fun coveredSquares(): Set<Int> { load(); return bySquare.keys.toSet() }

    fun isCovered(x: Int, y: Int): Boolean =
        coveredSquares().contains((x / 64) or ((y / 64) shl 7))

    fun spawnMetadataFor(@Suppress("UNUSED_PARAMETER") gameId: Int): Map<String, Int>? = null

    fun coverageFraction(): Double = coveredSquares().size.toDouble() / MANIFEST_SQUARES
}

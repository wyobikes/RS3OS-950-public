package com.opennxt.tools.impl

import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.opennxt.Constants
import com.opennxt.filesystem.Index
import com.opennxt.filesystem.sqlite.SqliteFilesystem
import com.opennxt.tools.Tool
import java.nio.ByteBuffer
import java.sql.Connection
import java.sql.DriverManager

class MapBuilder : Tool("map-builder", "Fills the map tables in data/rs3.sqlite from the game cache") {
    private val database by option(help = "The database to fill (default: data/rs3.sqlite)")
        .default(Constants.DATA_PATH.resolve("rs3.sqlite").toString())

    private val force by option(help = "Rewrite the map tables even when they already hold rows")
        .flag(default = false)

    private companion object {
        const val PLANES = 4
        const val SIDE = 64
        const val BITMAP_BYTES = SIDE * SIDE / 8
        const val GRID_I = 128
        const val GRID_J = 256
    }

    override fun runTool() {
        val cache = SqliteFilesystem(Constants.CACHE_PATH)
        val table = cache.getReferenceTable(Index.MAPS)
            ?: throw IllegalStateException(
                "the cache at ${Constants.CACHE_PATH} has no reference table for index ${Index.MAPS}.\n" +
                    "  Download one first: run-tool cache-downloader"
            )

        DriverManager.getConnection("jdbc:sqlite:$database").use { db ->
            db.autoCommit = false
            createTables(db)
            guardExisting(db)

            val squares = ArrayList<Pair<Int, MapTileExtract.Square>>()
            var locRows = 0L
            var keyedRows = 0L
            var noTerrain = 0
            var shortWalk = 0
            var locFailed = 0
            var desynced = 0

            db.prepareStatement(
                "INSERT INTO map_keyed (square_id, plane, tile_x, tile_z, local_x, local_y, value, key) " +
                    "VALUES (?,?,?,?,?,?,?,?)"
            ).use { insertKeyed ->
            db.prepareStatement(
                "INSERT INTO map_loc (square_id, plane, x, y, loc_id, type, rot, xform) " +
                    "VALUES (?,?,?,?,?,?,?,?)"
            ).use { insertLoc ->
                for (i in 0 until GRID_I) {
                    for (j in 0 until GRID_J) {
                        val sid = j * GRID_I + i
                        val archive = try { table.loadArchive(sid) } catch (e: Exception) { null } ?: continue

                        val terrain = archive.files[3]?.data
                        if (terrain == null) {
                            noTerrain++
                        } else {
                            val square = MapTileExtract.decodeSquare(terrain)
                            if (square == null) {
                                shortWalk++
                            } else {
                                squares.add(sid to square)
                            }
                        }

                        archive.files[2]?.data?.let { keyed ->
                            keyedRows += decodeKeyed(sid, keyed) { row ->
                                insertKeyed.setInt(1, row.squareId)
                                insertKeyed.setInt(2, row.plane)
                                insertKeyed.setInt(3, row.tileX)
                                insertKeyed.setInt(4, row.tileZ)
                                insertKeyed.setInt(5, row.localX)
                                insertKeyed.setInt(6, row.localY)
                                insertKeyed.setInt(7, row.value)
                                insertKeyed.setInt(8, row.key)
                                insertKeyed.addBatch()
                            }
                        }

                        val land = archive.files[0]?.data
                        if (land != null) {
                            try {
                                val reader = MapLocExtract.Reader(ByteBuffer.wrap(land))
                                locRows += MapLocExtract.decodeInto(reader, sid, 0) { row ->
                                    insertLoc.setInt(1, row.squareId)
                                    insertLoc.setInt(2, row.plane)
                                    insertLoc.setInt(3, row.x)
                                    insertLoc.setInt(4, row.y)
                                    insertLoc.setInt(5, row.locId)
                                    insertLoc.setInt(6, row.type)
                                    insertLoc.setInt(7, row.rot)
                                    insertLoc.setString(8, row.xform)
                                    insertLoc.addBatch()
                                }
                                if (reader.remaining() > 0) desynced++
                                insertLoc.executeBatch()
                            } catch (e: Exception) {
                                locFailed++
                                logger.warn { "square $sid: placement decode failed - ${e.message}" }
                            }
                        }
                    }
                }
                insertLoc.executeBatch()
            }
                insertKeyed.executeBatch()
            }

            writeSquares(db, squares)
            db.commit()

            logger.info { "map_square  : ${squares.size} squares" }
            logger.info { "map_blocked : ${squares.size * PLANES} bitmaps" }
            logger.info { "map_loc     : $locRows placements" }
            logger.info { "map_keyed   : $keyedRows records" }
            if (noTerrain > 0) logger.info { "no file 3   : $noTerrain archives" }
            if (shortWalk > 0) logger.warn { "ran short   : $shortWalk squares dropped" }
            if (locFailed > 0) logger.warn { "failed      : $locFailed squares" }
            if (desynced > 0) logger.warn { "desynced    : $desynced placement files left bytes unread" }

            if (squares.isEmpty()) {
                throw IllegalStateException(
                    "no map squares decoded; check the cache at ${Constants.CACHE_PATH}"
                )
            }
            logger.info { "Map tables written to $database" }
        }
    }

    private class KeyedRow(
        val squareId: Int, val plane: Int, val tileX: Int, val tileZ: Int,
        val localX: Int, val localY: Int, val value: Int, val key: Int
    )

    private fun decodeKeyed(sid: Int, data: ByteArray, emit: (KeyedRow) -> Unit): Long {
        if (data.size % 4 != 0) {
            logger.warn { "square $sid: file 2 is ${data.size} bytes, not a multiple of 4 - skipped" }
            return 0
        }
        val buf = ByteBuffer.wrap(data)
        var n = 0L
        while (buf.remaining() >= 4) {
            val key = buf.short.toInt() and 0xffff
            val value = buf.short.toInt() and 0xffff
            val plane = (key shr 14) and 0x3
            val localX = (key shr 7) and 0x7f
            val localY = key and 0x7f
            emit(
                KeyedRow(
                    squareId = sid, plane = plane,
                    tileX = (sid and 0x7f) * SIDE + localX,
                    tileZ = (sid shr 7) * SIDE + localY,
                    localX = localX, localY = localY, value = value, key = key
                )
            )
            n++
        }
        return n
    }

    private fun blockedBitmap(settings: ByteArray, plane: Int): ByteArray {
        val out = ByteArray(BITMAP_BYTES)
        val base = plane * SIDE
        for (x in 0 until SIDE) {
            val row = (base + x) * SIDE
            for (y in 0 until SIDE) {
                if ((settings[row + y].toInt() and 0x1) != 0) {
                    val n = x * SIDE + y
                    out[n shr 3] = (out[n shr 3].toInt() or (1 shl (n and 7))).toByte()
                }
            }
        }
        return out
    }

    private fun shortsToBlob(values: ShortArray): ByteArray {
        val out = ByteArray(values.size * 2)
        for (k in values.indices) {
            out[k * 2] = (values[k].toInt() shr 8).toByte()
            out[k * 2 + 1] = values[k].toInt().toByte()
        }
        return out
    }

    private fun writeSquares(db: Connection, squares: List<Pair<Int, MapTileExtract.Square>>) {
        db.prepareStatement(
            "INSERT INTO map_square (square_id, i, j, planes, heights, flags, underlay, overlay, shape) " +
                "VALUES (?,?,?,?,?,?,?,?,?)"
        ).use { st ->
            for ((sid, sq) in squares) {
                st.setInt(1, sid)
                st.setInt(2, sid % GRID_I)
                st.setInt(3, sid / GRID_I)
                st.setInt(4, PLANES)
                st.setBytes(5, shortsToBlob(sq.heights))
                st.setBytes(6, sq.settings)
                st.setBytes(7, shortsToBlob(sq.underlay))
                st.setBytes(8, shortsToBlob(sq.overlay))
                st.setBytes(9, sq.shape)
                st.addBatch()
            }
            st.executeBatch()
        }

        db.prepareStatement(
            "INSERT INTO map_blocked (square_id, plane, bitmap) VALUES (?,?,?)"
        ).use { st ->
            for ((sid, sq) in squares) {
                for (plane in 0 until PLANES) {
                    st.setInt(1, sid)
                    st.setInt(2, plane)
                    st.setBytes(3, blockedBitmap(sq.settings, plane))
                    st.addBatch()
                }
            }
            st.executeBatch()
        }
    }

    private fun createTables(db: Connection) {
        db.createStatement().use { st ->
            st.executeUpdate(
                "CREATE TABLE IF NOT EXISTS map_square (square_id INTEGER PRIMARY KEY, i INTEGER, " +
                    "j INTEGER, planes INTEGER, heights BLOB, flags BLOB, underlay BLOB, " +
                    "overlay BLOB, shape BLOB)"
            )
            st.executeUpdate(
                "CREATE TABLE IF NOT EXISTS map_blocked (square_id INTEGER, plane INTEGER, bitmap BLOB)"
            )
            st.executeUpdate(
                "CREATE TABLE IF NOT EXISTS map_loc (square_id INTEGER, plane INTEGER, x INTEGER, " +
                    "y INTEGER, loc_id INTEGER, type INTEGER, rot INTEGER, xform TEXT)"
            )
            st.executeUpdate(
                "CREATE TABLE IF NOT EXISTS map_keyed (square_id INTEGER, plane INTEGER, " +
                    "tile_x INTEGER, tile_z INTEGER, local_x INTEGER, local_y INTEGER, " +
                    "value INTEGER, key INTEGER)"
            )
        }
    }

    private fun guardExisting(db: Connection) {
        val counts = listOf("map_square", "map_blocked", "map_loc", "map_keyed").associateWith { t ->
            db.createStatement().use { st ->
                st.executeQuery("SELECT COUNT(*) FROM $t").use { rs -> if (rs.next()) rs.getLong(1) else 0L }
            }
        }
        val populated = counts.filterValues { it > 0 }
        if (populated.isNotEmpty() && !force) {
            throw IllegalStateException(
                "$database already holds map data (" +
                    populated.entries.joinToString(", ") { "${it.key} ${it.value}" } + "); pass --force to rebuild"
            )
        }
        db.createStatement().use { st ->
            for (t in listOf("map_square", "map_blocked", "map_loc", "map_keyed")) st.executeUpdate("DELETE FROM $t")
        }
    }
}

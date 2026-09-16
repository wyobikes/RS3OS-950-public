package com.opennxt.filesystem.sqlite

import com.opennxt.filesystem.ReferenceTable
import java.io.Closeable
import java.nio.file.Path
import java.sql.DriverManager

class SqliteIndexFile(val path: Path) : Closeable, AutoCloseable {
    val table: ReferenceTable? = null

    val connection = DriverManager.getConnection("jdbc:sqlite:$path")

    init {
        try {
            connection.createStatement().use { stmt ->
                stmt.execute("PRAGMA journal_mode=WAL;")
                stmt.execute("PRAGMA synchronous=NORMAL;")
                stmt.execute("PRAGMA temp_store=MEMORY;")
            }
        } catch (e: Exception) {
            System.err.println("SqliteIndexFile: could not enable WAL on $path (${e.message}); cache writes will be slow")
        }

        connection.prepareStatement(
            """
            CREATE TABLE IF NOT EXISTS `cache`(
              `KEY` INTEGER PRIMARY KEY,
              `DATA` BLOB,
              `VERSION` INTEGER,
              `CRC` INTEGER
            );
        """.trimIndent()
        ).use { stmt -> stmt.executeUpdate() }

        connection.prepareStatement(
            """
            CREATE TABLE IF NOT EXISTS `cache_index`(
              `KEY` INTEGER PRIMARY KEY,
              `DATA` BLOB,
              `VERSION` INTEGER,
              `CRC` INTEGER
            );
        """.trimIndent()
        ).use { stmt -> stmt.executeUpdate() }
    }

    val archiveExistsStmt = connection.prepareStatement("SELECT 1 FROM `cache` WHERE `KEY` = ?;")
    val getMaxArchiveStmt = connection.prepareStatement("SELECT MAX(`KEY`) FROM `cache`;")
    val getArchiveDataStmt = connection.prepareStatement("SELECT `DATA` FROM `cache` WHERE `KEY` = ?;")
    val getReferenceDataStmt = connection.prepareStatement("SELECT `DATA` FROM `cache_index` WHERE `KEY` = 1;")
    val putArchiveDataStmt = connection.prepareStatement(
        """
            INSERT INTO `cache`(`KEY`, `DATA`, `VERSION`, `CRC`)
              VALUES(?, ?, ?, ?)
              ON CONFLICT(`KEY`) DO UPDATE SET
                `DATA` = ?, `VERSION` = ?, `CRC` = ?
              WHERE `KEY` = ?;
    """.trimIndent()
    )
    val putReferenceDataStmt = connection.prepareStatement(
        """
            INSERT INTO `cache_index`(`KEY`, `DATA`, `VERSION`, `CRC`)
              VALUES(1, ?, ?, ?)
              ON CONFLICT(`KEY`) DO UPDATE SET
                `DATA` = ?, `VERSION` = ?, `CRC` = ?
              WHERE `KEY` = 1;
    """.trimIndent()
    )

    @Synchronized
    fun putRawTable(data: ByteArray, version: Int, crc: Int): Int {
        val stmt = putReferenceDataStmt
        stmt.clearParameters()
        stmt.setBytes(1, data)
        stmt.setInt(2, version)
        stmt.setInt(3, crc)
        stmt.setBytes(4, data)
        stmt.setInt(5, version)
        stmt.setInt(6, crc)
        return stmt.executeUpdate()
    }

    @Synchronized
    fun putRaw(archive: Int, data: ByteArray, version: Int, crc: Int): Int {
        connection.prepareStatement(
            """
            INSERT INTO `cache`(`KEY`, `DATA`, `VERSION`, `CRC`)
              VALUES(?, ?, ?, ?)
              ON CONFLICT(`KEY`) DO UPDATE SET
                `DATA` = ?, `VERSION` = ?, `CRC` = ?
              WHERE `KEY` = ?;
            """
        ).use { stmt ->
            stmt.clearParameters()
            stmt.setInt(1, archive)
            stmt.setBytes(2, data)
            stmt.setInt(3, version)
            stmt.setInt(4, crc)
            stmt.setBytes(5, data)
            stmt.setInt(6, version)
            stmt.setInt(7, crc)
            stmt.setInt(8, archive)
            return stmt.executeUpdate()
        }
    }

    @Synchronized
    fun hasReferenceTable(): Boolean {
        getReferenceDataStmt.executeQuery().use { return it.next() }
    }

    @Synchronized
    override fun close() {
        getMaxArchiveStmt.close()
        getArchiveDataStmt.close()
        getReferenceDataStmt.close()
        putArchiveDataStmt.close()
        putReferenceDataStmt.close()
    }

    @Synchronized
    fun getMaxArchive(): Int {
        getMaxArchiveStmt.executeQuery().use {
            if (!it.next()) return 0
            return it.getInt(1)
        }
    }

    @Synchronized
    fun exists(id: Int): Boolean {
        val stmt = archiveExistsStmt
        stmt.clearParameters()
        stmt.setInt(1, id)
        stmt.executeQuery().use { return it.next() }
    }

    @Synchronized
    fun getRaw(id: Int): ByteArray? {
        connection.prepareStatement("SELECT `DATA` FROM `cache` WHERE `KEY` = ?;").use { stmt ->
            stmt.clearParameters()
            stmt.setInt(1, id)
            stmt.executeQuery().use {
                if (!it.next()) return null
                return it.getBytes("DATA")
            }
        }
    }

    @Synchronized
    fun getRawTable(): ByteArray? {
        getReferenceDataStmt.executeQuery().use {
            if (!it.next()) return null
            return it.getBytes("DATA")
        }
    }
}

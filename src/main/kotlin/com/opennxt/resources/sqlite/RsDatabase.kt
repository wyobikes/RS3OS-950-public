package com.opennxt.resources.sqlite

import com.opennxt.Constants
import mu.KotlinLogging
import java.nio.file.Files
import java.sql.Connection
import org.sqlite.SQLiteConfig
import java.sql.ResultSet

object RsDatabase {
    private val logger = KotlinLogging.logger { }

    val path = Constants.DATA_PATH.resolve("rs3.sqlite")

    val available: Boolean by lazy {
        val exists = Files.exists(path)
        if (!exists) logger.warn { "No definition database at $path - item/npc/loc/varbit lookups will return null" }
        exists
    }

    private val connection: Connection? by lazy {
        if (!available) null
        else {
            val config = SQLiteConfig()
            config.setReadOnly(true)
            config.createConnection("jdbc:sqlite:$path").also {
                logger.info { "Opened definition database $path (read-only)" }
            }
        }
    }

    val queries = java.util.concurrent.atomic.AtomicLong()

    fun queryCount(): Long = queries.get()

    private val tableMemo = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    fun hasTable(table: String): Boolean {
        val c = connection ?: return false
        tableMemo[table]?.let { return it }
        queries.incrementAndGet()
        c.prepareStatement("SELECT 1 FROM sqlite_master WHERE type='table' AND name=?").use { st ->
            st.setString(1, table)
            st.executeQuery().use {
                val answer = it.next()
                tableMemo[table] = answer
                return answer
            }
        }
    }

    fun forgetTableMemo() = tableMemo.clear()

    fun columnsOf(table: String): Set<String> {
        val c = connection ?: return emptySet()
        if (!hasTable(table)) return emptySet()
        val out = LinkedHashSet<String>()
        queries.incrementAndGet()
        c.prepareStatement("PRAGMA table_info(\"$table\")").use { st ->
            st.executeQuery().use { rs -> while (rs.next()) out.add(rs.getString("name").lowercase()) }
        }
        return out
    }

    fun <T> queryOne(sql: String, id: Int, map: (ResultSet) -> T): T? {
        val c = connection ?: return null
        queries.incrementAndGet()
        c.prepareStatement(sql).use { st ->
            st.setInt(1, id)
            st.executeQuery().use { rs -> return if (rs.next()) map(rs) else null }
        }
    }

    fun <T> queryAll(sql: String, map: (ResultSet) -> T): List<T> {
        val c = connection ?: return emptyList()
        val out = ArrayList<T>()
        queries.incrementAndGet()
        c.prepareStatement(sql).use { st ->
            st.executeQuery().use { rs -> while (rs.next()) out.add(map(rs)) }
        }
        return out
    }

    fun <T> queryAll(sql: String, id: Int, map: (ResultSet) -> T): List<T> {
        val c = connection ?: return emptyList()
        val out = ArrayList<T>()
        queries.incrementAndGet()
        c.prepareStatement(sql).use { st ->
            st.setInt(1, id)
            st.executeQuery().use { rs -> while (rs.next()) out.add(map(rs)) }
        }
        return out
    }

    fun maxId(table: String): Int {
        val c = connection ?: return 0
        if (!hasTable(table)) return 0
        queries.incrementAndGet()
        c.prepareStatement("SELECT MAX(id) FROM \"$table\"").use { st ->
            st.executeQuery().use { rs -> return if (rs.next()) rs.getInt(1) else 0 }
        }
    }

    fun ResultSet.intOrNull(column: String): Int? {
        val v = getInt(column)
        return if (wasNull()) null else v
    }
}

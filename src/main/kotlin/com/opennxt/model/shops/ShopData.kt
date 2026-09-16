package com.opennxt.model.shops

import com.opennxt.Constants
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager

object ShopData {
    val dbPath: Path
        get() = System.getProperty("opennxt.cachedb")
            ?.let { Path.of(it) }
            ?: Constants.DATA_PATH.resolve("rs3.sqlite")

    enum class OpenerKind { NPC, LOC }

    data class ShopOpener(
        val kind: OpenerKind,
        val gameId: Int,
        val archiveId: Int,
        val name: String?,
        val option: String,
        val slot: Int,
        val membersOnly: Boolean
    ) {
        override fun toString(): String =
            "${kind.name} $gameId ${name ?: "<unnamed>"} op$slot=\"$option\"" +
                (if (membersOnly) " (members)" else "")
    }

    data class ItemStack(val item: Int, val quantity: Int) {
        override fun toString() = "$item x$quantity"
    }

    data class Container(val kind: String, val id: Int, val sub: Int, val hits: List<Int>, val size: Int) {
        override fun toString() = "$kind $id/$sub hits=$hits size=$size"
    }

    val SHOP_OPTION_KEYWORDS = listOf("trade", "shop", "buy", "exchange")

    private var loaded = false
    private val openerList = ArrayList<ShopOpener>()
    private val byNpcGameId = HashMap<Int, MutableList<ShopOpener>>()
    private val byLocId = HashMap<Int, MutableList<ShopOpener>>()
    private val itemNames = HashMap<Int, String>()
    private val itemValues = HashMap<Int, Long>()

    private val farmRows = LinkedHashMap<Int, List<Triple<Int, Int, Int>>>()
    private val letterPools = LinkedHashMap<Int, LinkedHashMap<Char, List<Int>>>()
    private val warpriest = LinkedHashMap<Int, List<Pair<Int, Int>>>()
    private val bundles = LinkedHashMap<Int, List<ItemStack>>()
    private val monthly = ArrayList<MonthlyReward>()

    data class MonthlyReward(val year: Int, val month: Int, val rewards: List<ItemStack>) {
        override fun toString() = "$year-${(month + 1).toString().padStart(2, '0')}: $rewards"
    }

    private fun connect(): Connection {
        val p = dbPath
        require(Files.exists(p)) { "cache database not found at ${p.toAbsolutePath()}" }
        Class.forName("org.sqlite.JDBC")
        return DriverManager.getConnection("jdbc:sqlite:file:${p.toAbsolutePath()}?mode=ro")
    }

    private fun unquote(s: String?): String? {
        if (s == null) return null
        val t = s.trim()
        return if (t.length >= 2 && t.startsWith('"') && t.endsWith('"')) t.substring(1, t.length - 1) else t
    }

    private fun isShopOption(s: String?): Boolean {
        if (s.isNullOrBlank()) return false
        val l = s.lowercase()
        return SHOP_OPTION_KEYWORDS.any { it in l }
    }

    private fun tuple(raw: String): List<String> {
        var s = raw.trim()
        if (s.startsWith("[")) s = s.substring(1)
        if (s.endsWith("]")) s = s.substring(0, s.length - 1)
        if (s.isBlank()) return emptyList()
        val out = ArrayList<String>()
        val sb = StringBuilder()
        var inStr = false
        var esc = false
        for (ch in s) {
            when {
                esc -> { sb.append(ch); esc = false }
                ch == '\\' && inStr -> esc = true
                ch == '"' -> { inStr = !inStr; sb.append(ch) }
                ch == ',' && !inStr -> { out.add(sb.toString().trim()); sb.setLength(0) }
                else -> sb.append(ch)
            }
        }
        out.add(sb.toString().trim())
        return out
    }

    private fun tupleInts(raw: String): List<Int> =
        tuple(raw).mapNotNull { it.toIntOrNull() }

    @Synchronized
    fun load() {
        if (loaded) return
        connect().use { c ->
            loadItems(c)
            loadNpcOpeners(c)
            loadLocOpeners(c)
            loadFarmProduce(c)
            loadLetterPools(c)
            loadWarpriest(c)
            loadBundles(c)
            loadMonthly(c)
        }
        openerList.sortWith(compareBy({ it.kind }, { it.gameId }, { it.slot }))
        loaded = true
    }

    private fun loadItems(c: Connection) {
        c.createStatement().executeQuery("select id, name from items").use { rs ->
            while (rs.next()) {
                val n = rs.getString(2)
                if (n != null) itemNames[rs.getInt(1)] = n
            }
        }
        c.createStatement().executeQuery(
            "select id, value from items_attr where field = 'big_value'"
        ).use { rs ->
            while (rs.next()) {
                val parts = tupleInts(rs.getString(2))
                if (parts.size == 2) itemValues[rs.getInt(1)] = (parts[0].toLong() shl 32) or (parts[1].toLong() and 0xffffffffL)
            }
        }
    }

    private fun addOpener(o: ShopOpener) {
        openerList.add(o)
        when (o.kind) {
            OpenerKind.NPC -> byNpcGameId.getOrPut(o.gameId) { ArrayList() }.add(o)
            OpenerKind.LOC -> byLocId.getOrPut(o.gameId) { ArrayList() }.add(o)
        }
    }

    private fun loadNpcOpeners(c: Connection) {
        val gameId = HashMap<Int, Int>()
        val archiveId = HashMap<Int, Int>()
        val name = HashMap<Int, String?>()
        c.createStatement().executeQuery("select id, game_id, name, _group, _file from npcs").use { rs ->
            while (rs.next()) {
                gameId[rs.getInt(1)] = rs.getInt(2)
                name[rs.getInt(1)] = rs.getString(3)
                archiveId[rs.getInt(1)] = rs.getInt(4) * 256 + rs.getInt(5)
            }
        }
        for (slot in 0..2) {
            c.createStatement().executeQuery(
                "select id, actions_$slot from npcs where actions_$slot is not null"
            ).use { rs ->
                while (rs.next()) {
                    val a = unquote(rs.getString(2)) ?: continue
                    if (!isShopOption(a)) continue
                    val id = rs.getInt(1)
                    addOpener(ShopOpener(OpenerKind.NPC, gameId[id] ?: continue, archiveId[id] ?: id, name[id], a, slot, false))
                }
            }
        }
        val fields = ArrayList<String>()
        c.createStatement().executeQuery(
            "select distinct field from npcs_attr where field like '%actions\\_%' escape '\\'"
        ).use { rs -> while (rs.next()) fields.add(rs.getString(1)) }
        for (f in fields) {
            val slot = f.last().digitToIntOrNull() ?: continue
            val members = f.startsWith("members")
            c.prepareStatement("select id, value from npcs_attr where field = ?").use { ps ->
                ps.setString(1, f)
                ps.executeQuery().use { rs ->
                    while (rs.next()) {
                        val a = unquote(rs.getString(2)) ?: continue
                        if (!isShopOption(a)) continue
                        val id = rs.getInt(1)
                        addOpener(ShopOpener(OpenerKind.NPC, gameId[id] ?: continue, archiveId[id] ?: id, name[id], a, slot, members))
                    }
                }
            }
        }
    }

    private fun loadLocOpeners(c: Connection) {
        val name = HashMap<Int, String?>()
        c.createStatement().executeQuery("select id, name from locs").use { rs ->
            while (rs.next()) name[rs.getInt(1)] = rs.getString(2)
        }
        c.createStatement().executeQuery(
            "select id, actions_0 from locs where actions_0 is not null"
        ).use { rs ->
            while (rs.next()) {
                val a = unquote(rs.getString(2)) ?: continue
                if (!isShopOption(a)) continue
                val id = rs.getInt(1)
                addOpener(ShopOpener(OpenerKind.LOC, id, id, name[id], a, 0, false))
            }
        }
        val fields = ArrayList<String>()
        c.createStatement().executeQuery(
            "select distinct field from locs_attr where field like 'actions\\_%' escape '\\'"
        ).use { rs -> while (rs.next()) fields.add(rs.getString(1)) }
        for (f in fields) {
            val slot = f.last().digitToIntOrNull() ?: continue
            c.prepareStatement("select id, value from locs_attr where field = ?").use { ps ->
                ps.setString(1, f)
                ps.executeQuery().use { rs ->
                    while (rs.next()) {
                        val a = unquote(rs.getString(2)) ?: continue
                        if (!isShopOption(a)) continue
                        val id = rs.getInt(1)
                        addOpener(ShopOpener(OpenerKind.LOC, id, id, name[id], a, slot, false))
                    }
                }
            }
        }
    }

    private fun dbTable(c: Connection, tableId: Int): LinkedHashMap<Int, LinkedHashMap<Int, MutableList<String>>> {
        val out = LinkedHashMap<Int, LinkedHashMap<Int, MutableList<String>>>()
        c.prepareStatement(
            "select row_id, column_id, idx, value from dbrow_value where table_id = ? order by row_id, column_id, idx"
        ).use { ps ->
            ps.setInt(1, tableId)
            ps.executeQuery().use { rs ->
                while (rs.next()) {
                    out.getOrPut(rs.getInt(1)) { LinkedHashMap() }
                        .getOrPut(rs.getInt(2)) { ArrayList() }
                        .add(rs.getString(4))
                }
            }
        }
        return out
    }

    private fun loadFarmProduce(c: Connection) {
        for ((row, cols) in dbTable(c, 30)) {
            farmRows[row] = (cols[0] ?: emptyList()).mapNotNull {
                val t = tupleInts(it)
                if (t.size >= 3) Triple(t[0], t[1], t[2]) else null
            }
        }
    }

    private fun loadLetterPools(c: Connection) {
        for (t in intArrayOf(43, 44, 45, 46)) {
            val m = LinkedHashMap<Char, List<Int>>()
            for ((_, cols) in dbTable(c, t)) {
                val key = cols[0]?.firstOrNull()?.let { tupleInts(it).firstOrNull() } ?: continue
                if (key < 1 || key > 26) continue
                m['A' + (key - 1)] = (cols[1] ?: emptyList()).mapNotNull { tupleInts(it).firstOrNull() }
            }
            letterPools[t] = m
        }
    }

    private fun loadWarpriest(c: Connection) {
        for ((row, cols) in dbTable(c, 63)) {
            val objs = (cols[0] ?: emptyList()).mapNotNull { tupleInts(it).firstOrNull() }
            val flags = (cols[1] ?: emptyList()).mapNotNull { tupleInts(it).firstOrNull() }
            warpriest[row] = objs.mapIndexed { i, o -> o to (flags.getOrNull(i) ?: -1) }
        }
    }

    private fun loadBundles(c: Connection) {
        for ((row, cols) in dbTable(c, 148)) {
            bundles[row] = (cols[0] ?: emptyList()).mapNotNull {
                val t = tupleInts(it)
                if (t.size >= 2) ItemStack(t[0], t[1]) else null
            }
        }
    }

    private fun loadMonthly(c: Connection) {
        for ((_, cols) in dbTable(c, 160)) {
            val y = cols[0]?.firstOrNull()?.let { tupleInts(it).firstOrNull() } ?: continue
            val m = cols[1]?.firstOrNull()?.let { tupleInts(it).firstOrNull() } ?: continue
            val r = (cols[2] ?: emptyList()).mapNotNull {
                val t = tupleInts(it)
                if (t.size >= 2) ItemStack(t[0], t[1]) else null
            }
            monthly.add(MonthlyReward(y, m, r))
        }
    }

    fun openers(): List<ShopOpener> { load(); return openerList }

    fun openersForNpc(gameId: Int): List<ShopOpener> { load(); return byNpcGameId[gameId] ?: emptyList() }

    fun openersForLoc(locId: Int): List<ShopOpener> { load(); return byLocId[locId] ?: emptyList() }

    fun openersNamed(name: String): List<ShopOpener> {
        load(); return openerList.filter { it.name == name }
    }

    fun itemName(id: Int): String? { load(); return itemNames[id] }

    fun itemValue(id: Int): Long? { load(); return itemValues[id] }

    fun itemValueCount(): Int { load(); return itemValues.size }

    @Suppress("UNUSED_PARAMETER")
    fun stockFor(gameId: Int): List<ItemStack>? = null

    const val STOCK_PRESENT_IN_CACHE = false

    fun farmProduce(): Map<Int, List<Triple<Int, Int, Int>>> { load(); return farmRows }
    fun letterPool(tableId: Int): Map<Char, List<Int>> { load(); return letterPools[tableId] ?: emptyMap() }
    fun letterPoolTables(): List<Int> { load(); return letterPools.keys.toList() }
    fun warpriestUnlocks(): Map<Int, List<Pair<Int, Int>>> { load(); return warpriest }
    fun rewardBundles(): Map<Int, List<ItemStack>> { load(); return bundles }
    fun monthlyRewards(): List<MonthlyReward> { load(); return monthly }
}

package com.opennxt.model.drops

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

enum class DropCategory { ALWAYS, MAIN, TERTIARY, TABLE_REF }

data class DropLine(
    val category: DropCategory,
    val itemName: String,
    val itemIds: List<Int>,
    val itemMatchAmbiguous: Boolean,
    val quantity: String,
    val quantityRange: IntRange?,
    val rarity: String,
    val rarityNum: Double?,
    val rarityDen: Double?,
    val note: String?,
    val isTableRef: Boolean,
    val itemId: Int? = DropData.chosenItemId(itemName, itemIds),
    val source: String,
    val retrieved: String
) {
    val tableRefName: String? get() = if (isTableRef) itemName.removePrefix("@") else null

    override fun toString(): String =
        "${category.name.lowercase()} $itemName x$quantity @ $rarity " +
            "[documented: $source]"
}

data class MonsterDrops(
    val name: String,
    val refPage: String,
    val npcIds: List<Int>,
    val npcMatchAmbiguous: Boolean,
    val drops: List<DropLine>,
    val note: String?,
    val source: String,
    val retrieved: String,
    val layer: String = "documented"
) {
    override fun toString(): String =
        "$name (${npcIds.size} npc ids, ${drops.size} drop lines) [$layer: $source]"
}

data class DeclaredCounts(val monsters: Int, val dropLines: Int, val unresolvedItemNames: Int)

data class SubTableLine(
    val section: String,
    val itemName: String,
    val itemIds: List<Int>,
    val itemMatchAmbiguous: Boolean,
    val quantity: String,
    val quantityRange: IntRange?,
    val rarity: String,
    val rarityNum: Double?,
    val rarityDen: Double?,
    val note: String?,
    val isTableRef: Boolean,
    val source: String,
    val retrieved: String
) {
    val tableRefName: String? get() = if (isTableRef) itemName.removePrefix("@") else null

    override fun toString(): String =
        "[$section] $itemName x$quantity @ $rarity [documented: $source]"
}

data class SubTable(
    val name: String,
    val lines: List<SubTableLine>,
    val note: String?,
    val source: String,
    val retrieved: String
) {
    override fun toString(): String = "@$name (${lines.size} lines) [documented: $source]"
}

data class DeclaredSubTableCounts(val tables: Int, val entries: Int, val unresolvedItemNames: Int)

object DropData {
    private const val SCHEMA = "opennxt.seed.drops_documented/1"
    private const val SUB_SCHEMA = "opennxt.seed.drop_subtables/1"

    private val byName = LinkedHashMap<String, MonsterDrops>()
    private val byNpcId = HashMap<Int, MonsterDrops>()
    private val unresolved = ArrayList<Pair<String, DropLine>>()

    private const val DROPS_SCHEMA = "opennxt.seed.drops/1"
    private val refByName = LinkedHashMap<String, MonsterDrops>()
    private val refByNpcId = HashMap<Int, MonsterDrops>()
    private val refUnresolved = ArrayList<Pair<String, DropLine>>()
    private var refDeclared = DeclaredCounts(0, 0, 0)
    private var declared = DeclaredCounts(0, 0, 0)

    private val subTablesByName = LinkedHashMap<String, SubTable>()
    private val subUnresolved = ArrayList<Pair<String, SubTableLine>>()
    private var subDeclared = DeclaredSubTableCounts(0, 0, 0)

    var subTablesLoaded: Boolean = false
        private set

    var loaded: Boolean = false
        private set

    var seedDir: Path = Paths.get(
        System.getProperty("opennxt.seed.dir") ?: "data/seed"
    )
        private set

    @Synchronized
    fun load(dir: Path = seedDir): DropData {
        seedDir = if (Files.isDirectory(dir)) dir
        else Paths.get("data/seed").let { if (Files.isDirectory(it)) it else dir }
        byName.clear(); byNpcId.clear(); unresolved.clear()

        val path = seedDir.resolve("drops_documented.json")
        val root = read(path) ?: throw IllegalStateException(
            "documented drops seed missing: $path"
        )
        val schema = root.strOrNull("_schema")
        if (schema != SCHEMA) throw IllegalStateException(
            "drops seed schema mismatch: expected $SCHEMA got $schema"
        )
        val counts = root.getAsJsonObject("counts") ?: throw IllegalStateException(
            "drops seed has no counts block -- refusing to load uncountable data"
        )
        declared = DeclaredCounts(
            counts.get("monsters").asInt,
            counts.get("drop_lines").asInt,
            counts.get("unresolved_item_names").asInt
        )

        for (el in root.getAsJsonArray("monsters")) {
            val monster = parseMonster(el.asJsonObject, "documented", unresolved) ?: continue
            byName[monster.name] = monster
            for (id in monster.npcIds) byNpcId[id] = monster
        }
        loadRef()
        loadSubTables()
        loaded = true
        return this
    }

    private fun loadRef() {
        refByName.clear(); refByNpcId.clear(); refUnresolved.clear()
        val root = read(seedDir.resolve("drops.json")) ?: return
        val schema = root.strOrNull("_schema")
        if (schema != DROPS_SCHEMA) throw IllegalStateException("drops.json schema mismatch: expected $DROPS_SCHEMA got $schema")
        val counts = root.getAsJsonObject("counts") ?: throw IllegalStateException("drops.json has no counts block")
        refDeclared = DeclaredCounts(counts.get("monsters").asInt, counts.get("drop_lines").asInt, counts.get("unresolved_item_names").asInt)
        for (el in root.getAsJsonArray("monsters")) {
            val monster = parseMonster(el.asJsonObject, "ref", refUnresolved) ?: continue
            refByName[monster.name] = monster
            for (id in monster.npcIds) refByNpcId[id] = monster
        }
    }

    private fun parseMonster(o: JsonObject, layer: String, unresolvedSink: MutableList<Pair<String, DropLine>>): MonsterDrops? {
        run {
            val name = o.strOrNull("name") ?: return null
            val src = o.strOrNull("source") ?: throw IllegalStateException(
                "monster \"$name\" has no source URL -- refusing to load unsourced drops"
            )
            val retrieved = o.strOrNull("retrieved") ?: throw IllegalStateException(
                "monster \"$name\" has no retrieval date -- refusing to load unsourced drops"
            )
            val npcIds = o.getAsJsonArray("npc_ids").map { it.asInt }
            val lines = ArrayList<DropLine>()
            for (dEl in o.getAsJsonArray("drops")) {
                val d = dEl.asJsonObject
                val item = d.strOrNull("item") ?: continue
                val isRef = (d.has("is_table_ref") && !d.get("is_table_ref").isJsonNull
                        && d.get("is_table_ref").asBoolean) || item.startsWith("@")
                val quantity = d.strOrNull("quantity") ?: ""
                if (isInvertedRange(quantity)) invertedQuantityRanges++
                val rawCategory = parseCategory(d.strOrNull("category"), name, item)
                val num = d.dblOrNull("rarity_num"); val den = d.dblOrNull("rarity_den")
                val promote = rawCategory == DropCategory.MAIN && num != null && den != null && den != 0.0 && num / den >= 1.0
                if (promote) promotedAlways++
                val line = DropLine(
                    category = if (promote) DropCategory.ALWAYS else rawCategory,
                    itemName = item,
                    itemIds = (d.getAsJsonArray("item_ids") ?: com.google.gson.JsonArray())
                        .map { it.asInt },
                    itemMatchAmbiguous = d.has("item_match_ambiguous")
                            && !d.get("item_match_ambiguous").isJsonNull
                            && d.get("item_match_ambiguous").asBoolean,
                    quantity = quantity,
                    quantityRange = parseQuantity(quantity),
                    rarity = d.strOrNull("rarity") ?: "",
                    rarityNum = d.dblOrNull("rarity_num"),
                    rarityDen = d.dblOrNull("rarity_den"),
                    note = (d.strOrNull("note") ?: "").let { n ->
                        if (!promote) d.strOrNull("note")
                        else (if (n.isEmpty()) "" else "$n; ") + "re-classed MAIN -> ALWAYS at load: fraction >= 1"
                    },
                    isTableRef = isRef,
                    source = src,
                    retrieved = retrieved
                )
                lines.add(line)
                if (!line.isTableRef && line.itemIds.isEmpty()) unresolvedSink.add(name to line)
            }
            return MonsterDrops(
                name = name,
                refPage = o.strOrNull("page") ?: name,
                npcIds = npcIds,
                npcMatchAmbiguous = o.has("npc_match_ambiguous")
                        && !o.get("npc_match_ambiguous").isJsonNull
                        && o.get("npc_match_ambiguous").asBoolean,
                drops = lines,
                note = o.strOrNull("note"),
                source = src,
                retrieved = retrieved,
                layer = layer
            )
        }
    }

    private fun loadSubTables() {
        subTablesByName.clear(); subUnresolved.clear()
        subDeclared = DeclaredSubTableCounts(0, 0, 0)
        subTablesLoaded = false

        val root = read(seedDir.resolve("drop_subtables.json")) ?: return
        val schema = root.strOrNull("_schema")
        if (schema != SUB_SCHEMA) throw IllegalStateException(
            "drop sub-tables seed schema mismatch: expected $SUB_SCHEMA got $schema"
        )
        val counts = root.getAsJsonObject("counts") ?: throw IllegalStateException(
            "drop sub-tables seed has no counts block -- refusing to load uncountable data"
        )
        subDeclared = DeclaredSubTableCounts(
            counts.get("tables").asInt,
            counts.get("entries").asInt,
            counts.get("unresolved_item_names").asInt
        )

        val tables = root.getAsJsonObject("tables") ?: throw IllegalStateException(
            "drop sub-tables seed has no tables object"
        )
        for ((key, el) in tables.entrySet()) {
            val o = el.asJsonObject
            val src = o.strOrNull("source") ?: throw IllegalStateException(
                "sub-table \"$key\" has no source URL -- refusing to load unsourced contents"
            )
            val retrieved = o.strOrNull("retrieved") ?: throw IllegalStateException(
                "sub-table \"$key\" has no retrieval date -- refusing to load unsourced contents"
            )
            val note = o.strOrNull("note")
            val lines = ArrayList<SubTableLine>()
            for (eEl in o.getAsJsonArray("entries")) {
                val e = eEl.asJsonObject
                val item = e.strOrNull("item") ?: continue
                val isRef = (e.has("is_table_ref") && !e.get("is_table_ref").isJsonNull
                        && e.get("is_table_ref").asBoolean) || item.startsWith("@")
                val quantity = e.strOrNull("quantity") ?: ""
                val line = SubTableLine(
                    section = e.strOrNull("section") ?: throw IllegalStateException(
                        "sub-table \"$key\" row \"$item\" has no section"
                    ),
                    itemName = item,
                    itemIds = (e.getAsJsonArray("item_ids") ?: com.google.gson.JsonArray())
                        .map { it.asInt },
                    itemMatchAmbiguous = e.has("item_match_ambiguous")
                            && !e.get("item_match_ambiguous").isJsonNull
                            && e.get("item_match_ambiguous").asBoolean,
                    quantity = quantity,
                    quantityRange = parseQuantity(quantity),
                    rarity = e.strOrNull("rarity") ?: "",
                    rarityNum = e.dblOrNull("rarity_num"),
                    rarityDen = e.dblOrNull("rarity_den"),
                    note = e.strOrNull("note"),
                    isTableRef = isRef,
                    source = src,
                    retrieved = retrieved
                )
                lines.add(line)
                if (!line.isTableRef && line.itemIds.isEmpty()) subUnresolved.add(key to line)
            }
            if (lines.isEmpty() && note == null) throw IllegalStateException(
                "sub-table \"$key\" is empty with no note saying why -- refusing to load"
            )
            subTablesByName[key] = SubTable(key, lines, note, src, retrieved)
        }
        subTablesLoaded = true
    }

    private fun ensure() { if (!loaded) load() }

    private fun read(path: Path): JsonObject? {
        if (!Files.exists(path)) return null
        Files.newBufferedReader(path).use { return JsonParser().parse(it).asJsonObject }
    }

    private fun JsonObject.strOrNull(k: String): String? =
        if (has(k) && !get(k).isJsonNull) get(k).asString else null

    private fun JsonObject.dblOrNull(k: String): Double? =
        if (has(k) && !get(k).isJsonNull) get(k).asDouble else null

    private fun parseCategory(raw: String?, monster: String, item: String): DropCategory =
        when (raw) {
            "always" -> DropCategory.ALWAYS
            "main" -> DropCategory.MAIN
            "tertiary" -> DropCategory.TERTIARY
            "table_ref" -> DropCategory.TABLE_REF
            else -> throw IllegalStateException(
                "unknown drop category \"$raw\" on $monster/$item -- refusing to guess"
            )
        }

    fun parseQuantity(quantity: String): IntRange? {
        val m = QUANTITY_RE.matchEntire(quantity.trim()) ?: return null
        val lo = m.groupValues[1].toIntOrNull() ?: return null
        val hi = m.groupValues[2].let { if (it.isEmpty()) lo else it.toIntOrNull() ?: return null }
        if (lo > hi) return null
        return lo..hi
    }

    fun isInvertedRange(quantity: String): Boolean {
        val m = QUANTITY_RE.matchEntire(quantity.trim()) ?: return false
        val lo = m.groupValues[1].toIntOrNull() ?: return false
        val hi = m.groupValues[2].let { if (it.isEmpty()) lo else it.toIntOrNull() ?: return false }
        return lo > hi
    }

    val CANONICAL_ITEM_IDS: Map<String, Int> = mapOf(
        "coins" to 995
    )

    fun chosenItemId(itemName: String, itemIds: List<Int>): Int? {
        val canonical = CANONICAL_ITEM_IDS[itemName.trim().lowercase()]
        if (canonical != null && canonical in itemIds) return canonical
        return itemIds.minOrNull()
    }

    var promotedAlways: Int = 0
        private set
    var invertedQuantityRanges: Int = 0
        private set

    private val QUANTITY_RE = Regex("""(\d+)(?:-(\d+))?""")

    fun dropsForNpc(gameId: Int): List<DropLine>? {
        ensure(); return (byNpcId[gameId] ?: refByNpcId[gameId])?.drops
    }

    fun monsterForNpc(gameId: Int): MonsterDrops? { ensure(); return byNpcId[gameId] ?: refByNpcId[gameId] }

    fun refMonsterForNpc(gameId: Int): MonsterDrops? { ensure(); return refByNpcId[gameId] }
    fun refMonsterByName(name: String): MonsterDrops? { ensure(); return refByName[name] }
    fun refMonsters(): Collection<MonsterDrops> { ensure(); return refByName.values }
    fun refMonsterCount(): Int { ensure(); return refByName.size }
    fun refDropLineCount(): Int { ensure(); return refByName.values.sumOf { it.drops.size } }
    fun refNpcIdCount(): Int { ensure(); return refByNpcId.size }
    fun refUnresolvedItemLines(): List<Pair<String, DropLine>> { ensure(); return refUnresolved }
    fun refDeclaredCounts(): DeclaredCounts { ensure(); return refDeclared }
    fun refOnlyNpcIdCount(): Int { ensure(); return refByNpcId.keys.count { it !in byNpcId } }

    fun dropsForName(name: String): MonsterDrops? { ensure(); return byName[name] }

    fun monsters(): Collection<MonsterDrops> { ensure(); return byName.values }

    fun unresolvedItemLines(): List<Pair<String, DropLine>> { ensure(); return unresolved }

    fun subTable(name: String): SubTable? {
        ensure(); return subTablesByName[name.removePrefix("@")]
    }

    fun resolveTableRef(line: DropLine): SubTable? {
        val ref = line.tableRefName ?: return null
        return subTable(ref)
    }

    fun subTables(): Collection<SubTable> { ensure(); return subTablesByName.values }

    fun unresolvedSubTableLines(): List<Pair<String, SubTableLine>> { ensure(); return subUnresolved }

    fun declaredSubTableCounts(): DeclaredSubTableCounts { ensure(); return subDeclared }

    fun subTableCount(): Int { ensure(); return subTablesByName.size }

    fun subTableLineCount(): Int { ensure(); return subTablesByName.values.sumOf { it.lines.size } }

    fun declaredCounts(): DeclaredCounts { ensure(); return declared }

    fun monsterCount(): Int { ensure(); return byName.size }

    fun dropLineCount(): Int { ensure(); return byName.values.sumOf { it.drops.size } }

    fun tableRefCount(): Int { ensure(); return byName.values.sumOf { m -> m.drops.count { it.isTableRef } } }

    fun npcIdCount(): Int { ensure(); return byNpcId.size }
}

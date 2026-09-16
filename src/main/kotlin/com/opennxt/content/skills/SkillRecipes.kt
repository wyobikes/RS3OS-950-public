package com.opennxt.content.skills

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.opennxt.api.stat.Stat
import com.opennxt.content.impl.SkillXpTable
import com.opennxt.content.impl.Smithing
import com.opennxt.resources.sqlite.RsDatabase
import mu.KotlinLogging

object SkillRecipes {
    private val logger = KotlinLogging.logger { }

    val enabled: Boolean get() = System.getProperty("opennxt.content.recipes") != "off"

    const val PROP_SKILL_KEY = 2640
    const val PROP_LEVEL = 2645
    const val PROP_XP_SKILL_KEY = 2696
    const val PROP_XP = 2697
    const val PROP_REQ_TYPE_1 = 2641
    const val PROP_REQ_VALUE_1 = 2646
    const val PROP_REQ_TYPE_2 = 2642
    const val PROP_REQ_VALUE_2 = 2647
    const val PROP_TOOL_1 = 2650
    const val PROP_TOOL_2 = 2651
    const val PROP_PRODUCT_COUNT = 2653
    const val PROP_PROGRESS = 7801
    val PROP_MATERIAL_ITEMS = intArrayOf(2655, 2656, 2657, 2658)
    val PROP_MATERIAL_COUNTS = intArrayOf(2665, 2666, 2667, 2668)
    val PROP_MATERIAL_STRUCTS = intArrayOf(2675, 2676, 2677)

    const val SKILL_KEY_ENUM = 681

    const val DEFAULT_DIVISOR = 10

    val CANDIDATE_DIVISORS: List<Int> = listOf(1, 2, 5, 10, 20, 50, 100, 200, 500, 1000, 2000, 5000, 10000, 20000)

    const val AGREEMENT_TOLERANCE = 0.02

    const val MIN_AGREEING = 3

    data class Material(val itemId: Int, val count: Int)

    data class Recipe(
        val productId: Int,
        val productName: String?,
        val skillKey: Int,
        val stat: Stat,
        val level: Int,
        val xpStat: Stat,
        val xpRaw: Int,
        val productCount: Int,
        val tools: List<Int>,
        val materials: List<Material>,
        val extraSkillLevels: List<Pair<Stat, Int>>,
        val otherRequirementTypes: List<Int>,
        val progress: Int?
    ) {
        val materialIds: Set<Int> get() = materials.mapTo(LinkedHashSet()) { it.itemId }
        override fun toString() =
            "Recipe(${productCount}x $productId '$productName' ${stat.name} $level <- " +
                materials.joinToString { "${it.count}x ${it.itemId}" } +
                (if (tools.isNotEmpty()) " tool $tools" else "") + " xpRaw $xpRaw)"
    }

    data class Scale(val stat: Stat, val divisor: Int, val matched: Int, val agreeing: Int, val source: String)

    class Table(
        val recipes: List<Recipe>,
        val byProduct: Map<Int, Recipe>,
        val byMaterial: Map<Int, List<Recipe>>,
        val byStat: Map<Stat, List<Recipe>>,
        val scales: Map<Stat, Scale>,
        val unmappedSkillKeys: Map<Int, Int>,
        val unresolvedStructRecipes: Int,
        val productDivisors: Map<Int, Int> = emptyMap()
    ) {
        companion object {
            val EMPTY = Table(emptyList(), emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptyMap(), 0)
        }
    }

    @Volatile
    private var cached: Table? = null

    val table: Table
        get() {
            if (!enabled) return Table.EMPTY
            cached?.let { return it }
            return synchronized(this) { cached ?: build().also { cached = it } }
        }

    internal fun invalidate() = synchronized(this) { cached = null }

    fun recipeFor(productId: Int): Recipe? = table.byProduct[productId]
    fun recipesUsing(itemId: Int): List<Recipe> = table.byMaterial[itemId] ?: emptyList()
    fun recipesFor(stat: Stat): List<Recipe> = table.byStat[stat] ?: emptyList()

    fun recipesUsingBoth(a: Int, b: Int): List<Recipe> =
        if (a == b) recipesUsing(a).filter { r -> r.materials.any { it.itemId == a && it.count >= 2 } || r.tools.contains(a) }
        else recipesUsing(a).filter { b in it.materialIds || b in it.tools } + recipesUsing(b).filter { a in it.tools && a !in it.materialIds }

    fun divisorFor(stat: Stat): Int = table.scales[stat]?.divisor ?: DEFAULT_DIVISOR

    fun xpOf(recipe: Recipe): Double =
        recipe.xpRaw.toDouble() / (table.productDivisors[recipe.productId] ?: divisorFor(recipe.xpStat))

    fun refName(stat: Stat): String = stat.name.lowercase().replaceFirstChar { it.uppercase() }

    private fun build(): Table {
        if (!RsDatabase.available) {
            logger.warn { "skill recipes: rs3.sqlite not available; no production recipes loaded" }
            return Table.EMPTY
        }
        val keyToStatId = HashMap<Int, Int>()
        RsDatabase.queryAll("SELECT key, value FROM enum_entry WHERE enum_id = $SKILL_KEY_ENUM") { rs ->
            rs.getString(1) to rs.getString(2)
        }.forEach { (k, v) ->
            val key = k?.trim('"')?.toIntOrNull() ?: return@forEach
            val value = v?.trim('"')?.toIntOrNull() ?: return@forEach
            keyToStatId[key] = value
        }
        val statById = Stat.values().associateBy { it.id }
        fun statOfKey(key: Int): Stat? = keyToStatId[key]?.let { statById[it] }

        val structs = runCatching { Smithing.materialStructs() }.getOrElse {
            logger.warn(it) { "skill recipes: material structs failed to load; skipping struct-only recipes" }
            emptyMap()
        }

        val rows = RsDatabase.queryAll(
            "SELECT a.id, a.value, i.name FROM items_attr a LEFT JOIN items i ON i.id = a.id " +
                "WHERE a.field = 'extra' AND a.value LIKE '%\"prop\":$PROP_SKILL_KEY,%'"
        ) { rs -> Triple(rs.getInt(1), rs.getString(2), rs.getString(3)) }

        val recipes = ArrayList<Recipe>(rows.size)
        val unmapped = HashMap<Int, Int>()
        var unresolvedStruct = 0
        for ((id, json, name) in rows) {
            val props = parseInts(json) ?: continue
            val key = props[PROP_SKILL_KEY] ?: continue
            val stat = statOfKey(key)
            if (stat == null) {
                unmapped.merge(key, 1, Int::plus)
                continue
            }
            val merged = LinkedHashMap<Int, Int>()
            for (i in PROP_MATERIAL_ITEMS.indices) {
                val item = props[PROP_MATERIAL_ITEMS[i]] ?: continue
                if (item <= 0) continue
                val count = (props[PROP_MATERIAL_COUNTS[i]] ?: 1).let { if (it <= 0) 1 else it }
                merged.merge(item, count, Int::plus)
            }
            var brokenStruct = false
            for (p in PROP_MATERIAL_STRUCTS) {
                val structId = props[p] ?: continue
                val m = structs[structId]
                if (m == null) { brokenStruct = true; continue }
                merged.merge(m.itemId, m.count, Int::plus)
            }
            if (brokenStruct && merged.isEmpty()) {
                unresolvedStruct++
                continue
            }
            val extraSkills = ArrayList<Pair<Stat, Int>>()
            val otherTypes = ArrayList<Int>()
            for ((tp, vp) in listOf(PROP_REQ_TYPE_1 to PROP_REQ_VALUE_1, PROP_REQ_TYPE_2 to PROP_REQ_VALUE_2)) {
                val type = props[tp] ?: continue
                val value = props[vp] ?: 0
                val s = if (type in 1..29) statOfKey(type) else null
                if (s != null) extraSkills += s to value else otherTypes += type
            }
            recipes += Recipe(
                productId = id,
                productName = name,
                skillKey = key,
                stat = stat,
                level = props[PROP_LEVEL] ?: 1,
                xpStat = props[PROP_XP_SKILL_KEY]?.let { statOfKey(it) } ?: stat,
                xpRaw = props[PROP_XP] ?: 0,
                productCount = (props[PROP_PRODUCT_COUNT] ?: 1).coerceAtLeast(1),
                tools = listOfNotNull(props[PROP_TOOL_1], props[PROP_TOOL_2]).filter { it > 0 },
                materials = merged.map { (item, count) -> Material(item, count) },
                extraSkillLevels = extraSkills,
                otherRequirementTypes = otherTypes,
                progress = props[PROP_PROGRESS]
            )
        }

        val byProduct = recipes.associateBy { it.productId }
        val byMaterial = HashMap<Int, MutableList<Recipe>>()
        for (r in recipes) for (m in r.materials) byMaterial.getOrPut(m.itemId) { ArrayList() }.add(r)
        val byStat = recipes.groupBy { it.stat }
        val scales = LinkedHashMap<Stat, Scale>()
        for (stat in recipes.map { it.xpStat }.toSortedSet()) {
            scales[stat] = deriveScale(stat, recipes.filter { it.xpStat == stat })
        }
        val productDivisors = HashMap<Int, Int>()
        for (stat in scales.keys) {
            val skillDivisor = scales[stat]!!.divisor
            val refEntry = refXpByName(stat)
            for (r in recipes) {
                if (r.xpStat != stat || r.xpRaw <= 0) continue
                val tenths = refEntry[r.productName?.lowercase() ?: continue] ?: continue
                val ratio = r.xpRaw / (tenths / 10.0)
                if (Math.abs(ratio / skillDivisor - 1.0) <= AGREEMENT_TOLERANCE) continue
                val own = CANDIDATE_DIVISORS.firstOrNull { Math.abs(ratio / it - 1.0) <= AGREEMENT_TOLERANCE } ?: continue
                productDivisors[r.productId] = own
            }
        }
        val table = Table(recipes, byProduct, byMaterial, byStat, scales, unmapped, unresolvedStruct, productDivisors)
        logger.info {
            "skill recipes: ${recipes.size} cache recipes over ${byStat.size} skills (" +
                byStat.entries.sortedByDescending { it.value.size }.joinToString { "${it.key.name} ${it.value.size}" } +
                "); xp units " + scales.values.joinToString { "${it.stat.name} /${it.divisor} ${it.source} ${it.agreeing}/${it.matched}" } +
                (if (unmapped.isNotEmpty()) "; unmapped skill keys $unmapped" else "") +
                (if (unresolvedStruct > 0) "; $unresolvedStruct struct-only recipe(s) unresolved" else "")
        }
        return table
    }

    private fun refXpByName(stat: Stat): Map<String, Int> {
        val refEntry = HashMap<String, Int>()
        if (SkillXpTable.enabled) {
            SkillXpTable.seed.skills[refName(stat)]?.values?.forEach { rows ->
                for (row in rows) if (row.xpTenths > 0) refEntry.putIfAbsent(row.name.lowercase(), row.xpTenths)
            }
        }
        return refEntry
    }

    internal fun deriveScale(stat: Stat, recipes: List<Recipe>): Scale {
        val refEntry = refXpByName(stat)
        val ratios = ArrayList<Double>()
        for (r in recipes) {
            if (r.xpRaw <= 0) continue
            val tenths = refEntry[r.productName?.lowercase() ?: continue] ?: continue
            ratios += r.xpRaw / (tenths / 10.0)
        }
        if (ratios.isEmpty()) return Scale(stat, DEFAULT_DIVISOR, 0, 0, "DEFAULT (no xp table match)")
        var best = DEFAULT_DIVISOR
        var bestAgree = -1
        for (c in CANDIDATE_DIVISORS) {
            val agree = ratios.count { Math.abs(it / c - 1.0) <= AGREEMENT_TOLERANCE }
            if (agree > bestAgree) { best = c; bestAgree = agree }
        }
        return if (bestAgree >= MIN_AGREEING && bestAgree * 2 >= ratios.size)
            Scale(stat, best, ratios.size, bestAgree, "DERIVED (xp table)")
        else Scale(stat, DEFAULT_DIVISOR, ratios.size, bestAgree.coerceAtLeast(0), "DEFAULT (xp table matches do not agree)")
    }

    private fun parseInts(json: String?): Map<Int, Int>? {
        if (json == null) return null
        return runCatching {
            val out = HashMap<Int, Int>()
            for (el in JsonParser().parse(json).asJsonArray) {
                val o = el as? JsonObject ?: continue
                val iv = o.get("intvalue") ?: continue
                if (iv.isJsonNull) continue
                out[o.get("prop").asInt] = iv.asInt
            }
            out
        }.getOrNull()
    }
}

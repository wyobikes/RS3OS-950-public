package com.opennxt.content.impl

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.opennxt.Constants
import mu.KotlinLogging
import java.nio.file.Files

object SkillXpTable {
    private val logger = KotlinLogging.logger {}

    val enabled: Boolean get() = System.getProperty("opennxt.seed.skillxp") != "off"

    data class Row(
        val skill: String,
        val category: String,
        val name: String,
        val level: Int?,
        val xpTenths: Int,
        val source: String
    )

    class Seed(val skills: Map<String, Map<String, List<Row>>>, val revisions: Map<String, Int>) {
        val rowCount: Int get() = skills.values.sumOf { cats -> cats.values.sumOf { it.size } }
        val rowsWithLevelAndXp: Int get() = skills.values.sumOf { cats -> cats.values.sumOf { rows -> rows.count { it.level != null } } }
    }

    private val seedPath = Constants.DATA_PATH.resolve("seed").resolve("skill_xp.json")
    private val chancePath = Constants.DATA_PATH.resolve("seed").resolve("skill_chance.json")

    val seed: Seed by lazy { load() }

    val trees: Map<String, Row> by lazy { loadTrees() }

    data class TreeChance(val logs: String, val name: String, val chance: Double, val ratio: Int, val fell: Int)
    val treeChance: Map<String, TreeChance> by lazy { loadTreeChance() }

    data class Hatchet(val item: String, val level: Int, val power: Int)
    val hatchets: Map<String, Hatchet> by lazy { loadHatchets() }

    private fun loadTreeChance(): Map<String, TreeChance> {
        if (!Files.isRegularFile(chancePath)) return emptyMap()
        val root = JsonParser().parse(Files.newBufferedReader(chancePath)).asJsonObject
        val trees = root.getAsJsonObject("trees") ?: return emptyMap()
        val out = LinkedHashMap<String, TreeChance>()
        for (t in trees.getAsJsonArray("data")) {
            val o = t as? JsonObject ?: continue
            val logs = o.get("logs")?.takeIf { it.isJsonPrimitive }?.asString ?: continue
            val chance = o.get("chance")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asDouble ?: continue
            val ratio = o.get("ratio")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asInt ?: 31250
            val fell = o.get("fell")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asInt ?: -1
            if (logs !in out) out[logs] = TreeChance(logs, o.get("name")?.asString ?: logs, chance, ratio, fell)
        }
        return out
    }

    private fun loadHatchets(): Map<String, Hatchet> {
        if (!Files.isRegularFile(chancePath)) return emptyMap()
        val root = JsonParser().parse(Files.newBufferedReader(chancePath)).asJsonObject
        val data = root.getAsJsonObject("hatchets")?.getAsJsonObject("data") ?: return emptyMap()
        val all = data.get("hatchets.all_hatchets") ?: return emptyMap()
        val entries = if (all.isJsonArray) all.asJsonArray.toList() else all.asJsonObject.entrySet().map { it.value }
        val out = LinkedHashMap<String, Hatchet>()
        for (e in entries) {
            val o = e as? JsonObject ?: continue
            val label = o.get("label")?.asString ?: continue
            val power = o.get("power")?.asInt ?: continue
            val level = o.get("level")?.asInt ?: 1
            val item = o.get("image")?.takeIf { it.isJsonPrimitive }?.asString ?: "$label hatchet"
            out[item] = Hatchet(item, level, power)
        }
        return out
    }

    fun jagexInterpolate(low: Long, high: Long, level: Int): Long =
        Math.floorDiv(low * (99 - level), 98L) + Math.floorDiv(high * (level - 1), 98L)

    fun woodcuttingChance(logs: String, hatchetItem: String, level: Int): Double? {
        val tree = treeChance[logs] ?: return null
        val hatchet = hatchets[hatchetItem] ?: return null
        val c = Math.round(tree.chance * 10000)
        val tierIncrement = c / 2
        val low = c + Math.floorDiv(tierIncrement * (hatchet.power - 100), 100L)
        val high = Math.floorDiv(low * tree.ratio, 10000L)
        val raw = (jagexInterpolate(low, high, level) + 1).toDouble() / (256.0 * 10000.0)
        return raw.coerceIn(0.0, 1.0)
    }

    data class FishChance(
        val item: String, val name: String, val level: Int, val low: Int, val high: Int,
        val tool: String?, val spot: String?, val xpTenths: Int, val delayTicks: Int
    )

    val fish: Map<String, FishChance> by lazy { loadFish() }

    private fun loadFish(): Map<String, FishChance> {
        if (!Files.isRegularFile(chancePath)) return emptyMap()
        val root = JsonParser().parse(Files.newBufferedReader(chancePath)).asJsonObject
        val block = root.getAsJsonObject("fishing") ?: return emptyMap()
        val out = LinkedHashMap<String, FishChance>()
        for (e in block.getAsJsonArray("data") ?: return emptyMap()) {
            val o = e as? JsonObject ?: continue
            val item = o.get("image")?.takeIf { it.isJsonPrimitive }?.asString ?: continue
            val low = o.get("low")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asInt ?: continue
            val high = o.get("high")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asInt ?: continue
            val level = o.get("level")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asInt ?: 1
            val xp = o.get("xp")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asDouble ?: 0.0
            val delay = o.get("delay")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asInt ?: 4
            if (item !in out) out[item] = FishChance(
                item, o.get("name")?.asString ?: item, level, low, high,
                o.get("tool")?.takeIf { it.isJsonPrimitive }?.asString,
                o.get("spot")?.takeIf { it.isJsonPrimitive }?.asString,
                tenths(xp), delay
            )
        }
        return out
    }

    fun fishingInterpolate(low: Long, high: Long, level: Int): Long =
        Math.floorDiv((99L - level) * low + (level - 1L) * high, 98L)

    fun fishingChance(item: String, level: Int): Double? {
        val f = fish[item] ?: return null
        return (fishingInterpolate(f.low.toLong(), f.high.toLong(), level).toDouble() / 256.0).coerceIn(0.0, 1.0)
    }

    fun fishCount(): Int = fish.size

    fun fellChance(logs: String): Double? {
        val t = treeChance[logs] ?: return null
        if (t.fell < 0) return null
        return t.fell / 256.0
    }

    private fun tenths(x: Double): Int = Math.round(x * 10.0).toInt()

    private fun load(): Seed {
        if (!Files.isRegularFile(seedPath)) {
            logger.warn { "skilling xp seed absent: $seedPath - the TABLE layer is empty" }
            return Seed(emptyMap(), emptyMap())
        }
        val root = JsonParser().parse(Files.newBufferedReader(seedPath)).asJsonObject
        val skills = LinkedHashMap<String, Map<String, List<Row>>>()
        val revisions = LinkedHashMap<String, Int>()
        for ((skill, v) in root.getAsJsonObject("skills").entrySet()) {
            val o = v.asJsonObject
            val prov = o.getAsJsonObject("provenance")
            val url = prov?.get("url")?.asString ?: "seed"
            prov?.get("revid")?.takeIf { it.isJsonPrimitive }?.asInt?.let { revisions[skill] = it }
            val cats = LinkedHashMap<String, List<Row>>()
            for ((cat, rowsEl) in o.getAsJsonObject("categories").entrySet()) {
                val rows = ArrayList<Row>()
                for (r in rowsEl.asJsonArray) {
                    if (!r.isJsonObject) continue
                    val ro = r.asJsonObject
                    val xp = ro.get("xp")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asDouble ?: continue
                    val level = ro.get("level")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asInt
                    val name = ro.get("name")?.takeIf { it.isJsonPrimitive }?.asString ?: continue
                    rows += Row(skill, cat, name, level, tenths(xp), "skill_xp.json $cat")
                }
                cats[cat] = rows
            }
            skills[skill] = cats
        }
        logger.info { "skilling xp seed: ${skills.size} skills, ${skills.values.sumOf { c -> c.values.sumOf { it.size } }} rows from $seedPath" }
        return Seed(skills, revisions)
    }

    private fun loadTrees(): Map<String, Row> {
        if (!Files.isRegularFile(chancePath)) return emptyMap()
        val root = JsonParser().parse(Files.newBufferedReader(chancePath)).asJsonObject
        val trees = root.getAsJsonObject("trees") ?: return emptyMap()
        val url = trees.getAsJsonObject("provenance")?.get("url")?.asString ?: "seed"
        val rev = trees.getAsJsonObject("provenance")?.get("revid")?.asInt
        val out = LinkedHashMap<String, Row>()
        for (t in trees.getAsJsonArray("data")) {
            val o = t as? JsonObject ?: continue
            val logs = o.get("logs")?.takeIf { it.isJsonPrimitive }?.asString ?: continue
            val xp = o.get("xp")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asDouble ?: continue
            val level = o.get("level")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asInt
            if (logs !in out) out[logs] = Row("Woodcutting", "TreeData", o.get("name")?.asString ?: logs, level, tenths(xp), "skill_chance.json")
        }
        return out
    }

    fun rows(skill: String, name: String): List<Row> =
        seed.skills[skill]?.values?.flatten()?.filter { it.name == name && it.level != null } ?: emptyList()

    fun requirementFor(kind: ResourceNodes.Kind, itemName: String): Row? = when (kind) {
        ResourceNodes.Kind.WOODCUTTING -> trees[itemName] ?: rows("Woodcutting", itemName).firstOrNull()
        ResourceNodes.Kind.MINING -> rows("Mining", itemName).firstOrNull()
        ResourceNodes.Kind.GATHERING -> null
    }

    fun skillCount(): Int = seed.skills.size
    fun rowCount(): Int = seed.rowCount
    fun rowsWithLevelAndXp(): Int = seed.rowsWithLevelAndXp
    fun rowCount(skill: String): Int = seed.skills[skill]?.values?.sumOf { rows -> rows.count { it.level != null } } ?: 0
}

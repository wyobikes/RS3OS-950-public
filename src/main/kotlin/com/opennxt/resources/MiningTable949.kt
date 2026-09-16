package com.opennxt.resources

import com.opennxt.Constants
import mu.KotlinLogging

object MiningTable949 {
    private val logger = KotlinLogging.logger { }

    data class Yield(
        val rock: String,
        val classId: Int,
        val itemId: Int,
        val itemName: String,
        val level: Int,
        val weight: Int,
        val suspect: Boolean,
        val ambiguous: Boolean,
    )

    private data class Loaded(val rows: List<Yield>, val levels: Map<String, Int>, val conflicts: Int)

    private val loaded: Loaded by lazy { load() }

    private fun load(): Loaded {
        val file = Constants.DATA_PATH.resolve("prot949").resolve("mining_949.tsv").toFile()
        if (!file.isFile) {
            logger.info {
                "MiningTable949: no ${file.path}; using built-in mining levels"
            }
            return Loaded(emptyList(), emptyMap(), 0)
        }
        val rows = ArrayList<Yield>()
        try {
            file.forEachLine { raw ->
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("#") || line.startsWith("class\t")) return@forEachLine
                val p = line.split('\t')
                if (p.size < 9) return@forEachLine
                val cls = p[0].toIntOrNull() ?: return@forEachLine
                val item = p[3].toIntOrNull() ?: return@forEachLine
                val lvl = p[5].toIntOrNull() ?: return@forEachLine
                rows.add(
                    Yield(
                        rock = p[1], classId = cls, itemId = item, itemName = p[4],
                        level = lvl, weight = p[6].toIntOrNull() ?: 0,
                        suspect = p[7] == "1", ambiguous = p[8] == "1",
                    )
                )
            }
        } catch (e: Exception) {
            logger.warn(e) { "MiningTable949: could not read ${file.path}; mining levels stay at the built-in defaults" }
            return Loaded(emptyList(), emptyMap(), 0)
        }

        val byItem = HashMap<String, MutableSet<Int>>()
        for (r in rows) {
            if (r.itemName.isBlank()) continue
            byItem.getOrPut(r.itemName) { HashSet() }.add(r.level)
        }
        val levels = HashMap<String, Int>()
        var conflicts = 0
        for ((name, lv) in byItem) {
            if (lv.size == 1) {
                levels[name] = lv.first()
            } else {
                conflicts++
                logger.info {
                    "MiningTable949: '$name' has conflicting levels ${lv.sorted().joinToString("/")}; skipped"
                }
            }
        }
        logger.info {
            "MiningTable949: ${rows.size} yield rows over ${rows.map { it.classId }.toSet().size} rock " +
                "classes; ${levels.size} item levels" +
                (if (conflicts > 0) ", $conflicts conflicting" else "")
        }
        return Loaded(rows, levels, conflicts)
    }

    fun rows(): List<Yield> = loaded.rows

    fun levelForItem(itemName: String): Int? = loaded.levels[itemName]

    fun levels(): Map<String, Int> = loaded.levels

    fun levelsByRock(): Map<String, Int> {
        val byName = HashMap<String, MutableSet<Int>>()
        for (r in loaded.rows) {
            if (r.rock.isBlank()) continue
            byName.getOrPut(r.rock) { HashSet() }.add(r.level)
        }
        val out = HashMap<String, Int>()
        for ((name, lv) in byName) if (lv.size == 1) out[name] = lv.first()
        return out
    }

    fun forRock(rock: String): List<Yield> = loaded.rows.filter { it.rock.equals(rock, ignoreCase = true) }

    fun size(): Int = loaded.rows.size
    fun conflictCount(): Int = loaded.conflicts
}

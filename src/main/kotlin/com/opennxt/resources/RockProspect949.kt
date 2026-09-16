package com.opennxt.resources

import com.opennxt.Constants
import mu.KotlinLogging

object RockProspect949 {
    private val logger = KotlinLogging.logger { }

    data class Rock(
        val loc: Int,
        val name: String,
        val level: Int,
        val hitpoints: Int,
        val hardness: Int,
        val xpMultiplier: Int,
    )

    private val loaded: List<Rock> by lazy { load() }

    private fun load(): List<Rock> {
        val file = Constants.DATA_PATH.resolve("prot949").resolve("rock_prospect_949.tsv").toFile()
        if (!file.isFile) {
            logger.info {
                "RockProspect949: no ${file.path}. Rock hitpoints/hardness are unavailable; " +
                    "add rows to the file to enable them."
            }
            return emptyList()
        }
        val rows = ArrayList<Rock>()
        try {
            file.forEachLine { raw ->
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("#") || line.startsWith("loc\t")) return@forEachLine
                val p = line.split('\t')
                if (p.size < 6) return@forEachLine
                val loc = p[0].toIntOrNull() ?: return@forEachLine
                val lvl = p[2].toIntOrNull() ?: return@forEachLine
                val hp = p[3].toIntOrNull() ?: return@forEachLine
                val hard = p[4].toIntOrNull() ?: return@forEachLine
                val mult = p[5].toIntOrNull() ?: return@forEachLine
                rows.add(Rock(loc = loc, name = p[1], level = lvl, hitpoints = hp,
                              hardness = hard, xpMultiplier = mult))
            }
        } catch (e: Exception) {
            logger.warn(e) { "RockProspect949: could not read ${file.path}" }
            return emptyList()
        }
        logger.info { "RockProspect949: ${rows.size} rock(s) loaded from the Prospect table" }
        return rows
    }

    fun all(): List<Rock> = loaded

    fun byLoc(loc: Int): Rock? = loaded.firstOrNull { it.loc == loc }

    fun byName(name: String): Rock? = loaded.firstOrNull { it.name.equals(name, ignoreCase = true) }

    fun agreementWithCache(): Pair<List<String>, List<String>> {
        val levels = MiningTable949.levelsByRock()
        val agree = ArrayList<String>()
        val differ = ArrayList<String>()
        for (r in loaded) {
            val cached = levels[r.name] ?: continue
            if (cached == r.level) agree.add(r.name) else differ.add("${r.name} table=${r.level} cache=$cached")
        }
        return agree to differ
    }

    fun describe(): String =
        if (loaded.isEmpty()) "RockProspect949: empty"
        else loaded.joinToString("; ") { "${it.name} lvl${it.level} hp${it.hitpoints} hard${it.hardness} x${it.xpMultiplier}" }
}

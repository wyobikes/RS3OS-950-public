package com.opennxt.content.impl

import com.opennxt.resources.sqlite.RsDatabase
import com.opennxt.resources.sqlite.SqliteLocCodec
import mu.KotlinLogging

object ResourceNodes {
    private val logger = KotlinLogging.logger { }

    enum class Kind { WOODCUTTING, MINING, GATHERING }

    const val CHOP_DOWN = "Chop down"
    const val CHOP = "Chop"
    const val MINE = "Mine"

    const val CHOP_DOWN_HYPHEN = "Chop-down"

    fun isGatherAction(action: String): Boolean =
        action == CHOP_DOWN || action == CHOP_DOWN_HYPHEN || action == CHOP || action == MINE

    fun isChopAction(action: String): Boolean =
        action == CHOP_DOWN || action == CHOP_DOWN_HYPHEN || action == CHOP

    data class Resolved(
        val locId: Int,
        val locName: String?,
        val kind: Kind,
        val itemId: Int,
        val itemName: String,
        val candidates: List<Int>
    ) {
        val ambiguous: Boolean get() = candidates.size > 1
        override fun toString() =
            "Resolved(loc $locId '${locName}' -> $itemId '$itemName'" +
                (if (ambiguous) " [AMBIGUOUS, ${candidates.size} ids: $candidates, lowest wins]" else "") + ")"
    }

    sealed class Refusal {
        object NoDatabase : Refusal()
        data class UnknownLoc(val locId: Int) : Refusal()
        data class Unnamed(val locId: Int) : Refusal()
        data class NoMatchingItem(val locId: Int, val locName: String, val tried: List<String>) : Refusal() {
            override fun toString() =
                "NoMatchingItem(loc $locId '$locName': no item in the cache is named ${tried.joinToString(" or ") { "'$it'" }})"
        }
    }

    private val itemIdsByName: Map<String, List<Int>> by lazy {
        if (!RsDatabase.available) {
            emptyMap()
        } else {
            RsDatabase.queryAll("SELECT id, name FROM items WHERE name IS NOT NULL AND name != ''") {
                it.getString("name") to it.getInt("id")
            }.groupBy({ it.first }, { it.second })
                .mapValues { (_, ids) -> ids.sorted() }
                .also { logger.info { "skilling: indexed ${it.size} distinct item names" } }
        }
    }

    fun itemIdsNamed(name: String): List<Int> = itemIdsByName[name] ?: emptyList()

    internal fun woodcuttingCandidateNames(locName: String): List<String> {
        var base = locName.trim()
        for (suffix in listOf(" tree", " Tree")) {
            if (base.endsWith(suffix)) {
                base = base.substring(0, base.length - suffix.length)
                break
            }
        }
        val out = ArrayList<String>()
        out.add("$base logs")
        if (base == "Tree") out.add("Logs")
        return out
    }

    internal fun miningCandidateNames(locName: String): List<String> {
        var base = locName.trim()
        var stripped = false
        for (suffix in listOf(" rock", " rocks", " Rock", " Rocks")) {
            if (base.endsWith(suffix)) {
                base = base.substring(0, base.length - suffix.length)
                stripped = true
                break
            }
        }
        if (!stripped) {
            return if (base.endsWith(" ore")) listOf(base) else emptyList()
        }
        return listOf("$base ore", base)
    }

    internal fun gatheringCandidateNames(locName: String): List<String> {
        val base = locName.trim()
        return listOf(base)
    }

    fun resolve(locId: Int, kind: Kind): Any {
        if (!RsDatabase.available) return Refusal.NoDatabase
        val def = SqliteLocCodec.load(locId) ?: return Refusal.UnknownLoc(locId)
        val name = def.name ?: return Refusal.Unnamed(locId)
        val tried = when (kind) {
            Kind.WOODCUTTING -> woodcuttingCandidateNames(name)
            Kind.MINING -> miningCandidateNames(name)
            Kind.GATHERING -> gatheringCandidateNames(name)
        }
        for (candidate in tried) {
            val ids = itemIdsByName[candidate] ?: continue
            if (ids.isEmpty()) continue
            return Resolved(locId, name, kind, ids.first(), candidate, ids)
        }
        return Refusal.NoMatchingItem(locId, name, tried)
    }

    fun yieldOf(locId: Int, kind: Kind): Resolved? = resolve(locId, kind) as? Resolved

    fun levelFromCache(locId: Int): Int? {
        if (!RsDatabase.available) return null
        return cachedParam23[locId]
    }

    private val cachedParam23: Map<Int, Int> by lazy {
        if (!RsDatabase.available) {
            emptyMap()
        } else {
            val out = HashMap<Int, Int>()
            RsDatabase.queryAll("SELECT id, value FROM locs_attr WHERE field = 'extra'") {
                it.getInt("id") to (it.getString("value") ?: "")
            }.forEach { (id, json) ->
                val marker = "\"prop\":23,"
                var idx = json.indexOf(marker)
                while (idx >= 0) {
                    val valueKey = json.indexOf("\"intvalue\":", idx)
                    if (valueKey >= 0) {
                        val start = valueKey + "\"intvalue\":".length
                        val end = json.indexOfFirst(start) { c -> !c.isDigit() && c != '-' }
                        val text = json.substring(start, end)
                        text.toIntOrNull()?.let { v -> out[id] = v }
                    }
                    idx = json.indexOf(marker, idx + 1)
                }
            }
            logger.info { "skilling: ${out.size} locs carry the cache's level param (23)" }
            out
        }
    }

    private inline fun String.indexOfFirst(from: Int, predicate: (Char) -> Boolean): Int {
        var i = from
        while (i < length) {
            if (predicate(this[i])) return i
            i++
        }
        return length
    }

    fun locsDeclaring(action: String): List<Int> =
        com.opennxt.model.world.LocChanges.locsDeclaring(action)
}

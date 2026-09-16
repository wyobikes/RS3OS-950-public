package com.opennxt.resources.sqlite

import com.google.gson.JsonParser

object CacheEnums {
    data class Entry(val key: String, val value: String)

    enum class Source { ENUM_ENTRY, ATTR_ARRAY_1, NONE }

    private val memo = HashMap<Int, List<Entry>>()
    private val sourceMemo = HashMap<Int, Source>()

    @Synchronized
    fun entries(enumId: Int): List<Entry> = memo.getOrPut(enumId) {
        if (!RsDatabase.available) return@getOrPut emptyList()

        val flat = RsDatabase.queryAll(
            "SELECT key, value FROM enum_entry WHERE enum_id = ?", enumId
        ) { Entry(it.getString("key"), it.getString("value")) }
        if (flat.isNotEmpty()) {
            sourceMemo[enumId] = Source.ENUM_ENTRY
            return@getOrPut flat
        }

        for (field in listOf("intArrayValue1", "stringArrayValue1")) {
            val raw = RsDatabase.queryOne(
                "SELECT value FROM enums_attr WHERE id = ? AND field = '$field'", enumId
            ) { it.getString(1) } ?: continue
            val out = ArrayList<Entry>()
            for (el in JsonParser().parse(raw).asJsonArray) {
                val pair = el.asJsonArray
                if (pair.size() < 2) continue
                out.add(Entry(pair.get(0).asString, pair.get(1).asString))
            }
            if (out.isNotEmpty()) {
                sourceMemo[enumId] = Source.ATTR_ARRAY_1
                return@getOrPut out
            }
        }
        sourceMemo[enumId] = Source.NONE
        emptyList()
    }

    fun sourceOf(enumId: Int): Source {
        entries(enumId)
        return sourceMemo[enumId] ?: Source.NONE
    }

    fun intMap(enumId: Int): Map<Int, Int> {
        val out = LinkedHashMap<Int, Int>()
        for (e in entries(enumId)) {
            val k = e.key.toIntOrNull() ?: continue
            val v = e.value.toIntOrNull() ?: continue
            out[k] = v
        }
        return out
    }

    fun idsMissingFromEnumEntry(): List<Int> {
        if (!RsDatabase.available) return emptyList()
        return RsDatabase.queryAll(
            "SELECT DISTINCT id FROM enums_attr WHERE field IN ('intArrayValue1','stringArrayValue1') " +
                "AND id NOT IN (SELECT DISTINCT enum_id FROM enum_entry) ORDER BY id"
        ) { it.getInt(1) }
    }

    val PROVENANCE: String =
        "cache enums: reads enum_entry and falls back to enums_attr for enums stored as intArrayValue1/stringArrayValue1"
}

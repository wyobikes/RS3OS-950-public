package com.opennxt.content.impl

import com.opennxt.resources.sqlite.RsDatabase
import mu.KotlinLogging

object NpcHeadModels {
    private val logger = KotlinLogging.logger { }

    private val cache = HashMap<Int, Int?>()

    private val partCounts = HashMap<Int, Int>()

    @Synchronized
    fun firstHeadModel(npcId: Int): Int? = cache.getOrPut(npcId) {
        if (!RsDatabase.hasTable("npcs_attr")) return@getOrPut null
        val raw = RsDatabase.queryOne(
            "SELECT value FROM npcs_attr WHERE id = ? AND field = 'headModels'", npcId
        ) { it.getString("value") } ?: return@getOrPut null
        val ids = parse(raw)
        partCounts[npcId] = ids.size
        ids.firstOrNull()
    }

    @Synchronized
    fun partCount(npcId: Int): Int {
        firstHeadModel(npcId)
        return partCounts[npcId] ?: 0
    }

    fun parse(raw: String): List<Int> =
        Regex("-?\\d+").findAll(raw).mapNotNull { it.value.toIntOrNull() }.toList()

    @Synchronized
    internal fun clear() {
        cache.clear(); partCounts.clear()
    }
}

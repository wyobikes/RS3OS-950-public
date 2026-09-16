package com.opennxt.model.combat

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import mu.KotlinLogging
import java.nio.file.Files
import java.nio.file.Path

object NpcBossData {
    private val logger = KotlinLogging.logger { }

    const val FILE = "npc_bosses.json"

    const val SCHEMA = "opennxt.seed.npc_bosses/1"

    data class Sourced<T>(val value: T, val provenance: String, val detail: String)

    data class RefusedId(val npcId: Int, val reason: String)

    data class PhaseEvidence(val percent: Double, val where: String, val revid: Int?, val sentence: String)

    data class LifepointsDisagreement(val npcId: Int, val values: Map<String, Int>, val winnerLayer: String)

    data class Respawn(val ticks: Int, val source: String, val detail: String)

    data class Boss(
        val name: String,
        val source: String,
        val pageRevid: Int?,
        val strategiesRevid: Int?,
        val tier: String,
        val tierDerived: String,
        val tierOverride: Map<String, String>?,
        val entry: String,
        val instanced: Boolean,
        val notBuilt: List<String>,
        val npcIds: List<Int>,
        val idsRefused: List<RefusedId>,
        val names: Map<Int, String?>,
        val lifepointsById: Map<Int, Int>,
        val lifepointsLayerById: Map<Int, String>,
        val lifepointsAllLayers: Map<Int, Map<String, Int>>,
        val lifepointsDisagreements: List<LifepointsDisagreement>,
        val combatLevelById: Map<Int, Int>,
        val attackSpeedById: Map<Int, Int>,
        val hasDrops: Boolean,
        val dropIds: List<Int>,
        val specialMaxHitById: Map<Int, Int>,
        val specialCadenceById: Map<Int, Int>,
        val phases: List<Double>,
        val phaseSource: String?,
        val phaseEvidence: List<PhaseEvidence>,
        val phaseCacheRefused: String?,
        val respawnById: Map<Int, Respawn>,
        val placement: String,
        val placementReason: String,
        val spawnRowCount: Int
    ) {
        val placed: Boolean get() = placement == "spawn_layer"
        override fun toString() = "$name [$tier, $entry, $placement, ids=$npcIds]"
    }

    private val bosses = ArrayList<Boss>()
    private val byNpcId = HashMap<Int, Boss>()
    private val byLowerName = HashMap<String, Boss>()
    private val counts = LinkedHashMap<String, Int>()
    private val refusedBosses = ArrayList<Pair<String, String>>()

    var loaded: Boolean = false
        private set
    var retrieved: String? = null
        private set

    var defaultRespawnTicks: Int = 0
        private set

    var specialEverySwings: Int = 0
        private set

    @Synchronized
    fun load(dir: Path = SeedData.seedDir): NpcBossData {
        bosses.clear(); byNpcId.clear(); byLowerName.clear(); counts.clear(); refusedBosses.clear()
        retrieved = null; defaultRespawnTicks = 0; specialEverySwings = 0

        val p = dir.resolve(FILE)
        if (!Files.exists(p)) {
            logger.warn {
                "$p not found; no boss data loaded"
            }
            loaded = true
            return this
        }
        val root = Files.newBufferedReader(p).use { JsonParser().parse(it).asJsonObject }
        val schema = root.s("_schema")
        require(schema == SCHEMA) { "$p declares _schema=$schema; this loader reads $SCHEMA only" }
        retrieved = root.s("retrieved")
        specialEverySwings = root.i("special_every_swings") ?: 0
        defaultRespawnTicks = root.obj("boss_respawn_default")?.i("ticks") ?: 0
        root.obj("counts")?.entrySet()?.forEach { (k, v) ->
            if (v.isJsonPrimitive && v.asJsonPrimitive.isNumber) counts[k] = v.asInt
        }
        for (el in root.getAsJsonArray("refused") ?: JsonArray()) {
            val o = el.asJsonObject
            refusedBosses.add((o.s("boss") ?: "?") to (o.s("reason") ?: "unstated"))
        }
        for (el in root.getAsJsonArray("bosses") ?: JsonArray()) {
            val o = el.asJsonObject
            val ids = (o.getAsJsonArray("npc_ids") ?: JsonArray()).map { it.asInt }
            val special = o.obj("special")
            val phases = o.obj("phases")
            val boss = Boss(
                name = o.s("boss") ?: continue,
                source = o.s("source") ?: "",
                pageRevid = o.i("page_revid"),
                strategiesRevid = o.i("strategies_revid"),
                tier = o.s("tier") ?: "?",
                tierDerived = o.s("tier_derived") ?: "?",
                tierOverride = o.obj("tier_override")?.let { ov ->
                    ov.entrySet().associate { (k, v) -> k to (if (v.isJsonPrimitive) v.asString else v.toString()) }
                },
                entry = o.s("entry") ?: "open world",
                instanced = o.b("instanced") == true,
                notBuilt = (o.getAsJsonArray("not_built") ?: JsonArray()).map { it.asString },
                npcIds = ids,
                idsRefused = (o.getAsJsonArray("ids_refused") ?: JsonArray()).map {
                    val r = it.asJsonObject
                    RefusedId(r.i("npc_id") ?: -1, r.s("reason") ?: "unstated")
                },
                names = o.obj("names").intKeyed { if (it.isJsonNull) null else it.asString },
                lifepointsById = o.obj("lifepoints_by_id").intKeyedInt(),
                lifepointsLayerById = o.obj("lifepoints_layer_by_id").intKeyed { it.asString },
                lifepointsAllLayers = o.obj("lifepoints_all_layers").intKeyed { v ->
                    v.asJsonObject.entrySet().associate { (k, n) -> k to n.asInt }
                },
                lifepointsDisagreements = (o.getAsJsonArray("lifepoints_disagreements") ?: JsonArray()).map { d ->
                    val row = d.asJsonObject
                    LifepointsDisagreement(
                        row.i("npc_id") ?: -1,
                        row.obj("values")?.entrySet()?.associate { (k, v) -> k to v.asInt } ?: emptyMap(),
                        row.s("winner_layer") ?: "?")
                },
                combatLevelById = o.obj("combat_level_by_id").intKeyedInt(),
                attackSpeedById = o.obj("attack_speed_by_id").intKeyedInt(),
                hasDrops = o.b("has_drops") == true,
                dropIds = (o.getAsJsonArray("drop_ids") ?: JsonArray()).map { it.asInt },
                specialMaxHitById = special?.obj("max_hit_tenths_by_id")
                    .intKeyed { it.asInt / 10 } ?: emptyMap(),
                specialCadenceById = special?.obj("cadence_ticks_by_id").intKeyedInt(),
                phases = (phases?.getAsJsonArray("thresholds") ?: JsonArray()).map { it.asDouble },
                phaseSource = phases?.s("source"),
                phaseEvidence = (phases?.getAsJsonArray("evidence") ?: JsonArray()).map {
                    val e = it.asJsonObject
                    PhaseEvidence(e.d("percent") ?: 0.0, e.s("where") ?: "?", e.i("revid"), e.s("sentence") ?: "")
                },
                phaseCacheRefused = phases?.s("cache_refused"),
                respawnById = o.obj("respawn_by_id").intKeyed { v ->
                    val r = v.asJsonObject
                    Respawn(r.i("ticks") ?: 0, r.s("source") ?: "?", r.s("detail") ?: "")
                },
                placement = o.s("placement") ?: "unplaced",
                placementReason = o.s("placement_reason") ?: "",
                spawnRowCount = (o.getAsJsonArray("spawn_rows") ?: JsonArray()).size()
            )
            bosses.add(boss)
            byLowerName[boss.name.lowercase()] = boss
            for (id in boss.npcIds) byNpcId.putIfAbsent(id, boss)
        }
        loaded = true
        logger.info { bootLine() }
        return this
    }

    @Synchronized
    fun reset() {
        loaded = false
        bosses.clear(); byNpcId.clear(); byLowerName.clear(); counts.clear(); refusedBosses.clear()
        retrieved = null; defaultRespawnTicks = 0; specialEverySwings = 0
    }

    private fun ensure() { if (!loaded) load() }

    fun all(): List<Boss> { ensure(); return bosses }

    fun isBoss(gameId: Int): Boolean { ensure(); return byNpcId.containsKey(gameId) }

    fun bossFor(gameId: Int): Boss? { ensure(); return byNpcId[gameId] }

    fun byName(query: String): Boss? {
        ensure()
        val q = query.trim().lowercase()
        if (q.isEmpty()) return null
        byLowerName[q]?.let { return it }
        val hits = bosses.filter { it.name.lowercase().contains(q) }
        return if (hits.size == 1) hits[0] else null
    }

    fun matches(query: String, limit: Int = 6): List<Boss> {
        ensure()
        val q = query.trim().lowercase()
        return bosses.filter { it.name.lowercase().contains(q) }.sortedBy { it.name.length }.take(limit)
    }

    fun counts(): Map<String, Int> { ensure(); return counts }

    fun refused(): List<Pair<String, String>> { ensure(); return refusedBosses }

    fun respawnTicks(gameId: Int): Sourced<Int>? {
        ensure()
        val boss = byNpcId[gameId] ?: return null
        val row = boss.respawnById[gameId] ?: return null
        if (row.ticks <= 0) return null
        return Sourced(
            row.ticks,
            if (row.source == "combat_defs") "DOCUMENTED-DEFS" else "INVENTED-DERIVED",
            "${boss.name} [${boss.tier}] respawn ${row.ticks} ticks (${row.source}): ${row.detail}"
        )
    }

    fun specialMaxHit(gameId: Int): Sourced<Int>? {
        ensure()
        val boss = byNpcId[gameId] ?: return null
        val v = boss.specialMaxHitById[gameId] ?: return null
        return Sourced(v, "DOCUMENTED",
            "${boss.name}: special max hit for npc $gameId from reference data")
    }

    fun specialCadenceTicks(gameId: Int): Int? {
        ensure()
        return byNpcId[gameId]?.specialCadenceById?.get(gameId)
    }

    fun phases(gameId: Int): List<Double> {
        ensure()
        return byNpcId[gameId]?.phases ?: emptyList()
    }

    fun placedCountsByTier(): Map<String, Int> {
        ensure()
        val m = LinkedHashMap<String, Int>()
        for (t in listOf("T1", "T2", "T3", "T4")) m[t] = 0
        for (b in bosses) if (b.placed) m[b.tier] = (m[b.tier] ?: 0) + 1
        return m
    }

    fun placed(): List<Boss> { ensure(); return bosses.filter { it.placed } }

    fun bootLine(): String {
        ensure()
        val placed = placedCountsByTier()
        val tiers = bosses.groupingBy { it.tier }.eachCount().toSortedMap()
        return "boss layer: bosses=${bosses.size}, npc_ids=${byNpcId.size}, refused=${refusedBosses.size}, " +
            "tiers=${tiers.entries.joinToString(",") { "${it.key}=${it.value}" }}; " +
            "placed=${bosses.count { it.placed }} " +
            "(${placed.entries.joinToString(",") { "${it.key}=${it.value}" }}), " +
            "unplaced=${bosses.count { !it.placed }}, " +
            "specials=${bosses.count { it.specialMaxHitById.isNotEmpty() }}, " +
            "phases=${bosses.count { it.phases.isNotEmpty() }}, " +
            "drops=${bosses.count { it.hasDrops }}, default respawn=${defaultRespawnTicks} ticks"
    }

    private fun JsonObject.obj(k: String): JsonObject? {
        if (!has(k)) return null
        val v = get(k)
        return if (v.isJsonObject) v.asJsonObject else null
    }

    private fun JsonObject.i(k: String): Int? =
        if (has(k) && !get(k).isJsonNull && get(k).isJsonPrimitive) runCatching { get(k).asInt }.getOrNull() else null

    private fun JsonObject.d(k: String): Double? =
        if (has(k) && !get(k).isJsonNull && get(k).isJsonPrimitive) runCatching { get(k).asDouble }.getOrNull() else null

    private fun JsonObject.s(k: String): String? =
        if (has(k) && !get(k).isJsonNull && get(k).isJsonPrimitive) get(k).asString else null

    private fun JsonObject.b(k: String): Boolean? =
        if (has(k) && !get(k).isJsonNull && get(k).isJsonPrimitive) runCatching { get(k).asBoolean }.getOrNull() else null

    private fun <T> JsonObject?.intKeyed(f: (com.google.gson.JsonElement) -> T): Map<Int, T> {
        if (this == null) return emptyMap()
        val m = LinkedHashMap<Int, T>()
        for ((k, v) in entrySet()) k.toIntOrNull()?.let { m[it] = f(v) }
        return m
    }

    private fun JsonObject?.intKeyedInt(): Map<Int, Int> = intKeyed { it.asInt }
}

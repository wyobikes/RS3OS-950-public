package com.opennxt.model.world

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.opennxt.model.combat.SeedData
import mu.KotlinLogging
import java.nio.file.Files
import java.nio.file.Path

object WorldSpawns {
    private val logger = KotlinLogging.logger { }

    const val FILE = "npc_spawns.json"

    const val SCHEMA = "opennxt.seed.npc_spawns/1"

    data class Row(
        val npcId: Int,
        val name: String?,
        val x: Int,
        val y: Int,
        val plane: Int,
        val source: String,
        val planeSource: String,
        val squareId: Int,
        val terrainBlocked: Boolean,
        val inCacheSpawnSquare: Boolean,
        val surfaceMap: Boolean,
        val sourcePage: String?,
        val loc: String?,
        val confidence: String?,
        val sampledRule: String?,
        val sampledRadius: Int
    ) {
        val isReference: Boolean get() = source == "ref"
        val isSampled: Boolean get() = source == "sampled"
        val activatable: Boolean get() = source != "cache"
        val tile: TileLocation get() = TileLocation(x, y, plane)
        override fun toString() =
            "${name ?: "npc$npcId"}($npcId) @ ($x,$y,$plane) [$source${confidence?.let { "/$it" } ?: ""}]"
    }

    data class Refusal(val page: String?, val token: String?, val reason: String)

    private var loadedFlag = false
    val loaded: Boolean get() = loadedFlag

    private var rows: List<Row> = emptyList()
    private var bySquarePlane: Map<Long, List<Row>> = emptyMap()
    private var squareSet: Set<Long> = emptySet()
    private var refusalList: List<Refusal> = emptyList()
    private val headerCounts = LinkedHashMap<String, Int>()

    var retrieved: String? = null
        private set

    var densestBlockRows: Int = 0
        private set
    var densestBlockCentreX: Int = 0
        private set
    var densestBlockCentreY: Int = 0
        private set

    fun key(squareId: Int, plane: Int): Long = (squareId.toLong() shl 8) or plane.toLong()

    fun squareOf(x: Int, y: Int): Int = (x / 64) or ((y / 64) shl 7)

    @Synchronized
    fun load(dir: Path = SeedData.seedDir): WorldSpawns {
        if (loadedFlag) return this
        rows = emptyList(); bySquarePlane = emptyMap(); squareSet = emptySet()
        refusalList = emptyList(); headerCounts.clear(); retrieved = null
        densestBlockRows = 0; densestBlockCentreX = 0; densestBlockCentreY = 0

        val p = dir.resolve(FILE)
        if (!Files.exists(p)) {
            logger.warn {
                "$p not found; only the cache's ${NpcSpawnData.MANIFEST_SQUARES}-square spawn layer is loaded"
            }
            loadedFlag = true
            return this
        }
        val root = Files.newBufferedReader(p).use { JsonParser().parse(it).asJsonObject }
        val schema = root.s("_schema")
        require(schema == SCHEMA) { "$p declares _schema=$schema; this loader reads $SCHEMA only" }
        retrieved = root.s("retrieved")
        root.getAsJsonObject("counts")?.entrySet()?.forEach { (k, v) ->
            if (v.isJsonPrimitive && v.asJsonPrimitive.isNumber) headerCounts[k] = v.asInt
        }
        root.getAsJsonObject("densest_3x3_square_block")?.let { d ->
            densestBlockRows = d.i("rows") ?: 0
            densestBlockCentreX = d.i("centre_tile_x") ?: 0
            densestBlockCentreY = d.i("centre_tile_y") ?: 0
        }
        val refusals = ArrayList<Refusal>()
        for (el in root.getAsJsonArray("refused") ?: JsonArray()) {
            val o = el.asJsonObject
            refusals.add(Refusal(o.s("page"), o.s("token"), o.s("reason") ?: "unstated"))
        }
        val list = ArrayList<Row>(root.getAsJsonArray("spawns")?.size() ?: 0)
        for (el in root.getAsJsonArray("spawns") ?: JsonArray()) {
            val o = el.asJsonObject
            val id = o.i("npc_id") ?: continue
            val x = o.i("x") ?: continue
            val y = o.i("y") ?: continue
            val plane = o.i("plane") ?: continue
            list.add(
                Row(
                    npcId = id, name = o.s("name"), x = x, y = y, plane = plane,
                    source = o.s("source") ?: "unstated",
                    planeSource = o.s("plane_source") ?: "unstated",
                    squareId = squareOf(x, y),
                    terrainBlocked = o.b("terrain_blocked") == true,
                    inCacheSpawnSquare = o.b("in_cache_spawn_square") == true,
                    surfaceMap = o.b("surface_map") == true,
                    sourcePage = o.s("source_page"),
                    loc = o.s("loc"),
                    confidence = o.s("confidence"),
                    sampledRule = o.s("sample_rule"),
                    sampledRadius = o.i("radius") ?: 0
                )
            )
        }
        val index = HashMap<Long, MutableList<Row>>()
        for (r in list) index.getOrPut(key(r.squareId, r.plane)) { ArrayList() }.add(r)
        rows = list
        bySquarePlane = index
        squareSet = index.keys.toSet()
        refusalList = refusals
        loadedFlag = true
        logger.info { bootLine() }
        return this
    }

    @Synchronized
    fun reset() {
        loadedFlag = false
        rows = emptyList(); bySquarePlane = emptyMap(); squareSet = emptySet()
        refusalList = emptyList(); headerCounts.clear(); retrieved = null
    }

    fun all(): List<Row> = rows

    fun count(): Int = rows.size

    fun spawnsInSquare(square: Int, plane: Int): List<Row> = bySquarePlane[key(square, plane)] ?: emptyList()

    fun activatableInSquare(square: Int, plane: Int): List<Row> =
        spawnsInSquare(square, plane).filter { it.activatable }

    fun squares(): Set<Long> = squareSet

    fun distinctSquares(): Int = rows.mapTo(HashSet()) { it.squareId }.size

    fun refused(): List<Refusal> = refusalList

    fun counts(): Map<String, Int> = headerCounts

    fun countOf(source: String): Int = rows.count { it.source == source }

    fun provenanceBreakdown(): Map<String, Int> = linkedMapOf(
        "rows" to rows.size,
        "cache" to rows.count { it.source == "cache" },
        "ref" to rows.count { it.isReference },
        "sampled" to rows.count { it.isSampled },
        "sampled_stationary" to rows.count { it.sampledRule == "held-tile" },
        "sampled_modal" to rows.count { it.sampledRule == "modal-tile" },
        "sampled_centroid" to rows.count { it.sampledRule == "centroid" },
        "activatable" to rows.count { it.activatable },
        "squares" to distinctSquares(),
        "square_planes" to squareSet.size,
        "npc_ids" to rows.mapTo(HashSet()) { it.npcId }.size,
        "terrain_blocked" to rows.count { it.terrainBlocked },
        "plane_defaulted" to rows.count { it.planeSource == "default" },
        "surface_map" to rows.count { it.surfaceMap },
        "refused" to refusalList.size
    )

    fun bootLine(): String =
        "world spawn layer: ${provenanceBreakdown().entries.joinToString(", ") { "${it.key}=${it.value}" }}" +
            "; densest 3x3 block $densestBlockRows rows at (${densestBlockCentreX},${densestBlockCentreY})"

    private fun JsonObject.i(k: String): Int? =
        if (has(k) && !get(k).isJsonNull && get(k).isJsonPrimitive) runCatching { get(k).asInt }.getOrNull() else null

    private fun JsonObject.s(k: String): String? =
        if (has(k) && !get(k).isJsonNull && get(k).isJsonPrimitive) get(k).asString else null

    private fun JsonObject.b(k: String): Boolean? =
        if (has(k) && !get(k).isJsonNull && get(k).isJsonPrimitive) runCatching { get(k).asBoolean }.getOrNull() else null
}

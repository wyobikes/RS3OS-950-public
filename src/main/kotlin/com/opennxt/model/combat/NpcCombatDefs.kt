package com.opennxt.model.combat

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import mu.KotlinLogging
import java.nio.file.Files
import java.nio.file.Path

object NpcCombatDefs {
    private val logger = KotlinLogging.logger { }

    const val FILE = "npc_combat_defs.json"

    enum class Style { MELEE, RANGE, MAGIC }

    data class Row(
        val npcId: Int, val name: String?, val path: String, val byName: Boolean, val admittedOn: String,
        val defenceAnim: Int?, val attackRangeRaw: Int?, val attackProjectile: Int?, val attackGfx: Int?,
        val style: Style?, val deathDelay: Int?, val respawnDelayRaw: Int?, val aggroDistanceRaw: Int?,
        val deaggroDistanceRaw: Int?, val maxDistFromSpawnRaw: Int?, val aggressive: Boolean?
    ) {
        val attackRange: Int? get() = attackRangeRaw?.takeIf { it >= 0 } ?: when (style) {
            Style.MELEE -> 0; Style.RANGE -> 7; Style.MAGIC -> 10; null -> null
        }
        val aggroDistance: Int? get() = aggroDistanceRaw?.takeIf { it > 0 }
            ?: when (style) { Style.MELEE -> 4; null -> null; else -> attackRange?.let { it - 2 } }
        val respawnDelay: Int? get() = respawnDelayRaw?.takeIf { it > 0 }
    }

    data class Value<T>(val value: T, val provenance: String, val detail: String)

    private val byId = HashMap<Int, Row>()
    private val counts = LinkedHashMap<String, Int>()
    var loaded: Boolean = false
        private set
    var retrieved: String? = null
        private set
    var commit: String? = null
        private set
    var rejected: Int = 0
        private set

    private fun JsonObject.i(k: String): Int? = if (has(k) && !get(k).isJsonNull && get(k).isJsonPrimitive) runCatching { get(k).asInt }.getOrNull() else null
    private fun JsonObject.s(k: String): String? = if (has(k) && !get(k).isJsonNull && get(k).isJsonPrimitive) get(k).asString else null
    private fun JsonObject.b(k: String): Boolean? = if (has(k) && !get(k).isJsonNull && get(k).isJsonPrimitive) runCatching { get(k).asBoolean }.getOrNull() else null

    @Synchronized
    fun load(dir: Path = SeedData.seedDir): NpcCombatDefs {
        byId.clear(); counts.clear(); retrieved = null; commit = null; rejected = 0
        val p = dir.resolve(FILE)
        if (!Files.exists(p)) {
            logger.warn { "$p not found; using default npc combat definitions" }
            loaded = true
            return this
        }
        val root = Files.newBufferedReader(p).use { JsonParser().parse(it).asJsonObject }
        retrieved = root.s("retrieved"); commit = root.s("source_commit")
        if (root.has("counts")) for ((k, v) in root.getAsJsonObject("counts").entrySet()) {
            if (v.isJsonPrimitive && v.asJsonPrimitive.isNumber) counts[k] = v.asInt
        }
        rejected = (root.getAsJsonArray("rejected") ?: JsonArray()).size()
        for (el in root.getAsJsonArray("npcs") ?: JsonArray()) {
            val o = el.asJsonObject
            val id = o.i("npc_id") ?: continue
            val style = when (o.s("attack_style")) { "MELEE" -> Style.MELEE; "RANGE" -> Style.RANGE; "MAGIC", "MAGE" -> Style.MAGIC; else -> null }
            byId[id] = Row(
                npcId = id, name = o.s("name"), path = o.s("path") ?: "?", byName = o.b("by_name") == true,
                admittedOn = o.s("admitted_on") ?: "?",
                defenceAnim = o.i("defence_anim"), attackRangeRaw = o.i("attack_range"),
                attackProjectile = o.i("attack_projectile"), attackGfx = o.i("attack_gfx"), style = style,
                deathDelay = o.i("death_delay"), respawnDelayRaw = o.i("respawn_delay"),
                aggroDistanceRaw = o.i("aggro_distance"), deaggroDistanceRaw = o.i("deaggro_distance"),
                maxDistFromSpawnRaw = o.i("max_dist_from_spawn"),
                aggressive = o.s("aggressiveness")?.let { it == "AGGRESSIVE" }
            )
        }
        loaded = true
        logger.info {
            "combat defs: ${byId.size} npc ids loaded, $rejected rejected; " +
                "${byId.values.count { it.defenceAnim != null }} block animations, " +
                "${byId.values.count { it.style == Style.RANGE || it.style == Style.MAGIC }} ranged/magic attackers"
        }
        return this
    }

    private fun ensure() { if (!loaded) load() }

    fun row(npcId: Int): Row? { ensure(); return byId[npcId] }
    fun ids(): Set<Int> { ensure(); return byId.keys }
    fun counts(): Map<String, Int> { ensure(); return counts }

    private fun <T> v(r: Row, value: T, what: String) =
        Value(value, "DOCUMENTED-DEFS", "combat defs ${r.path} (admitted on ${r.admittedOn}): $what")

    fun defenceAnimation(npcId: Int): Value<Int>? { ensure(); val r = byId[npcId] ?: return null; val a = r.defenceAnim ?: return null; return v(r, a, "defenceAnim $a") }

    fun attackRange(npcId: Int): Value<Int>? { ensure(); val r = byId[npcId] ?: return null; val a = r.attackRange ?: return null
        return v(r, a, if (r.attackRangeRaw != null && r.attackRangeRaw >= 0) "attackRange $a" else "attackRange default for ${r.style}: $a") }

    fun aggroDistance(npcId: Int): Value<Int>? { ensure(); val r = byId[npcId] ?: return null; val a = r.aggroDistance ?: return null
        return v(r, a, if (r.aggroDistanceRaw != null && r.aggroDistanceRaw > 0) "aggroDistance $a" else "aggroDistance default: $a") }

    fun respawnDelay(npcId: Int): Value<Int>? { ensure(); val r = byId[npcId] ?: return null; val d = r.respawnDelay ?: return null; return v(r, d, "respawnDelay $d ticks") }

    fun aggressive(npcId: Int): Boolean? { ensure(); return byId[npcId]?.aggressive }
    fun style(npcId: Int): Style? { ensure(); return byId[npcId]?.style }
}

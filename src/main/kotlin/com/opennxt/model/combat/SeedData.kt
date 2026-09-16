package com.opennxt.model.combat

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

enum class Provenance { CACHE, DOCUMENTED, AUTHORED }

data class SeededValue(
    val value: Int,
    val provenance: Provenance,
    val source: String,
    val retrieved: String? = null,
    val note: String? = null,
    val disambiguation: String? = null
) {
    override fun toString(): String =
        "$value [${provenance.name.lowercase()}: $source" +
            "]"
}

data class SeedConflict(
    val npcId: Int,
    val name: String,
    val field: String,
    val cache: SeededValue,
    val documented: SeededValue,
    val genuine: Boolean,
    val cacheValuesForName: List<Int>,
    val documentedValuesForName: List<Int>
) {
    val winner: SeededValue get() = cache
    override fun toString(): String =
        "npc $npcId ($name) $field: cache=${cache.value} documented=${documented.value} " +
            "-> cache wins " +
            (if (genuine) "[source disagreement: cache$cacheValuesForName vs reference$documentedValuesForName] "
             else "[name mapping overlap: cache$cacheValuesForName, reference$documentedValuesForName] ") +
            "(${documented.source})"
}

enum class LifepointsLayer { CACHE, REF_TABLE, PROFILE, DOCUMENTED, VARIANT, AUTHORED }

data class VariantPage(
    val name: String,
    val refPage: String?,
    val source: String,
    val retrieved: String?,
    val levelToLifepoints: Map<Int, List<Int>>,
    val npcIds: List<Int>
)

enum class VariantOutcome {
    RESOLVED,
    NOT_A_COMBAT_NPC,
    LEVEL_NOT_IN_VARIANTS,
    LEVEL_MULTI_VALUED
}

data class VariantResult(val outcome: VariantOutcome, val lifepoints: Int?, val level: Int?)

data class RefCombat(
    val defence: Int?, val attack: Int?, val ranged: Int?, val magic: Int?, val necromancy: Int?,
    val maxMelee: Int?, val maxRanged: Int?, val maxMagic: Int?, val maxNecromancy: Int?, val maxSpec: Int?,
    val accMelee: Int?, val accRanged: Int?, val accMagic: Int?, val accNecromancy: Int?,
    val affWeakness: Int?, val affMelee: Int?, val affRanged: Int?, val affMagic: Int?,
    val experience: Double?,
    val slayerLevel: Int?, val slayerXp: Double?, val slayerCategory: String?,
    val weakness: String?,
    val style: String?, val primaryStyle: String?,
    val aggressive: Boolean?, val aggressiveRaw: String?,
    val poisonous: Boolean?,
    val immuneToPoison: Boolean?, val immuneToStun: Boolean?, val immuneToDeflect: Boolean?, val immuneToDrain: Boolean?
)

object SeedData {
    private val cacheLp = HashMap<Int, SeededValue>()
    private val docLp = HashMap<Int, SeededValue>()
    private val authoredLp = HashMap<Int, SeededValue>()

    private val refLp = HashMap<Int, SeededValue>()
    private val refRefused = LinkedHashSet<Int>()
    private val refValuesOf = HashMap<Int, List<Int>>()
    private val refConflicts = ArrayList<SeedConflict>()
    private val profileLp = HashMap<Int, SeededValue>()
    private val refCounts = LinkedHashMap<String, Int>()
    private val refCombat = HashMap<Int, RefCombat>()
    private val refCross = HashMap<Int, Map<String, Boolean?>>()
    var refRetrieved: String? = null
        private set

    private val cacheCb = HashMap<Int, SeededValue>()
    private val docCb = HashMap<Int, SeededValue>()
    private val authoredCb = HashMap<Int, SeededValue>()

    private val names = HashMap<Int, String>()

    private val docNameOf = HashMap<Int, String>()
    private val docLpVariants = HashMap<String, List<Int>>()
    private val docCbVariants = HashMap<String, List<Int>>()
    private val cacheLpSetOf = HashMap<Int, List<Int>>()

    private val lpConflicts = ArrayList<SeedConflict>()
    private val cbConflicts = ArrayList<SeedConflict>()

    private val unattachedBosses = ArrayList<String>()

    private val variantLp = HashMap<Int, SeededValue>()
    private val variantPages = ArrayList<VariantPage>()
    private val variantOutcomes = LinkedHashMap<Int, VariantResult>()
    private val variantShadowed = ArrayList<Int>()

    var loaded: Boolean = false
        private set

    var seedDir: Path = Paths.get(
        System.getProperty("opennxt.seed.dir") ?: "data/seed"
    )
        private set

    @Synchronized
    fun load(dir: Path = seedDir): SeedData {
        seedDir = dir
        cacheLp.clear(); docLp.clear(); authoredLp.clear()
        cacheCb.clear(); docCb.clear(); authoredCb.clear()
        names.clear(); lpConflicts.clear(); cbConflicts.clear(); unattachedBosses.clear()
        docNameOf.clear(); docLpVariants.clear(); docCbVariants.clear(); cacheLpSetOf.clear()
        variantLp.clear(); variantPages.clear(); variantOutcomes.clear(); variantShadowed.clear()
        refLp.clear(); refRefused.clear(); refValuesOf.clear(); refConflicts.clear()
        refCounts.clear(); refCombat.clear(); refCross.clear(); refRetrieved = null
        profileLp.clear()

        loadCache(seedDir.resolve("npc_cache.json"))
        if (refEnabled) { loadRef(seedDir.resolve("npc_stats.json")); loadProfiles() }
        loadDocumented(seedDir.resolve("npc_documented.json"))
        loadAuthored(seedDir.resolve("npc_authored.json"))
        disambiguateVariants()
        detectConflicts()
        loaded = true
        return this
    }

    private fun ensure() { if (!loaded) load() }

    private fun read(path: Path): JsonObject? {
        if (!Files.exists(path)) return null
        Files.newBufferedReader(path).use { return JsonParser().parse(it).asJsonObject }
    }

    private fun JsonObject.intOrNull(k: String): Int? =
        if (has(k) && !get(k).isJsonNull) get(k).asInt else null

    private fun JsonObject.strOrNull(k: String): String? =
        if (has(k) && !get(k).isJsonNull) get(k).asString else null

    private fun loadCache(path: Path) {
        val root = read(path) ?: throw IllegalStateException(
            "cache seed missing: $path"
        )

        data class Group(val lps: MutableList<Int>, val structs: MutableList<Int>,
                         val npcIds: MutableSet<Int>, var ambiguous: Boolean)

        val groups = LinkedHashMap<String, Group>()
        for (el in root.getAsJsonArray("boss_encounters")) {
            val o = el.asJsonObject
            val name = o.strOrNull("name") ?: continue
            val lp = o.intOrNull("lifepoints") ?: continue
            val g = groups.getOrPut(name) { Group(ArrayList(), ArrayList(), LinkedHashSet(), false) }
            g.lps.add(lp)
            o.intOrNull("struct_game_id")?.let { g.structs.add(it) }
            val matches = o.getAsJsonArray("npc_name_matches") ?: JsonArray()
            for (m in matches) {
                val mo = m.asJsonObject
                mo.intOrNull("npc_id")?.let { g.npcIds.add(it) }
            }
            if (o.has("npc_match_ambiguous") && o.get("npc_match_ambiguous").asBoolean) g.ambiguous = true
        }

        for ((name, g) in groups) {
            if (g.npcIds.isEmpty()) { unattachedBosses.add(name); continue }
            val value = g.lps.max()
            val notes = StringBuilder("boss health-bar maximum for encounter struct(s) ")
            notes.append(g.structs.joinToString(",")).append(" (structs.game_id)")
            if (g.lps.distinct().size > 1) {
                notes.append("; this name has ").append(g.lps.size)
                    .append(" structs with maxima ").append(g.lps.sorted().joinToString(","))
                    .append(" -- MAX chosen by the loader, not a cache fact")
            }
            if (g.ambiguous) {
                notes.append("; attached by NAME to ").append(g.npcIds.size)
                    .append(" npc ids that share the name \"").append(name).append("\"")
            }
            val sv = SeededValue(value, Provenance.CACHE, "struct_param prop 8850",
                note = notes.toString())
            val lpSet = g.lps.distinct().sorted()
            for (id in g.npcIds) { cacheLp[id] = sv; cacheLpSetOf[id] = lpSet }
        }

        for (el in root.getAsJsonArray("npc_stats")) {
            val o = el.asJsonObject
            val id = o.intOrNull("npc_id") ?: continue
            o.strOrNull("name")?.let { names[id] = it }
            val cl = if (o.has("combat_level")) o.getAsJsonObject("combat_level") else null
            if (cl != null) {
                cacheCb[id] = SeededValue(cl.get("value").asInt, Provenance.CACHE,
                    cl.strOrNull("source") ?: "npcs.combat")
            }
        }
    }

    private fun loadDocumented(path: Path) {
        val root = read(path) ?: throw IllegalStateException(
            "documented seed missing: $path"
        )
        for (el in root.getAsJsonArray("npcs")) {
            val o = el.asJsonObject
            val name = o.strOrNull("name") ?: continue
            val ids = o.getAsJsonArray("npc_ids") ?: continue
            val src = o.strOrNull("source") ?: "seed"
            val retrieved = o.strOrNull("retrieved")
            val ambiguousIds = o.has("match_ambiguous") && o.get("match_ambiguous").asBoolean
            val variants = o.getAsJsonArray("distinct_documented_lifepoints")
            val note = buildString {
                append("reference data, variant \"").append(o.strOrNull("primary_variant")).append('"')
                if (variants != null && variants.size() > 1) {
                    append("; reference data also lists ").append(variants.joinToString(",") { it.asString })
                }
                if (ambiguousIds) append("; name maps to ").append(ids.size()).append(" npc ids")
            }
            val lp = o.intOrNull("lifepoints")
            val cb = o.intOrNull("combat_level")
            docLpVariants[name] = (o.getAsJsonArray("distinct_documented_lifepoints")
                ?: JsonArray()).map { it.asInt }
            docCbVariants[name] = (o.getAsJsonArray("distinct_documented_combat_levels")
                ?: JsonArray()).map { it.asInt }

            if (lp == null) {
                val table = LinkedHashMap<Int, MutableList<Int>>()
                for (vEl in (o.getAsJsonArray("variants") ?: JsonArray())) {
                    val v = vEl.asJsonObject
                    val vl = v.intOrNull("combat_level") ?: continue
                    val vlp = v.intOrNull("lifepoints") ?: continue
                    val bucket = table.getOrPut(vl) { ArrayList() }
                    if (!bucket.contains(vlp)) bucket.add(vlp)
                }
                if (table.isNotEmpty()) {
                    variantPages.add(VariantPage(
                        name = name,
                        refPage = o.strOrNull("page"),
                        source = src,
                        retrieved = retrieved,
                        levelToLifepoints = table.mapValues { it.value.sorted() },
                        npcIds = ids.map { it.asInt }
                    ))
                }
            }

            for (idEl in ids) {
                val id = idEl.asInt
                names.putIfAbsent(id, name)
                docNameOf[id] = name
                if (lp != null) docLp[id] = SeededValue(lp, Provenance.DOCUMENTED, src, retrieved, note)
                if (cb != null) docCb[id] = SeededValue(cb, Provenance.DOCUMENTED, src, retrieved, note)
            }
        }
    }

    val refEnabled: Boolean
        get() = System.getProperty("opennxt.seed.stats") != "off"

    private fun loadRef(path: Path) {
        val root = read(path) ?: return
        refRetrieved = root.strOrNull("retrieved")
        if (root.has("counts")) for ((k, v) in root.getAsJsonObject("counts").entrySet()) {
            if (v.isJsonPrimitive && v.asJsonPrimitive.isNumber) refCounts[k] = v.asInt
        }
        for (el in root.getAsJsonArray("refused") ?: JsonArray()) {
            el.asJsonObject.intOrNull("npc_id")?.let { refRefused.add(it) }
        }
        for (el in root.getAsJsonArray("npcs")) {
            val o = el.asJsonObject
            val id = o.intOrNull("npc_id") ?: continue
            val lp = o.intOrNull("lifepoints") ?: continue
            val page = o.strOrNull("page") ?: "?"
            val version = o.strOrNull("version_name")?.takeIf { it.isNotBlank() }
            val note = buildString {
                append("reference data names this npc id on page \"").append(page).append('"')
                if (version != null) append(", version \"").append(version).append('"')
                append("; cross-check vs cache: level ")
                append(if (o.has("cache_level_match") && o.get("cache_level_match").asBoolean) "match" else "MISMATCH")
                append(", armour ")
                append(if (o.has("cache_armour_match") && o.get("cache_armour_match").asBoolean) "match" else "MISMATCH")
            }
            val others = (o.getAsJsonArray("other_values") ?: JsonArray()).map { it.asInt }
            refValuesOf[id] = listOf(lp) + others
            if (o.has("combat") && o.get("combat").isJsonObject) {
                val cb = o.getAsJsonObject("combat")
                fun d(k: String): Double? = if (cb.has(k) && !cb.get(k).isJsonNull) cb.get(k).asDouble else null
                fun yn(k: String): Boolean? = when (cb.strOrNull(k)?.trim()?.lowercase()) {
                    "yes" -> true; "no" -> false; else -> null
                }
                refCombat[id] = RefCombat(
                    defence = cb.intOrNull("defence"), attack = cb.intOrNull("attack"), ranged = cb.intOrNull("ranged"),
                    magic = cb.intOrNull("magic"), necromancy = cb.intOrNull("necromancy"),
                    maxMelee = cb.intOrNull("max_melee"), maxRanged = cb.intOrNull("max_ranged"),
                    maxMagic = cb.intOrNull("max_magic"), maxNecromancy = cb.intOrNull("max_necromancy"),
                    maxSpec = cb.intOrNull("max_spec"),
                    accMelee = cb.intOrNull("acc_melee"), accRanged = cb.intOrNull("acc_ranged"),
                    accMagic = cb.intOrNull("acc_magic"), accNecromancy = cb.intOrNull("acc_necromancy"),
                    affWeakness = cb.intOrNull("aff_weakness"), affMelee = cb.intOrNull("aff_melee"),
                    affRanged = cb.intOrNull("aff_ranged"), affMagic = cb.intOrNull("aff_magic"),
                    experience = d("experience"), slayerLevel = cb.intOrNull("slaylvl"), slayerXp = d("slayxp"),
                    slayerCategory = cb.strOrNull("slayercat"),
                    weakness = cb.strOrNull("weakness"), style = cb.strOrNull("style"),
                    primaryStyle = cb.strOrNull("primarystyle"),
                    aggressive = yn("aggressive"), aggressiveRaw = cb.strOrNull("aggressive"),
                    poisonous = yn("poisonous"),
                    immuneToPoison = yn("immune_to_poison"), immuneToStun = yn("immune_to_stun"),
                    immuneToDeflect = yn("immune_to_deflect"), immuneToDrain = yn("immune_to_drain")
                )
            }
            if (o.has("cross_check") && o.get("cross_check").isJsonObject) {
                val m = LinkedHashMap<String, Boolean?>()
                for ((k, v) in o.getAsJsonObject("cross_check").entrySet()) m[k] = if (v.isJsonNull) null else v.asBoolean
                refCross[id] = m
            }
            o.strOrNull("cache_name")?.let { names.putIfAbsent(id, it) }
            refLp[id] = SeededValue(
                lp, Provenance.DOCUMENTED, o.strOrNull("source") ?: "seed",
                o.strOrNull("retrieved") ?: refRetrieved, note,
                disambiguation = o.strOrNull("disambiguation")
            )
        }
    }

    private fun loadProfiles() {
        NpcProfiles.load(seedDir)
        for (id in NpcProfiles.ids()) {
            val b = NpcProfiles.beast(id) ?: continue
            val lp = b.lifepoints ?: continue
            if (lp <= 0) continue
            names.putIfAbsent(id, b.name ?: continue)
            profileLp[id] = SeededValue(
                lp, Provenance.DOCUMENTED,
                "npc_profiles.json",
                NpcProfiles.retrieved,
                note = "npc profile row for npc $id" +
                    (if (b.refLifepoints != null && b.refLifepoints != lp) "; reference data ${b.refLifepoints} takes precedence" else "")
            )
        }
    }

    private fun loadAuthored(path: Path) {
        val root = read(path) ?: return
        for (el in root.getAsJsonArray("npcs")) {
            val o = el.asJsonObject
            val id = o.intOrNull("npc_id") ?: continue
            val why = o.strOrNull("reason") ?: "AUTHORED -- invented to fill a gap"
            o.strOrNull("name")?.let { names.putIfAbsent(id, it) }
            o.intOrNull("lifepoints")?.let {
                authoredLp[id] = SeededValue(it, Provenance.AUTHORED, why, note = "not from cache or reference data")
            }
            o.intOrNull("combat_level")?.let {
                authoredCb[id] = SeededValue(it, Provenance.AUTHORED, why, note = "not from cache or reference data")
            }
        }
    }

    val variantDisambiguationEnabled: Boolean
        get() = System.getProperty("opennxt.experiment.seed.variantDisambiguation") != "off"

    val VARIANT_PROVENANCE: String =
        "seed: lifepoints variants resolved by combat level " +
            "(-Dopennxt.experiment.seed.variantDisambiguation=off to disable)"

    fun resolveVariant(level: Int?, table: Map<Int, List<Int>>): VariantResult {
        if (level == null || level <= 0) return VariantResult(VariantOutcome.NOT_A_COMBAT_NPC, null, level)
        val values = table[level]
            ?: return VariantResult(VariantOutcome.LEVEL_NOT_IN_VARIANTS, null, level)
        if (values.size != 1) return VariantResult(VariantOutcome.LEVEL_MULTI_VALUED, null, level)
        return VariantResult(VariantOutcome.RESOLVED, values[0], level)
    }

    private fun disambiguateVariants() {
        if (!variantDisambiguationEnabled) return
        for (page in variantPages) {
            for (id in page.npcIds) {
                val level = cacheCb[id]?.takeIf { it.provenance == Provenance.CACHE }?.value
                val r = resolveVariant(level, page.levelToLifepoints)
                variantOutcomes[id] = r
                val lp = r.lifepoints ?: continue
                val published = page.levelToLifepoints.values.flatten().distinct().sorted()
                variantLp[id] = SeededValue(
                    lp, Provenance.DOCUMENTED, page.source, page.retrieved,
                    note = "reference page \"${page.name}\" lists lifepoints ${published.joinToString(",")}",
                    disambiguation = "chosen by combat level $level"
                )
                if (cacheLp.containsKey(id)) variantShadowed.add(id)
            }
        }
    }

    private fun detectConflicts() {
        val cbSetForName = HashMap<String, MutableSet<Int>>()
        for ((id, dn) in docNameOf) cacheCb[id]?.let {
            cbSetForName.getOrPut(dn) { LinkedHashSet() }.add(it.value)
        }

        for ((id, c) in cacheLp) {
            val d = docLp[id] ?: continue
            if (c.value == d.value) continue
            val dn = docNameOf[id]
            val cacheSet = cacheLpSetOf[id] ?: listOf(c.value)
            val docSet = (dn?.let { docLpVariants[it] } ?: listOf(d.value)).ifEmpty { listOf(d.value) }
            lpConflicts.add(SeedConflict(id, names[id] ?: "?", "lifepoints", c, d,
                genuine = cacheSet.intersect(docSet.toSet()).isEmpty(),
                cacheValuesForName = cacheSet, documentedValuesForName = docSet.sorted()))
        }
        for ((id, c) in cacheCb) {
            val d = docCb[id] ?: continue
            if (c.value == d.value) continue
            val dn = docNameOf[id]
            val cacheSet = (dn?.let { cbSetForName[it] }?.sorted()) ?: listOf(c.value)
            val docSet = (dn?.let { docCbVariants[it] } ?: listOf(d.value)).ifEmpty { listOf(d.value) }
            cbConflicts.add(SeedConflict(id, names[id] ?: "?", "combat_level", c, d,
                genuine = cacheSet.intersect(docSet.toSet()).isEmpty(),
                cacheValuesForName = cacheSet, documentedValuesForName = docSet.sorted()))
        }
        lpConflicts.sortBy { it.npcId }
        cbConflicts.sortBy { it.npcId }

        for ((id, c) in cacheLp) {
            val w = refLp[id] ?: continue
            if (c.value == w.value) continue
            val cacheSet = cacheLpSetOf[id] ?: listOf(c.value)
            val refSet = refValuesOf[id] ?: listOf(w.value)
            refConflicts.add(SeedConflict(id, names[id] ?: "?", "lifepoints", c, w,
                genuine = cacheSet.intersect(refSet.toSet()).isEmpty(),
                cacheValuesForName = cacheSet, documentedValuesForName = refSet.sorted()))
        }
        refConflicts.sortBy { it.npcId }
    }

    fun lifepoints(npcId: Int): SeededValue? {
        ensure()
        return cacheLp[npcId] ?: refLp[npcId] ?: profileLp[npcId] ?: docLp[npcId] ?: variantLp[npcId] ?: authoredLp[npcId]
    }

    fun layerOf(npcId: Int): LifepointsLayer? {
        ensure()
        return when {
            cacheLp.containsKey(npcId) -> LifepointsLayer.CACHE
            refLp.containsKey(npcId) -> LifepointsLayer.REF_TABLE
            profileLp.containsKey(npcId) -> LifepointsLayer.PROFILE
            docLp.containsKey(npcId) -> LifepointsLayer.DOCUMENTED
            variantLp.containsKey(npcId) -> LifepointsLayer.VARIANT
            authoredLp.containsKey(npcId) -> LifepointsLayer.AUTHORED
            else -> null
        }
    }

    fun layerCounts(): Map<LifepointsLayer, Int> {
        ensure()
        val m = LinkedHashMap<LifepointsLayer, Int>()
        for (l in LifepointsLayer.values()) m[l] = 0
        for (id in allIds()) layerOf(id)?.let { m[it] = m[it]!! + 1 }
        return m
    }

    private fun allIds(): Set<Int> {
        val ids = HashSet<Int>()
        ids.addAll(cacheLp.keys); ids.addAll(refLp.keys); ids.addAll(profileLp.keys); ids.addAll(docLp.keys)
        ids.addAll(variantLp.keys); ids.addAll(authoredLp.keys)
        return ids
    }

    fun refLifepoints(npcId: Int): SeededValue? { ensure(); return refLp[npcId] }

    fun refIds(): Set<Int> { ensure(); return refLp.keys }

    fun refRefusedIds(): Set<Int> { ensure(); return refRefused }

    fun refValuesFor(npcId: Int): List<Int> { ensure(); return refValuesOf[npcId] ?: emptyList() }

    fun refCounts(): Map<String, Int> { ensure(); return refCounts }

    fun profileLifepoints(npcId: Int): SeededValue? { ensure(); return profileLp[npcId] }
    fun profileIds(): Set<Int> { ensure(); return profileLp.keys }

    fun refCombat(npcId: Int): RefCombat? { ensure(); return refCombat[npcId] }

    fun refLifepointsConflicts(): List<SeedConflict> { ensure(); return refConflicts }
    fun genuineRefLifepointsConflicts(): List<SeedConflict> { ensure(); return refConflicts.filter { it.genuine } }

    fun variantPages(): List<VariantPage> { ensure(); return variantPages }

    fun variantLifepoints(npcId: Int): SeededValue? { ensure(); return variantLp[npcId] }

    fun variantOutcomes(): Map<Int, VariantResult> { ensure(); return variantOutcomes }

    fun variantOutcomeCounts(): Map<VariantOutcome, Int> {
        ensure()
        val m = LinkedHashMap<VariantOutcome, Int>()
        for (o in VariantOutcome.values()) m[o] = 0
        for (r in variantOutcomes.values) m[r.outcome] = m[r.outcome]!! + 1
        return m
    }

    fun variantResolvedIds(): Set<Int> { ensure(); return variantLp.keys }

    fun variantShadowedByCache(): List<Int> { ensure(); return variantShadowed }

    fun combatLevel(npcId: Int): SeededValue? {
        ensure(); return cacheCb[npcId] ?: docCb[npcId] ?: authoredCb[npcId]
    }

    fun allLifepoints(npcId: Int): List<SeededValue> {
        ensure()
        return listOfNotNull(cacheLp[npcId], refLp[npcId], profileLp[npcId], docLp[npcId], variantLp[npcId], authoredLp[npcId])
    }

    fun name(npcId: Int): String? { ensure(); return names[npcId] }

    fun lifepointsConflicts(): List<SeedConflict> { ensure(); return lpConflicts }
    fun combatLevelConflicts(): List<SeedConflict> { ensure(); return cbConflicts }

    fun genuineLifepointsConflicts(): List<SeedConflict> { ensure(); return lpConflicts.filter { it.genuine } }
    fun genuineCombatLevelConflicts(): List<SeedConflict> { ensure(); return cbConflicts.filter { it.genuine } }

    fun conflictFor(npcId: Int): SeedConflict? {
        ensure(); return lpConflicts.firstOrNull { it.npcId == npcId }
    }

    fun unattachedBossEncounters(): List<String> { ensure(); return unattachedBosses }

    fun countsByProvenance(): Map<Provenance, Int> {
        ensure()
        val ids = allIds()
        val m = LinkedHashMap<Provenance, Int>()
        for (p in Provenance.values()) m[p] = 0
        for (id in ids) lifepoints(id)?.let { m[it.provenance] = m[it.provenance]!! + 1 }
        return m
    }

    fun combatCountsByProvenance(): Map<Provenance, Int> {
        ensure()
        val ids = HashSet<Int>().apply { addAll(cacheCb.keys); addAll(docCb.keys); addAll(authoredCb.keys) }
        val m = LinkedHashMap<Provenance, Int>()
        for (p in Provenance.values()) m[p] = 0
        for (id in ids) combatLevel(id)?.let { m[it.provenance] = m[it.provenance]!! + 1 }
        return m
    }

    fun size(): Int { ensure(); return allIds().size }
}

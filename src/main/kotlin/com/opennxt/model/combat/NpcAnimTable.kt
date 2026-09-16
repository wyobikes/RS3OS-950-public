package com.opennxt.model.combat

import com.opennxt.resources.sqlite.RsDatabase
import mu.KotlinLogging
import java.nio.file.Files
import java.nio.file.Path

object NpcAnimTable {
    private val logger = KotlinLogging.logger { }

    const val DEATH_SWITCH = "opennxt.experiment.combat.anim.death"
    const val FILE = "npc_anims.tsv"

    data class AnimSample(
        val sampleSet: String, val packet: Int, val npcIndex: Int, val npcId: Int, val name: String?,
        val anims: IntArray, val delay: Int, val animationGroup: Int?,
        val deathCandidate: Boolean, val killCandidate: Boolean
    ) {
        val sequence: Int? get() = anims.filter { it >= 0 }.distinct().singleOrNull()
    }

    data class DeathAnimation(
        val sequence: Int,
        val provenance: String,
        val viaAnimationGroup: Int?,
        val detail: String
    )

    private val byNpc = HashMap<Int, MutableList<AnimSample>>()
    private val killByNpc = HashMap<Int, LinkedHashSet<Int>>()
    private val killByGroup = HashMap<Int, LinkedHashSet<Int>>()
    private val groupCache = HashMap<Int, Int?>()

    var loaded: Boolean = false
        private set
    var rows: Int = 0
        private set
    var path: Path? = null
        private set

    @Synchronized
    fun load(dir: Path = SeedData.seedDir): NpcAnimTable {
        byNpc.clear(); killByNpc.clear(); killByGroup.clear(); groupCache.clear()
        rows = 0
        val p = dir.resolve(FILE)
        path = p
        if (!Files.exists(p)) {
            logger.warn { "$p absent - no npc animation table; death animations off unless -D$DEATH_SWITCH names one" }
            loaded = true
            return this
        }
        var header: List<String>? = null
        Files.readAllLines(p).forEach { line ->
            if (line.isBlank() || line.startsWith("#")) return@forEach
            val cols = line.split('\t')
            if (header == null) { header = cols; return@forEach }
            val h = header!!
            fun col(name: String) = cols.getOrNull(h.indexOf(name))?.takeIf { it.isNotEmpty() }
            val npcId = col("npc_id")?.toIntOrNull() ?: return@forEach
            val anims = intArrayOf(
                col("anim0")?.toIntOrNull() ?: -1, col("anim1")?.toIntOrNull() ?: -1,
                col("anim2")?.toIntOrNull() ?: -1, col("anim3")?.toIntOrNull() ?: -1
            )
            val obs = AnimSample(
                sampleSet = col("sample") ?: "?", packet = col("packet")?.toIntOrNull() ?: -1,
                npcIndex = col("npc_index")?.toIntOrNull() ?: -1, npcId = npcId, name = col("name"),
                anims = anims, delay = col("delay")?.toIntOrNull() ?: 0,
                animationGroup = col("animgroup")?.toIntOrNull(),
                deathCandidate = col("death_candidate") == "1", killCandidate = col("kill_candidate") == "1"
            )
            rows++
            byNpc.getOrPut(npcId) { ArrayList() }.add(obs)
            if (obs.killCandidate) obs.sequence?.let { seq ->
                killByNpc.getOrPut(npcId) { LinkedHashSet() }.add(seq)
                animationGroup(npcId)?.let { g -> killByGroup.getOrPut(g) { LinkedHashSet() }.add(seq) }
            }
        }
        loaded = true
        logger.info {
            "npc animation table: $rows rows over${byNpc.size} npc ids; kill candidates on " +
                "${killByNpc.size} npc ids -> ${killByGroup.size} animation groups " +
                "(${killByGroup.entries.joinToString { "${it.key}:${it.value.joinToString("/")}" }})"
        }
        return this
    }

    private fun ensure() { if (!loaded) load() }

    private val lengthCache = HashMap<Int, Int?>()

    fun sequenceLengthTicks(sequence: Int): Int? {
        if (lengthCache.containsKey(sequence)) return lengthCache[sequence]
        val value: Int? = try {
            if (!RsDatabase.available) null else {
                val raw = RsDatabase.queryOne("SELECT value FROM sequences_attr WHERE id = ? AND field = 'frames'", sequence) { it.getString(1) }
                if (raw == null) null else {
                    val units = com.google.gson.JsonParser().parse(raw).asJsonArray.sumOf { it.asJsonObject.get("framelength").asInt }
                    if (units <= 0) null else (units * 20 + 599) / 600
                }
            }
        } catch (t: Exception) {
            logger.warn { "sequence $sequence: frames row did not parse (${t.javaClass.simpleName}: ${t.message}); treating as no length" }
            null
        }
        lengthCache[sequence] = value
        return value
    }

    fun animationGroup(npcId: Int): Int? {
        if (groupCache.containsKey(npcId)) return groupCache[npcId]
        val value: Int? = if (!RsDatabase.available) null
        else RsDatabase.queryOne("SELECT animation_group FROM npcs WHERE game_id = ?", npcId) { rs ->
            val v = rs.getInt(1); if (rs.wasNull()) null else v
        }
        groupCache[npcId] = value
        return value
    }

    fun seenValues(npcId: Int): List<AnimSample> { ensure(); return byNpc[npcId] ?: emptyList() }
    fun animNpcIds(): Set<Int> { ensure(); return byNpc.keys }
    fun killSequences(npcId: Int): Set<Int> { ensure(); return killByNpc[npcId] ?: emptySet() }
    fun killSequencesByGroup(): Map<Int, Set<Int>> { ensure(); return killByGroup }

    val mode: String
        get() {
            val raw = System.getProperty(DEATH_SWITCH)?.trim() ?: return "TABLE"
            if (raw.equals("off", ignoreCase = true)) return "OFF"
            return raw.toIntOrNull()?.toString() ?: "TABLE"
        }

    fun resolve(npcId: Int, group: Int?, byId: Map<Int, Set<Int>>, byGroup: Map<Int, Set<Int>>): DeathAnimation? {
        byId[npcId]?.let { seen ->
            return if (seen.size == 1) DeathAnimation(seen.first(), "TABLE", null,
                "sequence ${seen.first()} is the death animation of npc $npcId")
            else null
        }
        val g = group ?: return null
        val seen = byGroup[g] ?: return null
        return if (seen.size == 1) DeathAnimation(seen.first(), "TABLE-BY-GROUP", g,
            "sequence ${seen.first()} is the death animation of an npc in animation group $g")
        else null
    }

    fun deathAnimation(npcId: Int): DeathAnimation? {
        val raw = System.getProperty(DEATH_SWITCH)?.trim()
        if (raw != null && raw.equals("off", ignoreCase = true)) return null
        raw?.toIntOrNull()?.let {
            return DeathAnimation(it, "OPERATOR", null, "-D$DEATH_SWITCH=$it names it for every npc")
        }
        ensure()
        if (killByNpc[npcId]?.size == 1) return resolve(npcId, null, killByNpc, emptyMap())
        NpcProfiles.deathAnimation(npcId)?.let {
            return DeathAnimation(it, "PROFILE", null, "the npc profile lists sequence $it as npc $npcId's death animation")
        }
        return resolve(npcId, animationGroup(npcId), killByNpc, killByGroup)
    }
}

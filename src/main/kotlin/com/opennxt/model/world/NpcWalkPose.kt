package com.opennxt.model.world

import com.google.gson.JsonParser
import com.opennxt.resources.sqlite.RsDatabase
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap
import mu.KotlinLogging
import java.util.concurrent.ConcurrentHashMap

object NpcWalkPose {
    private val logger = KotlinLogging.logger { }

    val enabled: Boolean = System.getProperty("opennxt.experiment.npcs.wander.pose") != "false"

    enum class Pose(val walks: Boolean) {
        WALK(true),

        OPCODE2(true),

        NONE(false),

        NO_GROUP(false),

        UNKNOWN(true)
    }

    private const val NO_GROUP_DECLARED = -1

    private val groupOfNpc: Int2IntOpenHashMap by lazy {
        val out = Int2IntOpenHashMap()
        RsDatabase.queryAll("SELECT id, animation_group FROM npcs") { rs ->
            val group = rs.getInt(2)
            val absent = rs.wasNull()
            val id = rs.getInt(1)
            out.put(id, if (absent) NO_GROUP_DECLARED else group)
        }
        out
    }

    private val poseOfGroup: Map<Int, Pose> by lazy {
        val walkOf = HashMap<Int, Int>()
        val hasOpcode2 = HashSet<Int>()
        RsDatabase.queryAll(
            "SELECT id, field, value FROM animgroups_attr WHERE field IN ('baseAnims', 'unknown_02')"
        ) { rs ->
            val id = rs.getInt(1)
            when (rs.getString(2)) {
                "unknown_02" -> hasOpcode2.add(id)
                "baseAnims" -> runCatching {
                    val o = JsonParser().parse(rs.getString(3)).asJsonObject
                    val w = o.get("walk")
                    if (w != null && w.isJsonPrimitive && w.asJsonPrimitive.isNumber) walkOf[id] = w.asInt
                }.onFailure {
                    logger.warn { "animgroups_attr baseAnims for group $id did not parse: ${it.message}" }
                }
                else -> Unit
            }
        }
        val out = HashMap<Int, Pose>()
        for (id in walkOf.keys + hasOpcode2) {
            val w = walkOf[id]
            out[id] = when {
                w != null && w >= 0 -> Pose.WALK
                id in hasOpcode2 -> Pose.OPCODE2
                w != null -> Pose.NONE
                else -> Pose.OPCODE2
            }
        }
        out
    }

    private val memo = ConcurrentHashMap<Int, Pose>()

    fun groupOf(gameId: Int): Int? {
        if (!RsDatabase.available || !groupOfNpc.containsKey(gameId)) return null
        val g = groupOfNpc.get(gameId)
        if (g != NO_GROUP_DECLARED) return g
        if (!com.opennxt.model.world.NpcMorph.movementFillEnabled) return null
        val target = com.opennxt.model.world.NpcMorph.effectiveId(gameId)
        if (target == gameId || !groupOfNpc.containsKey(target)) return null
        val t = groupOfNpc.get(target)
        return if (t == NO_GROUP_DECLARED) null else t
    }

    fun poseOf(gameId: Int): Pose = memo.getOrPut(gameId) {
        if (!RsDatabase.available) return@getOrPut Pose.UNKNOWN
        if (!groupOfNpc.containsKey(gameId)) return@getOrPut Pose.UNKNOWN
        val group = groupOf(gameId) ?: return@getOrPut Pose.NO_GROUP
        poseOfGroup[group] ?: Pose.UNKNOWN
    }

    fun mayWander(gameId: Int): Boolean = !enabled || poseOf(gameId).walks

    fun warm(gameIds: Collection<Int>): Int {
        if (RsDatabase.available) { groupOfNpc.size; poseOfGroup.size }
        var cold = 0
        for (id in gameIds) if (memo[id] == null) { cold++; poseOf(id) }
        return cold
    }

    fun census(gameIds: Collection<Int>): Map<Pose, Int> {
        val out = LinkedHashMap<Pose, Int>()
        for (p in Pose.values()) out[p] = 0
        for (id in gameIds) out[poseOf(id)] = (out[poseOf(id)] ?: 0) + 1
        return out
    }

    fun forget() = memo.clear()
}

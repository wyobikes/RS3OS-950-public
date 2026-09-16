package com.opennxt.resources.sqlite

import com.google.gson.JsonParser
import com.opennxt.resources.config.vars.ScriptVarType

object CacheVarTypes {
    enum class Target(val table: String?, val varType: ScriptVarType) {
        SEQUENCE("sequences", ScriptVarType.SEQ),
        ANIMGROUP("animgroups", ScriptVarType.BAS),
        SPOTANIM("spotanims", ScriptVarType.SPOTANIM),
        STRUCT("structs", ScriptVarType.STRUCT),
        ENUM("enums", ScriptVarType.ENUM),
        NPC("npcs", ScriptVarType.NPC),
        ITEM("items", ScriptVarType.OBJ),
        LOC("locs", ScriptVarType.LOC),

        SPRITE(null, ScriptVarType.GRAPHIC),
    }

    private val MAP: Map<Int, Target> = mapOf(
        6 to Target.SEQUENCE,
        23 to Target.SPRITE,
        44 to Target.ANIMGROUP,
        131 to Target.ANIMGROUP,
        37 to Target.SPOTANIM,
        73 to Target.STRUCT,
        26 to Target.ENUM,
        32 to Target.NPC,
        33 to Target.ITEM,
        30 to Target.LOC
    )

    val FINGERPRINT_ONLY: Set<Int> = setOf(131)

    val MAX_FIT: Map<Int, Double> = mapOf(
        32 to 1.0000, 33 to 1.0000, 73 to 0.9999, 131 to 0.9974,
        26 to 0.9994, 37 to 0.9982, 30 to 0.9714,
        6 to 1.0000, 44 to 0.9980
    )

    val FINGERPRINT_DISAGREES: Map<Int, Pair<String, Double>> = mapOf(
        23 to ("sequences" to 0.9556)
    )

    const val SPRITE_MAX_FIT: Double = 0.9977

    val PROVENANCE: String =
        "cache vartypes: param vartypes read from params_attr (opcode 101)"

    private val cache = HashMap<Int, Int?>()

    @Synchronized
    fun vartypeOf(paramId: Int): Int? = cache.getOrPut(paramId) {
        if (!RsDatabase.available) return@getOrPut null
        RsDatabase.queryOne(
            "SELECT value FROM params_attr WHERE id = ? AND field = 'type'", paramId
        ) { JsonParser().parse(it.getString(1)).asJsonObject.get("vartype")?.asInt }
    }

    fun scriptVarTypeOf(paramId: Int): ScriptVarType? =
        vartypeOf(paramId)?.let { ScriptVarType.getById(it) }

    fun targetOf(paramId: Int): Target? = vartypeOf(paramId)?.let { MAP[it] }

    fun isSequence(paramId: Int): Boolean = targetOf(paramId) == Target.SEQUENCE

    fun named(): Map<Int, Target> = MAP
}

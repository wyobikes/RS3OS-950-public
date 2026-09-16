package com.opennxt.model.combat

import com.google.gson.JsonParser
import com.opennxt.resources.sqlite.CacheVarTypes
import com.opennxt.resources.sqlite.RsDatabase

object WeaponAnimations {
    const val WEAPON_CLASS_PARAM = 686

    const val NPC_CLASS_PARAM = 2816

    val NPC_SLOTS = listOf(4385, 4386, 4387)

    val NPC_PROVENANCE: String =
        "npc combat animations: struct param slot unset " +
            "(-Dopennxt.experiment.combat.anim.npcslot=<param> to select one)"

    private fun extras(table: String, id: Int): Map<Int, Int> {
        if (!RsDatabase.available) return emptyMap()
        val raw = RsDatabase.queryOne(
            "SELECT value FROM ${table}_attr WHERE id = ? AND field = 'extra'", id
        ) { it.getString(1) } ?: return emptyMap()
        val out = HashMap<Int, Int>()
        for (el in JsonParser().parse(raw).asJsonArray) {
            val o = el.asJsonObject
            if (!o.has("prop") || !o.has("intvalue") || o.get("intvalue").isJsonNull) continue
            out[o.get("prop").asInt] = o.get("intvalue").asInt
        }
        return out
    }

    fun npcClassOf(npcId: Int): Int? =
        extras("npcs", npcId)[NPC_CLASS_PARAM]?.takeIf { it >= 0 }

    fun npcSequenceParams(npcId: Int): Map<Int, Int> {
        val struct = npcClassOf(npcId) ?: return emptyMap()
        return extras("structs", struct).filterKeys { CacheVarTypes.isSequence(it) }
    }

    fun sequenceClass(sequenceId: Int): Int? {
        if (!RsDatabase.available) return null
        return RsDatabase.queryOne(
            "SELECT unknown_05 FROM sequences WHERE id = ?", sequenceId
        ) { rs -> rs.getInt(1).takeIf { !rs.wasNull() } }
    }

    fun npcSlotConfigured(): Int? =
        System.getProperty("opennxt.experiment.combat.anim.npcslot")?.trim()?.toIntOrNull()

    fun npcSelected(npcId: Int): Int? {
        val param = npcSlotConfigured() ?: return null
        if (!CacheVarTypes.isSequence(param)) return null
        return npcSequenceParams(npcId)[param]?.takeIf { it >= 0 }
    }

    fun weaponClassOf(itemId: Int): Int? =
        extras("items", itemId)[WEAPON_CLASS_PARAM]?.takeIf { it >= 0 }

    fun sequenceParamsFor(itemId: Int): Map<Int, Int> {
        val struct = weaponClassOf(itemId) ?: return emptyMap()
        return extras("structs", struct).filterKeys { CacheVarTypes.isSequence(it) }
    }

    fun configured(): Pair<Int, Int>? {
        val raw = System.getProperty("opennxt.experiment.combat.anim.weapon") ?: return null
        val parts = raw.split(':')
        if (parts.size != 2) return null
        val item = parts[0].toIntOrNull() ?: return null
        val param = parts[1].toIntOrNull() ?: return null
        return item to param
    }

    fun selected(): Int? {
        val (item, param) = configured() ?: return null
        if (!CacheVarTypes.isSequence(param)) return null
        return sequenceParamsFor(item)[param]?.takeIf { it >= 0 }
    }
}

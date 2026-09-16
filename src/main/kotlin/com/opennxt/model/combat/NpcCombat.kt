package com.opennxt.model.combat

import com.google.gson.JsonParser
import com.opennxt.resources.sqlite.NpcDefinition
import com.opennxt.resources.sqlite.RsDatabase
import com.opennxt.model.world.WorldNpc
import com.opennxt.resources.sqlite.SqliteNpcCodec

object NpcCombat {
    fun load(id: Int): NpcCombatDefinition? {
        val base = SqliteNpcCodec.load(id) ?: return null
        return NpcCombatDefinition(base, loadParams(id))
    }

    private val paramMemo = java.util.concurrent.ConcurrentHashMap<Int, Map<Int, Int>>()

    fun loadParams(id: Int): Map<Int, Int> {
        paramMemo[id]?.let { return it }
        val answer = loadParamsUncached(id)
        paramMemo[id] = answer
        return answer
    }

    private fun loadParamsUncached(id: Int): Map<Int, Int> {
        if (!RsDatabase.hasTable("npcs_attr")) return emptyMap()
        val json = RsDatabase.queryOne(
            "SELECT value FROM npcs_attr WHERE id = ? AND field = 'extra'", id
        ) { it.getString("value") } ?: return emptyMap()
        return parseParams(json)
    }

    fun warm(ids: Collection<Int>): Long {
        val before = RsDatabase.queryCount()
        for (id in ids) {
            SqliteNpcCodec.load(id)
            loadParams(id)
            val target = com.opennxt.model.world.NpcMorph.effectiveId(id)
            if (target != id) {
                SqliteNpcCodec.load(target)
                loadParams(target)
            }
        }
        return RsDatabase.queryCount() - before
    }

    fun memoSize(): Int = paramMemo.size

    fun forgetMemo() = paramMemo.clear()

    fun parseParams(json: String): Map<Int, Int> {
        val out = LinkedHashMap<Int, Int>()
        val root = try {
            JsonParser().parse(json)
        } catch (e: Exception) {
            return out
        }
        if (!root.isJsonArray) return out
        for (element in root.asJsonArray) {
            if (!element.isJsonObject) continue
            val obj = element.asJsonObject
            val prop = obj.get("prop") ?: continue
            val value = obj.get("intvalue") ?: continue
            if (value.isJsonNull) continue
            try {
                out[prop.asInt] = value.asInt
            } catch (e: Exception) {
            }
        }
        return out
    }
}

object NpcDeathTransmission {
    const val LINGER_SWITCH = "opennxt.experiment.combat.deathlinger"

    val lingerTicks: Int
        get() {
            val raw = System.getProperty(LINGER_SWITCH)?.trim()?.toIntOrNull() ?: return 0
            if (raw <= 0) return 0
            return minOf(raw, WorldNpc.RESPAWN_TICKS - 1)
        }

    fun lingerTicksFor(npc: WorldNpc): Int {
        val explicit = System.getProperty(LINGER_SWITCH)?.trim()?.toIntOrNull()
        if (explicit != null) return minOf(explicit.coerceAtLeast(0), npc.respawnTotal - 1).coerceAtLeast(0)
        val seq = npc.deathAnimation?.sequence ?: return 0
        val ticks = NpcAnimTable.sequenceLengthTicks(seq) ?: return 0
        return minOf(ticks, npc.respawnTotal - 1).coerceAtLeast(0)
    }

    fun ticksDead(npc: WorldNpc): Int {
        if (npc.alive) return -1
        val remaining = npc.respawnTicksRemaining
        if (remaining < 0) return -1
        return (npc.respawnTotal - remaining).coerceAtLeast(0)
    }

    fun transmissible(npc: WorldNpc): Boolean {
        if (npc.despawned) return false
        if (npc.alive) return true
        val dead = ticksDead(npc)
        return dead >= 0 && dead <= lingerTicksFor(npc)
    }

    fun shouldRetire(npc: WorldNpc): Boolean {
        if (npc.alive) return false
        if (transmissible(npc)) return false
        if (npc.pendingUpdates.blocks().isEmpty()) return false
        queuesRetired++
        return true
    }

    var queuesRetired: Int = 0
        private set

    fun resetCounters() {
        queuesRetired = 0
    }

    val PROVENANCE: String =
        "npc death: corpses linger for their death animation, otherwise one tick; " +
            "-D" + LINGER_SWITCH + "=<ticks> overrides"
}

object NpcCombatParams {
    const val MELEE_DAMAGE = 641

    const val MELEE_ACCURACY = 29

    const val RANGED_DAMAGE = 643
    const val RANGED_ACCURACY = 4

    const val MAGIC_DAMAGE = 965
    const val MAGIC_ACCURACY = 3

    const val ARMOUR = 2865

    const val ATTACK_SPEED = 14

    const val WEAKNESS_CLASS = 26

    const val WEAKNESS = 2848

    val AFFINITY = intArrayOf(2849, 2850, 2851, 2852)

    const val ITEM_REQUIREMENT_SKILL = 749
    const val ITEM_REQUIREMENT_LEVEL = 750

    const val ITEM_ACCURACY = 3267

    const val ITEM_DAMAGE = 641

    const val ITEM_RANGED_ACCURACY = 4
    const val ITEM_MAGIC_ACCURACY = 3
    const val ITEM_RANGED_DAMAGE = 643
    const val ITEM_MAGIC_DAMAGE = 965
    const val ITEM_ATTACK_RANGE = 13
    const val ITEM_STYLE_MELEE = 2825
    const val ITEM_STYLE_RANGED = 2826
    const val ITEM_STYLE_MAGIC = 2827
}

enum class NpcWeakness(val key: Int, val label: String, val style: CombatStyle?) {
    NONE(0, "None", null),
    MAGIC_AIR(1, "Magic (Air)", CombatStyle.MAGIC),
    MAGIC_WATER(2, "Magic (Water)", CombatStyle.MAGIC),
    MAGIC_EARTH(3, "Magic (Earth)", CombatStyle.MAGIC),
    MAGIC_FIRE(4, "Magic (Fire)", CombatStyle.MAGIC),
    MELEE_STAB(5, "Melee (Stabbing)", CombatStyle.MELEE),
    MELEE_SLASH(6, "Melee (Slashing)", CombatStyle.MELEE),
    MELEE_CRUSH(7, "Melee (Crushing)", CombatStyle.MELEE),
    RANGED_ARROW(8, "Ranged (Arrow)", CombatStyle.RANGED),
    RANGED_BOLT(9, "Ranged (Bolt)", CombatStyle.RANGED),
    RANGED_THROWN(10, "Ranged (Thrown)", CombatStyle.RANGED),
    NECROMANCY(37, "Necromancy", null);

    companion object {
        private val BY_KEY = values().associateBy { it.key }

        fun of(key: Int): NpcWeakness? = BY_KEY[key]
    }
}

class NpcCombatDefinition(
    val definition: NpcDefinition,
    val params: Map<Int, Int>
) {
    val id: Int get() = definition.id
    val name: String? get() = definition.name

    val size: Int get() = definition.size

    val combatLevel: Int get() = definition.combatLevel ?: 0

    val attackable: Boolean
        get() = definition.actions.any { it != null && it.equals("Attack", ignoreCase = true) }

    val hasCombatParams: Boolean
        get() = params.keys.any {
            it == NpcCombatParams.MELEE_DAMAGE || it == NpcCombatParams.MELEE_ACCURACY ||
                it == NpcCombatParams.ARMOUR
        }

    val meleeDamage: Int? get() = params[NpcCombatParams.MELEE_DAMAGE]
    val meleeAccuracy: Int? get() = params[NpcCombatParams.MELEE_ACCURACY]
    val rangedDamage: Int? get() = params[NpcCombatParams.RANGED_DAMAGE]
    val rangedAccuracy: Int? get() = params[NpcCombatParams.RANGED_ACCURACY]
    val magicDamage: Int? get() = params[NpcCombatParams.MAGIC_DAMAGE]
    val magicAccuracy: Int? get() = params[NpcCombatParams.MAGIC_ACCURACY]
    val armour: Int? get() = params[NpcCombatParams.ARMOUR]

    val attackSpeed: Int? get() = params[NpcCombatParams.ATTACK_SPEED]

    val affinities: List<Int?> get() = NpcCombatParams.AFFINITY.map { params[it] }

    val weakTo: CombatStyle? get() = weaknessClass ?: weakness?.style

    fun affinityFor(style: CombatStyle): Int? {
        val weak = weakTo
        if (weak != null) return affinities[CombatFormulas.affinitySlot(style, weak)]
        val styleSlots = listOf(affinities[1], affinities[2], affinities[3])
        if (styleSlots.any { it == null }) return null
        return if (styleSlots.distinct().size == 1) styleSlots[0] else null
    }

    val weaknessClassRaw: Int? get() = params[NpcCombatParams.WEAKNESS_CLASS]

    val weaknessClass: CombatStyle?
        get() = when (weaknessClassRaw) {
            1 -> CombatStyle.MAGIC
            2 -> CombatStyle.MELEE
            3 -> CombatStyle.RANGED
            else -> null
        }

    val weakness: NpcWeakness? get() = params[NpcCombatParams.WEAKNESS]?.let(NpcWeakness::of)

    val combatStyle: CombatStyle? get() = weaknessClass?.beats

    fun damageFor(style: CombatStyle): Int? = when (style) {
        CombatStyle.MELEE -> meleeDamage
        CombatStyle.RANGED -> rangedDamage
        CombatStyle.MAGIC -> magicDamage
    }

    fun accuracyFor(style: CombatStyle): Int? = when (style) {
        CombatStyle.MELEE -> meleeAccuracy
        CombatStyle.RANGED -> rangedAccuracy
        CombatStyle.MAGIC -> magicAccuracy
    }

    fun toCombatStats(lifepoints: Int): CombatStats = CombatStats(lifepoints)

    override fun toString() =
        "NpcCombatDefinition($id, ${name ?: "unnamed"}, level=$combatLevel, size=$size, params=${params.size})"
}

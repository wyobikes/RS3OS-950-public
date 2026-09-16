package com.opennxt.model.vars

import com.opennxt.api.vars.VarDomain
import com.opennxt.resources.sqlite.SqliteVarBitCodec
import com.opennxt.resources.sqlite.VarBitDefinition
import it.unimi.dsi.fastutil.ints.Int2IntMap
import it.unimi.dsi.fastutil.ints.Int2IntMaps
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap
import it.unimi.dsi.fastutil.ints.IntAVLTreeSet
import it.unimi.dsi.fastutil.ints.IntOpenHashSet
import it.unimi.dsi.fastutil.ints.IntSortedSet

fun interface VarBitSource {
    fun definition(id: Int): VarBitDefinition?
}

object SqliteVarBits : VarBitSource {
    private val byId: Map<Int, VarBitDefinition> by lazy { SqliteVarBitCodec.listAll() }

    val size: Int get() = byId.size

    fun all(): Collection<VarBitDefinition> = byId.values

    override fun definition(id: Int): VarBitDefinition? = byId[id]
}

val VarBitDefinition.domainId: Int get() = varId ushr 16

val VarBitDefinition.varpId: Int get() = varId and 0xFFFF

val VarBitDefinition.isPlayerDomain: Boolean get() = domainId == VarDomain.PLAYER.id

class VarPlayerState(
    private val source: VarBitSource = SqliteVarBits,
    private val allowForeignDomains: Boolean = false
) {
    private val values = Int2IntOpenHashMap().apply { defaultReturnValue(0) }
    private val dirty = IntOpenHashSet()

    fun varps(): Int2IntMap = Int2IntMaps.unmodifiable(values)

    fun getVarp(id: Int): Int {
        require(id >= 0) { "varp id must not be negative: $id" }
        return values.get(id)
    }

    fun setVarp(id: Int, value: Int): Boolean {
        require(id >= 0) { "varp id must not be negative: $id" }
        val old = values.get(id)
        if (old == value) return false
        if (value == 0) values.remove(id) else values.put(id, value)
        dirty.add(id)
        return true
    }

    fun definition(id: Int): VarBitDefinition? = source.definition(id)

    fun getVarbitOrNull(id: Int): Int? {
        val def = source.definition(id) ?: return null
        return usable(def).read(getVarp(def.varpId))
    }

    fun getVarbit(id: Int): Int {
        val def = required(id)
        return usable(def).read(getVarp(def.varpId))
    }

    fun setVarbit(id: Int, value: Int): Boolean {
        val def = usable(required(id))
        require(def.fits(value)) {
            "varbit $id is ${def.bitCount} bit(s) wide (0..${def.maxValueUnsigned}); " +
                "refusing to write $value - truncating it would silently change varp ${def.varpId}"
        }
        val varp = def.varpId
        return setVarp(varp, def.write(getVarp(varp), value))
    }

    fun accepts(id: Int, value: Int): Boolean {
        val def = source.definition(id) ?: return false
        if (!def.isWellFormed) return false
        if (!allowForeignDomains && !def.isPlayerDomain) return false
        return def.fits(value)
    }

    fun dirtyVarps(): IntSortedSet = IntAVLTreeSet(dirty)

    fun isDirty(varp: Int): Boolean = dirty.contains(varp)

    fun dirtyCount(): Int = dirty.size

    fun clearDirty() = dirty.clear()

    private fun required(id: Int): VarBitDefinition =
        source.definition(id) ?: throw IllegalArgumentException(
            "no varbit $id (use definition(id) or getVarbitOrNull(id) if the id may not exist)"
        )

    private fun usable(def: VarBitDefinition): VarBitDefinition {
        require(def.isWellFormed) {
            "varbit ${def.id} has an impossible bit range ${def.bitStart}..${def.bitEnd}; " +
                "a 32-bit varp has no such bits"
        }
        require(allowForeignDomains || def.isPlayerDomain) {
            "varbit ${def.id} lives in domain ${def.domainId} varp ${def.varpId}, not a player varp; " +
                "writing it into player state would change a varp no client is sent"
        }
        return def
    }
}

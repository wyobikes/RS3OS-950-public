package com.opennxt.model.items

import com.opennxt.resources.FilesystemResources
import com.opennxt.resources.sqlite.RsDatabase
import com.opennxt.resources.sqlite.ItemDefinition
import it.unimi.dsi.fastutil.ints.Int2BooleanOpenHashMap

data class Item(val id: Int, val amount: Int = 1) {
    init {
        require(id >= 0) { "item id must not be negative: $id" }
        require(amount >= 1) { "item amount must be at least 1 (an empty slot is null, not Item($id, $amount))" }
    }

    val definition: ItemDefinition?
        get() = ItemStacking.definition(id)

    val name: String?
        get() = definition?.name

    val stackable: Boolean
        get() = ItemStacking.isStackable(id)

    fun plus(delta: Int): Item {
        require(delta >= 0) { "use minus() to reduce an amount" }
        val room = Int.MAX_VALUE - amount
        return Item(id, amount + minOf(room, delta))
    }

    fun minus(delta: Int): Item? {
        require(delta >= 0) { "use plus() to raise an amount" }
        return if (delta >= amount) null else Item(id, amount - delta)
    }

    fun withAmount(newAmount: Int): Item? = if (newAmount <= 0) null else Item(id, newAmount)

    override fun toString() = "Item($id${name?.let { " $it" } ?: ""} x$amount)"
}

object ItemStacking {
    private val cache = Int2BooleanOpenHashMap()

    fun noteOf(id: Int): Int? = RsDatabase.queryOne(
        "SELECT noteTemplate_old, noteData_old FROM items WHERE id = ?", id
    ) { rs ->
        val template = rs.getInt("noteTemplate_old")
        if (rs.wasNull()) null else {
            val data = rs.getInt("noteData_old")
            if (rs.wasNull()) null else data
        }
    }

    fun isNote(id: Int): Boolean = noteOf(id) != null

    fun definition(id: Int): ItemDefinition? {
        val resources = try {
            FilesystemResources.instance
        } catch (e: UninitializedPropertyAccessException) {
            throw IllegalStateException("FilesystemResources has not been constructed; item lookups are unavailable", e)
        }
        return resources.get<ItemDefinition>(id)
    }

    fun isStackable(id: Int): Boolean {
        if (cache.containsKey(id)) return cache.get(id)
        val def = definition(id)
        val stacks = when {
            def == null -> false
            def.stackable -> true
            isNote(id) -> true
            else -> false
        }
        cache.put(id, stacks)
        return stacks
    }

    fun invalidate() = cache.clear()
}

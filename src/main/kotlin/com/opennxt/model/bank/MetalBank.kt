package com.opennxt.model.bank

import com.opennxt.model.items.Item
import com.opennxt.model.items.ItemContainer

class MetalBank {
    companion object {
        const val CAPACITY = 49

        const val INV = 858
    }

    private val container = ItemContainer(CAPACITY, stackAll = true)

    data class Moved(val ids: Int, val units: Long, val leftBehind: Int)

    fun count(id: Int): Long = container.count(id)
    fun usedSlots(): Int = container.usedSlots()
    fun isEmpty(): Boolean = container.usedSlots() == 0

    fun materials(): ItemContainer = container

    fun contents(): List<Bank.BankedItem> = container.toArray().withIndex()
        .mapNotNull { (slot, item) -> item?.let { Bank.BankedItem(slot, it.id, it.amount) } }

    fun wireSlots(): List<Pair<Int, Int>?> {
        val array = container.toArray()
        val last = array.indexOfLast { it != null }
        return (0..last).map { i -> array[i]?.let { it.id to it.amount } }
    }

    fun restore(items: List<Bank.BankedItem>) {
        val seenSlots = HashSet<Int>()
        val seenIds = HashSet<Int>()
        items.forEach {
            require(it.slot in 0 until CAPACITY) { "metal bank slot ${it.slot} outside 0..${CAPACITY - 1}" }
            require(it.amount >= 1) { "metal bank slot ${it.slot} has amount ${it.amount}" }
            require(seenSlots.add(it.slot)) { "metal bank slot ${it.slot} appears twice" }
            require(seenIds.add(it.id)) { "item ${it.id} appears in two metal bank slots" }
        }
        container.clear()
        items.forEach { container[it.slot] = Item(it.id, it.amount) }
    }

    fun depositAll(from: ItemContainer, eligible: (Int) -> Boolean): Moved {
        val ids = LinkedHashSet<Int>()
        from.toArray().forEach { item -> if (item != null && eligible(item.id)) ids += item.id }
        var moved = 0
        var units = 0L
        var left = 0
        for (id in ids) {
            val held = from.count(id).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            if (held <= 0) continue
            if (container.isFull() && !container.contains(id)) { left++; continue }
            val taken = from.remove(id, held).removed
            val added = container.add(id, taken)
            if (added.remaining > 0) { from.add(id, added.remaining); left++ }
            if (added.added > 0) { moved++; units += added.added.toLong() }
        }
        return Moved(moved, units, left)
    }

    fun withdrawAll(to: ItemContainer): Moved {
        var moved = 0
        var units = 0L
        var left = 0
        for (slot in 0 until CAPACITY) {
            val item = container[slot] ?: continue
            val accepted = to.add(item.id, item.amount).added
            if (accepted > 0) {
                container.remove(item.id, accepted)
                moved++
                units += accepted.toLong()
            }
            if (accepted < item.amount) left++
        }
        return Moved(moved, units, left)
    }

    fun clear() = container.clear()

    override fun toString() = "MetalBank(${usedSlots()}/$CAPACITY slots used)"
}

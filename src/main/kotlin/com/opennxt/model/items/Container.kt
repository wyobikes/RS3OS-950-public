package com.opennxt.model.items

data class AddResult(val requested: Int, val added: Int) {
    val remaining: Int get() = requested - added
    val complete: Boolean get() = added == requested
    override fun toString() = "AddResult(requested=$requested, added=$added, remaining=$remaining)"
}

data class RemoveResult(val requested: Int, val removed: Int) {
    val shortfall: Int get() = requested - removed
    val complete: Boolean get() = removed == requested
    override fun toString() = "RemoveResult(requested=$requested, removed=$removed, shortfall=$shortfall)"
}

open class ItemContainer(val size: Int, val stackAll: Boolean = false) {
    companion object {
        const val INVENTORY_SIZE = 28

        fun inventory() = ItemContainer(INVENTORY_SIZE)
    }

    init {
        require(size > 0) { "container size must be positive: $size" }
    }

    private val slots = arrayOfNulls<Item>(size)

    fun stacks(id: Int): Boolean = stackAll || ItemStacking.isStackable(id)

    operator fun get(slot: Int): Item? {
        checkSlot(slot)
        return slots[slot]
    }

    fun isEmpty(slot: Int): Boolean = get(slot) == null

    operator fun set(slot: Int, item: Item?) {
        checkSlot(slot)
        slots[slot] = item
    }

    fun usedSlots(): Int = slots.count { it != null }

    fun freeSlots(): Int = size - usedSlots()

    fun isFull(): Boolean = freeSlots() == 0

    fun firstFreeSlot(): Int = slots.indexOfFirst { it == null }

    fun slotOf(id: Int): Int = slots.indexOfFirst { it != null && it.id == id }

    fun count(id: Int): Long = slots.sumOf { if (it != null && it.id == id) it.amount.toLong() else 0L }

    fun totalCount(): Long = slots.sumOf { it?.amount?.toLong() ?: 0L }

    fun contains(id: Int): Boolean = slotOf(id) >= 0

    fun contains(id: Int, amount: Int): Boolean = count(id) >= amount.toLong()

    fun contains(item: Item): Boolean = contains(item.id, item.amount)

    fun toArray(): Array<Item?> = slots.copyOf()

    fun items(): List<Item> = slots.filterNotNull()

    fun add(id: Int, amount: Int): AddResult {
        require(amount >= 0) { "cannot add a negative amount: $amount" }
        if (amount == 0) return AddResult(0, 0)

        if (stacks(id)) {
            val existing = slotOf(id)
            if (existing >= 0) {
                val current = slots[existing]!!
                val room = Int.MAX_VALUE - current.amount
                val give = minOf(room, amount)
                if (give > 0) slots[existing] = Item(id, current.amount + give)
                return AddResult(amount, give)
            }
            val free = firstFreeSlot()
            if (free < 0) return AddResult(amount, 0)
            slots[free] = Item(id, amount)
            return AddResult(amount, amount)
        }

        var placed = 0
        for (slot in slots.indices) {
            if (placed == amount) break
            if (slots[slot] == null) {
                slots[slot] = Item(id, 1)
                placed++
            }
        }
        return AddResult(amount, placed)
    }

    fun add(item: Item): AddResult = add(item.id, item.amount)

    fun remove(id: Int, amount: Int): RemoveResult {
        require(amount >= 0) { "cannot remove a negative amount: $amount" }
        if (amount == 0) return RemoveResult(0, 0)

        var left = amount
        for (slot in slots.indices) {
            if (left == 0) break
            val item = slots[slot] ?: continue
            if (item.id != id) continue
            val take = minOf(item.amount, left)
            slots[slot] = item.minus(take)
            left -= take
        }
        return RemoveResult(amount, amount - left)
    }

    fun remove(item: Item): RemoveResult = remove(item.id, item.amount)

    fun removeSlot(slot: Int): Item? {
        checkSlot(slot)
        val item = slots[slot]
        slots[slot] = null
        return item
    }

    fun clear() {
        slots.fill(null)
    }

    fun swap(from: Int, to: Int) {
        checkSlot(from)
        checkSlot(to)
        val tmp = slots[from]
        slots[from] = slots[to]
        slots[to] = tmp
    }

    fun insert(from: Int, to: Int) {
        checkSlot(from)
        checkSlot(to)
        if (from == to) return
        val moving = slots[from]
        if (from < to) {
            for (i in from until to) slots[i] = slots[i + 1]
        } else {
            for (i in from downTo to + 1) slots[i] = slots[i - 1]
        }
        slots[to] = moving
    }

    fun compact() {
        val packed = slots.filterNotNull()
        slots.fill(null)
        packed.forEachIndexed { i, item -> slots[i] = item }
    }

    private fun checkSlot(slot: Int) {
        if (slot < 0 || slot >= size) throw IndexOutOfBoundsException("slot $slot outside 0..${size - 1}")
    }

    override fun toString() =
        "ItemContainer(size=$size, used=${usedSlots()}, stackAll=$stackAll, ${items()})"
}

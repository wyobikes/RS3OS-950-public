package com.opennxt.model.bank

import com.opennxt.model.items.Item
import com.opennxt.model.items.ItemContainer
import com.opennxt.model.items.ItemStacking

class Bank {
    companion object {
        const val CAPACITY = 510

        const val MAIN_TAB = 1
        const val FIRST_TAB = 2
        const val LAST_TAB = 15
        const val TAB_COUNT = LAST_TAB - FIRST_TAB + 1
    }

    private val container = ItemContainer(CAPACITY, stackAll = true)

    private val tabSizes = IntArray(TAB_COUNT)

    fun tabSizes(): List<Int> = tabSizes.toList()

    fun tabSize(tab: Int): Int = if (tab in FIRST_TAB..LAST_TAB) tabSizes[tab - FIRST_TAB] else 0

    fun isTab(tab: Int): Boolean = tabSize(tab) > 0

    fun tabbedSlots(): Int = tabSizes.sum()

    fun tabStart(tab: Int): Int {
        require(tab in FIRST_TAB..LAST_TAB) { "tab $tab outside $FIRST_TAB..$LAST_TAB" }
        var start = 0
        for (t in FIRST_TAB until tab) start += tabSizes[t - FIRST_TAB]
        return start
    }

    fun tabOfSlot(slot: Int): Int {
        var end = 0
        for (i in 0 until TAB_COUNT) {
            end += tabSizes[i]
            if (slot < end) return FIRST_TAB + i
        }
        return MAIN_TAB
    }

    fun placeholderTab(): Int? = (FIRST_TAB..LAST_TAB).firstOrNull { tabSize(it) == 0 }

    fun savedTabs(): List<Int> = tabSizes.takeWhile { it > 0 }

    sealed class TabMoveResult {
        data class Moved(val id: Int, val fromTab: Int, val toTab: Int, val slot: Int, val collapsed: List<Int>) :
            TabMoveResult()

        data class EmptySlot(val slot: Int) : TabMoveResult()

        data class NoSuchTab(val tab: Int) : TabMoveResult()
    }

    sealed class DeleteTabResult {
        data class Deleted(val tab: Int, val itemsMoved: Int) : DeleteTabResult()

        data class NoSuchTab(val tab: Int) : DeleteTabResult()
    }

    fun moveToTab(slot: Int, tab: Int): TabMoveResult {
        val item = itemAt(slot) ?: return TabMoveResult.EmptySlot(slot)
        if (tab != MAIN_TAB && !isTab(tab) && tab != placeholderTab()) return TabMoveResult.NoSuchTab(tab)
        val fromTab = tabOfSlot(slot)
        val collapsed = relocate(slot, tab)
        val now = slotOf(item.id)
        return TabMoveResult.Moved(item.id, fromTab, tabOfSlot(now), now, collapsed)
    }

    fun deleteTab(tab: Int): DeleteTabResult {
        if (!isTab(tab)) return DeleteTabResult.NoSuchTab(tab)
        val start = tabStart(tab)
        val size = tabSize(tab)
        val slots = container.toArray().toMutableList()
        val block = ArrayList(slots.subList(start, start + size))
        repeat(size) { slots.removeAt(start) }
        for (i in tab - FIRST_TAB until TAB_COUNT - 1) tabSizes[i] = tabSizes[i + 1]
        tabSizes[TAB_COUNT - 1] = 0
        val items = block.filterNotNull()
        val at = maxOf(slots.indexOfLast { it != null } + 1, tabbedSlots()).coerceAtMost(slots.size)
        slots.addAll(at, items)
        while (slots.size < CAPACITY) slots.add(null)
        writeBack(slots)
        return DeleteTabResult.Deleted(tab, items.size)
    }

    fun compact(): Boolean {
        val before = container.toArray()
        val counts = IntArray(TAB_COUNT)
        var index = 0
        for (i in 0 until TAB_COUNT) {
            for (s in index until index + tabSizes[i]) if (before[s] != null) counts[i]++
            index += tabSizes[i]
        }
        val oldSizes = tabSizes.copyOf()
        val packed = before.filterNotNull()
        container.clear()
        packed.forEachIndexed { i, item -> container[i] = item }
        tabSizes.fill(0)
        counts.filter { it > 0 }.forEachIndexed { i, s -> tabSizes[i] = s }
        return !oldSizes.contentEquals(tabSizes) || !before.contentEquals(container.toArray())
    }

    private fun relocate(from: Int, target: Int): List<Int> {
        val slots = container.toArray().toMutableList()
        val srcTab = tabOfSlot(from)
        val moving = slots.removeAt(from)
        if (srcTab != MAIN_TAB) tabSizes[srcTab - FIRST_TAB]--
        val at = if (target == MAIN_TAB) maxOf(slots.indexOfLast { it != null } + 1, tabbedSlots())
        else tabStart(target) + tabSize(target)
        slots.add(at.coerceAtMost(slots.size), moving)
        if (target != MAIN_TAB) tabSizes[target - FIRST_TAB]++
        val collapsed = if (srcTab != MAIN_TAB && tabSize(srcTab) == 0) listOf(srcTab) else emptyList()
        val kept = tabSizes.filter { it > 0 }
        tabSizes.fill(0)
        kept.forEachIndexed { i, s -> tabSizes[i] = s }
        writeBack(slots)
        return collapsed
    }

    private fun writeBack(slots: List<Item?>) {
        container.clear()
        slots.forEachIndexed { i, item -> if (item != null && i < CAPACITY) container[i] = item }
    }

    private fun placeNew(id: Int, amount: Int, intoTab: Int) {
        val end = tabbedSlots()
        val free = (end until CAPACITY).firstOrNull { container[it] == null }
            ?: (0 until end).first { container[it] == null }
        container[free] = Item(id, amount)
        val target = if (isTab(intoTab)) intoTab else MAIN_TAB
        if (target == MAIN_TAB && free >= end) return
        relocate(free, target)
    }

    sealed class DepositResult {
        data class Deposited(val id: Int, val requested: Int, val deposited: Int, val bankedTotal: Long) :
            DepositResult() {
            val complete: Boolean get() = deposited == requested
        }

        data class UnknownItem(val id: Int) : DepositResult()

        data class NothingToDeposit(val id: Int) : DepositResult()

        data class BankFull(val id: Int) : DepositResult()
    }

    sealed class WithdrawResult {
        data class Withdrawn(val id: Int, val requested: Int, val withdrawn: Int, val stillBanked: Long) :
            WithdrawResult() {
            val shortfall: Int get() = requested - withdrawn
            val complete: Boolean get() = withdrawn == requested
        }

        data class UnknownItem(val id: Int) : WithdrawResult()

        data class NotBanked(val id: Int) : WithdrawResult()

        data class TargetFull(val id: Int) : WithdrawResult()
    }

    fun usedSlots(): Int = container.usedSlots()
    fun freeSlots(): Int = container.freeSlots()
    fun isFull(): Boolean = container.isFull()
    fun count(id: Int): Long = container.count(id)
    fun contains(id: Int): Boolean = container.contains(id)
    fun isEmpty(): Boolean = container.usedSlots() == 0

    fun slotOf(id: Int): Int = container.slotOf(id)

    fun itemAt(slot: Int): BankedItem? {
        if (slot < 0 || slot >= CAPACITY) return null
        val item = container[slot] ?: return null
        return BankedItem(slot, item.id, item.amount)
    }

    data class BankedItem(val slot: Int, val id: Int, val amount: Int)

    fun contents(): List<BankedItem> = container.toArray().withIndex()
        .mapNotNull { (slot, item) -> item?.let { BankedItem(slot, it.id, it.amount) } }

    fun restore(items: List<BankedItem>, tabs: List<Int> = emptyList()) {
        val seenSlots = HashSet<Int>()
        val seenIds = HashSet<Int>()
        items.forEach {
            require(it.slot in 0 until CAPACITY) { "bank slot ${it.slot} outside 0..${CAPACITY - 1}" }
            require(it.amount >= 1) { "bank slot ${it.slot} has amount ${it.amount}; an empty slot is omitted, not zero" }
            require(seenSlots.add(it.slot)) { "bank slot ${it.slot} appears twice in the restored layout" }
            require(seenIds.add(it.id)) { "item ${it.id} appears in two bank slots; a bank holds one slot per id" }
        }
        require(tabs.size <= TAB_COUNT) { "${tabs.size} tabs; the client has $TAB_COUNT" }
        tabs.forEach { require(it >= 1) { "a restored tab has size $it; an empty tab is dropped, not stored" } }
        require(tabs.sumOf { it.toLong() } <= CAPACITY) { "tabs claim ${tabs.sumOf { it.toLong() }} of $CAPACITY slots" }
        container.clear()
        items.forEach { container[it.slot] = Item(it.id, it.amount) }
        tabSizes.fill(0)
        tabs.forEachIndexed { i, size -> tabSizes[i] = size }
    }

    fun deposit(id: Int, amount: Int, from: ItemContainer, intoTab: Int = MAIN_TAB): DepositResult {
        require(amount >= 0) { "cannot deposit a negative amount: $amount" }
        if (ItemStacking.definition(id) == null) return DepositResult.UnknownItem(id)

        val held = from.count(id).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        if (held == 0) return DepositResult.NothingToDeposit(id)
        val moving = minOf(amount, held)
        if (moving == 0) return DepositResult.Deposited(id, amount, 0, count(id))

        if (container.isFull() && !container.contains(id)) return DepositResult.BankFull(id)

        if (container.contains(id) || (tabbedSlots() == 0 && !isTab(intoTab))) {
            val taken = from.remove(id, moving).removed
            val added = container.add(id, taken)
            if (added.remaining > 0) from.add(id, added.remaining)
            return DepositResult.Deposited(id, amount, added.added, count(id))
        }
        val taken = from.remove(id, moving).removed
        placeNew(id, taken, intoTab)
        return DepositResult.Deposited(id, amount, taken, count(id))
    }

    fun withdraw(id: Int, amount: Int, to: ItemContainer): WithdrawResult {
        require(amount >= 0) { "cannot withdraw a negative amount: $amount" }
        if (ItemStacking.definition(id) == null) return WithdrawResult.UnknownItem(id)

        val banked = count(id)
        if (banked == 0L) return WithdrawResult.NotBanked(id)
        val wanted = minOf(amount.toLong(), banked).toInt()
        if (wanted == 0) return WithdrawResult.Withdrawn(id, amount, 0, banked)

        val accepted = to.add(id, wanted).added
        if (accepted == 0) return WithdrawResult.TargetFull(id)
        container.remove(id, accepted)
        return WithdrawResult.Withdrawn(id, amount, accepted, count(id))
    }

    fun clear() {
        container.clear()
        tabSizes.fill(0)
    }

    override fun toString() = "Bank(${usedSlots()}/$CAPACITY slots used, ${savedTabs().size} tab(s))"
}

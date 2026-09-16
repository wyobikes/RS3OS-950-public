package com.opennxt.content.impl

import com.opennxt.content.ContentPlayer
import com.opennxt.content.ContentRegistry
import com.opennxt.content.LocContext
import com.opennxt.model.bank.Bank
import com.opennxt.model.bank.MetalBank
import com.opennxt.model.items.ItemContainer
import com.opennxt.resources.sqlite.RsDatabase
import mu.KotlinLogging

object MetalBanks {
    private val logger = KotlinLogging.logger { }

    const val INTERFACE = Smithing.METAL_BANK_INTERFACE
    const val INV = MetalBank.INV
    const val GAMEFRAME = 1477
    const val MOUNT = 726
    const val CLOSE_COMPONENT = 121
    const val BANK_BUTTON_COMPONENT = 146

    const val DEPOSIT_PREFIX = "deposit-all (into metal bank)"
    const val WITHDRAW_PREFIX = "withdraw-all (from metal bank)"

    const val DEPOSITED_MESSAGE = "You deposit some materials."
    const val NOTHING_MESSAGE = "You have no ores or bars to deposit."
    const val EMPTY_MESSAGE = "Your metal bank is empty."
    const val NO_ROOM_MESSAGE = "You don't have enough inventory space to withdraw that many."

    val smithingReads: Boolean get() = System.getProperty("opennxt.smithing.metalbank") != "off"

    interface Sink {
        fun openSub(interfaceId: Int, parent: Int, component: Int, walkable: Boolean)
        fun updateInvFull(inv: Int, slots: List<Pair<Int, Int>?>)
        fun stopTransmit(inv: Int)
        fun closeSub(parent: Int, component: Int)
        fun message(text: String)
        fun resendBackpack()
    }

    @Volatile var sinkSupplier: (ContentPlayer) -> Sink? = { null }
    @Volatile var backpackSupplier: (ContentPlayer) -> ItemContainer = { it.inventory }

    private val banks = java.util.concurrent.ConcurrentHashMap<String, MetalBank>()
    private val screenOpen: MutableMap<ContentPlayer, Boolean> =
        java.util.Collections.synchronizedMap(java.util.WeakHashMap())

    private var deposits = 0
    private var withdrawals = 0
    private var refusals = 0
    private var opens = 0
    private var closes = 0

    fun depositCount(): Int = deposits
    fun withdrawCount(): Int = withdrawals
    fun refusalCount(): Int = refusals
    fun screenOpens(): Int = opens
    fun screenCloses(): Int = closes
    fun isScreenOpen(player: ContentPlayer): Boolean = screenOpen[player] == true

    fun metalBankFor(name: String): MetalBank = banks.computeIfAbsent(Banks.bankKey(name)) { MetalBank() }

    fun restore(name: String, items: List<Bank.BankedItem>): MetalBank {
        val bank = metalBankFor(name)
        bank.restore(items)
        return bank
    }

    fun savedContents(name: String): List<Bank.BankedItem> = metalBankFor(name).contents()

    @Volatile private var eligibleCache: Set<Int>? = null

    fun eligibleIds(): Set<Int> {
        eligibleCache?.let { return it }
        val out = runCatching {
            Smithing.smeltRecipes.values.flatMap { r -> listOf(r.barId) + r.materials.map { it.itemId } }.toSortedSet()
        }.getOrDefault(sortedSetOf())
        eligibleCache = out
        return out
    }

    fun isEligible(id: Int): Boolean = id in eligibleIds()

    fun metalBankLocOptions(): List<Pair<Int, String>> = runCatching {
        RsDatabase.queryAll(
            "SELECT id, value FROM locs_attr WHERE value LIKE '%etal bank%' " +
                "UNION ALL SELECT id, actions_0 FROM locs WHERE actions_0 LIKE '%etal bank%' ORDER BY 1, 2"
        ) { it.getInt(1) to (it.getString(2) ?: "").trim('"') }
    }.getOrDefault(emptyList())

    @Volatile private var optionCache: Map<Int, Set<String>>? = null

    fun optionsByLoc(): Map<Int, Set<String>> {
        optionCache?.let { return it }
        val out = LinkedHashMap<Int, MutableSet<String>>()
        for ((id, label) in metalBankLocOptions()) {
            val lower = label.lowercase()
            if (lower.startsWith(DEPOSIT_PREFIX) || lower.startsWith(WITHDRAW_PREFIX)) out.getOrPut(id) { LinkedHashSet() } += label
        }
        optionCache = out
        return out
    }

    fun depositLocs(): Int = optionsByLoc().values.count { labels -> labels.any { it.lowercase().startsWith(DEPOSIT_PREFIX) } }

    fun withdrawLocs(): Int = optionsByLoc().values.count { labels -> labels.any { it.lowercase().startsWith(WITHDRAW_PREFIX) } }

    fun isMetalBankOption(locId: Int, action: String): Boolean = optionsByLoc()[locId]?.contains(action) == true

    fun handleLocOption(player: ContentPlayer, locId: Int, action: String, where: String): MetalBank.Moved? {
        if (!isMetalBankOption(locId, action)) return null
        return if (action.lowercase().startsWith(DEPOSIT_PREFIX)) depositAll(player, where) else withdrawAll(player, where)
    }

    fun install(): Int {
        optionCache = null
        val locs = optionsByLoc().size
        Smithing.metalBankSupplier = { player -> if (smithingReads) metalBankFor(player.name).materials() else null }
        Smithing.metalBankResend = { player -> resend(player) }
        logger.info {
            "metal bank: deposit on ${depositLocs()} and withdraw on ${withdrawLocs()} loc(s), " +
                "${eligibleIds().size} ore/bar ids, smithing reads it: $smithingReads"
        }
        return locs
    }

    fun depositAll(player: ContentPlayer, where: String): MetalBank.Moved {
        val bank = metalBankFor(player.name)
        val moved = bank.depositAll(backpackSupplier(player), ::isEligible)
        val sink = sinkSupplier(player)
        if (moved.units > 0) {
            deposits++
            sink?.resendBackpack()
            sink?.updateInvFull(INV, bank.wireSlots())
            sink?.message(DEPOSITED_MESSAGE)
        } else {
            refusals++
            sink?.message(NOTHING_MESSAGE)
        }
        logger.info {
            "metal bank: ${player.name} deposit-all at $where moved ${moved.units} item(s) over ${moved.ids} id(s), " +
                "${moved.leftBehind} left behind; metal bank ${bank.usedSlots()}/${MetalBank.CAPACITY}"
        }
        return moved
    }

    fun withdrawAll(player: ContentPlayer, where: String): MetalBank.Moved {
        val bank = metalBankFor(player.name)
        val sink = sinkSupplier(player)
        if (bank.isEmpty()) {
            refusals++
            sink?.message(EMPTY_MESSAGE)
            return MetalBank.Moved(0, 0, 0)
        }
        val moved = bank.withdrawAll(backpackSupplier(player))
        if (moved.units > 0) {
            withdrawals++
            sink?.resendBackpack()
            sink?.updateInvFull(INV, bank.wireSlots())
        } else refusals++
        if (moved.leftBehind > 0) sink?.message(NO_ROOM_MESSAGE)
        logger.info {
            "metal bank: ${player.name} withdraw-all at $where moved ${moved.units} item(s) over ${moved.ids} id(s), " +
                "${moved.leftBehind} stack(s) stayed; metal bank ${bank.usedSlots()}/${MetalBank.CAPACITY}"
        }
        return moved
    }

    fun resend(player: ContentPlayer): Boolean {
        val sink = sinkSupplier(player) ?: return false
        sink.updateInvFull(INV, metalBankFor(player.name).wireSlots())
        return true
    }

    fun openScreen(player: ContentPlayer): Boolean {
        val sink = sinkSupplier(player) ?: return false
        sink.updateInvFull(INV, metalBankFor(player.name).wireSlots())
        if (screenOpen[player] != true) {
            sink.openSub(INTERFACE, GAMEFRAME, MOUNT, walkable = false)
            screenOpen[player] = true
            opens++
        }
        logger.info { "metal bank: ${player.name} opened interface $INTERFACE at $GAMEFRAME:$MOUNT" }
        return true
    }

    fun closeScreen(player: ContentPlayer): Boolean {
        if (screenOpen[player] != true) return false
        val sink = sinkSupplier(player) ?: return false
        sink.stopTransmit(INV)
        sink.closeSub(GAMEFRAME, MOUNT)
        screenOpen[player] = false
        closes++
        return true
    }

    sealed class ButtonResult {
        object NotOurs : ButtonResult()
        data class Opened(val usedSlots: Int) : ButtonResult()
        object Closed : ButtonResult()
        data class Refused(val why: String) : ButtonResult()
    }

    fun handleButton(player: ContentPlayer, interfaceId: Int, component: Int, op: Int): ButtonResult {
        if (interfaceId == Banks.BANK_INTERFACE && component == BANK_BUTTON_COMPONENT) {
            if (!Banks.isScreenOpen(player)) { refusals++; return ButtonResult.Refused("no bank screen is open") }
            if (op != 1) { refusals++; return ButtonResult.Refused("517:$component op $op is not 'Open Metal Bank'") }
            return if (openScreen(player)) ButtonResult.Opened(metalBankFor(player.name).usedSlots())
            else { refusals++; ButtonResult.Refused("no sink for this player") }
        }
        if (interfaceId != INTERFACE) return ButtonResult.NotOurs
        if (screenOpen[player] != true) { refusals++; return ButtonResult.Refused("interface $INTERFACE is not open for this player") }
        if (component == CLOSE_COMPONENT) {
            closeScreen(player)
            return ButtonResult.Closed
        }
        refusals++
        return ButtonResult.Refused("$INTERFACE:$component op $op is handled client-side")
    }

    fun clear() {
        banks.clear()
        synchronized(screenOpen) { screenOpen.clear() }
        eligibleCache = null
        deposits = 0; withdrawals = 0; refusals = 0; opens = 0; closes = 0
    }
}

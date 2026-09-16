package com.opennxt.content.impl

import com.opennxt.content.ContentPlayer
import com.opennxt.content.ContentRegistry
import com.opennxt.content.LocContext
import com.opennxt.content.NpcContext
import com.opennxt.model.bank.Bank
import com.opennxt.model.items.ItemContainer
import com.opennxt.resources.sqlite.RsDatabase
import mu.KotlinLogging

object Banks {
    private val logger = KotlinLogging.logger { }

    const val BANK = "Bank"
    const val USE = "Use"

    const val OPENED = "bank-opened"

    data class BankEvent(val player: String, val bank: Bank, val locId: Int, val locName: String?,
                         val action: String, val x: Int, val z: Int, val plane: Int,
                         val npcId: Int = -1,
                         val npcIndex: Int = -1) {
        val fromNpc: Boolean get() = npcId >= 0
    }

    private val events = ArrayList<BankEvent>()

    fun eventCount(): Int = events.size
    fun lastEvent(): BankEvent? = events.lastOrNull()

    fun clear() {
        events.clear()
        banks.clear()
        synchronized(screenOpen) { screenOpen.clear() }
        opens = 0
        closes = 0
        lastScreen = null
        deposits = 0
        withdrawals = 0
        refusals = 0
        quantityMode.clear()
        synchronized(armedMasks) { armedMasks.clear() }
        quantityButtonCache = null
        synchronized(viewedTab) { viewedTab.clear() }
        tabActions = 0
    }

    private val banks = java.util.concurrent.ConcurrentHashMap<String, Bank>()

    fun bankKey(name: String): String = name.lowercase()

    var bankSupplier: (ContentPlayer) -> Bank =
        { player -> banks.computeIfAbsent(bankKey(player.name)) { Bank() } }

    fun bankOf(player: ContentPlayer): Bank = bankSupplier(player)

    fun bankForAccount(username: String): Bank = bankOf(ContentPlayer(name = username))

    fun restoreBank(username: String, items: List<Bank.BankedItem>, tabs: List<Int> = emptyList()): Bank {
        val bank = bankForAccount(username)
        val had = bank.usedSlots()
        bank.restore(items, tabs)
        if (had > 0) {
            logger.info {
                "bank for '$username' replaced: $had slot(s) in memory, ${items.size} slot(s) from the save"
            }
        }
        return bank
    }

    fun onBank(ctx: LocContext): Any? {
        events.add(
            BankEvent(
                player = ctx.player.name,
                bank = bankOf(ctx.player),
                locId = ctx.locId,
                locName = ctx.definition.name,
                action = ctx.action,
                x = ctx.x, z = ctx.z, plane = ctx.plane
            )
        )
        openScreen(ctx.player)
        return OPENED
    }

    fun onBankNpc(ctx: NpcContext): Any? {
        events.add(
            BankEvent(
                player = ctx.player.name,
                bank = bankOf(ctx.player),
                locId = -1,
                locName = ctx.definition.name,
                action = ctx.action,
                x = ctx.x, z = ctx.z, plane = ctx.plane,
                npcId = ctx.npcId, npcIndex = ctx.npcIndex
            )
        )
        openScreen(ctx.player)
        return OPENED
    }

    const val BANK_INTERFACE = 517

    const val GAMEFRAME = 1477

    const val BANK_MOUNT = 693

    const val BANK_INV = 95

    const val BANK_INV_CACHE_SIZE = 1820

    val BANK_INV_SLOTS: Int =
        System.getProperty("opennxt.experiment.banks.invSlots")?.toIntOrNull() ?: BANK_INV_CACHE_SIZE

    const val CLOSE_COMPONENT = 317

    const val OP1_MASK = 2

    val uiEnabled: Boolean get() = System.getProperty("opennxt.experiment.banks.ui") == "true"

    const val NPC_DISPATCH_SWITCH = "opennxt.experiment.npc.dispatch"

    val npcClickReaches: Boolean get() = System.getProperty(NPC_DISPATCH_SWITCH) == "true"

    const val PROVENANCE_SHORT =
        "interface 517 at 1477:693, inv 95, 1820 slots"

    sealed class ScreenResult {
        data class Opened(val interfaceId: Int, val parent: Int, val component: Int,
                          val inv: Int, val slots: Int, val occupied: Int, val resent: Boolean) : ScreenResult()

        object Disabled : ScreenResult()

        object NoSink : ScreenResult()
    }

    interface Sink {
        fun openSub(interfaceId: Int, parent: Int, component: Int, walkable: Boolean)

        fun updateInvFull(inv: Int, slots: List<Pair<Int, Int>?>)

        fun setEvents(interfaceId: Int, component: Int, fromSlot: Int, toSlot: Int, mask: Int)

        fun closeSub(parent: Int, component: Int)

        fun setVarp(id: Int, value: Int) {}

        fun varpValue(id: Int): Int = 0

        fun runClientScript(script: Int, args: List<Any>) {}
    }

    var sinkSupplier: (ContentPlayer) -> Sink? = { null }

    private val screenOpen: MutableMap<ContentPlayer, Boolean> =
        java.util.Collections.synchronizedMap(java.util.WeakHashMap())

    private var opens = 0
    private var closes = 0
    private var lastScreen: ScreenResult? = null

    fun screenOpens(): Int = opens
    fun screenCloses(): Int = closes
    fun lastScreenResult(): ScreenResult? = lastScreen
    fun isScreenOpen(player: ContentPlayer): Boolean = screenOpen[player] == true

    fun positionalSlots(bank: Bank, size: Int = BANK_INV_SLOTS): List<Pair<Int, Int>?> {
        val out = arrayOfNulls<Pair<Int, Int>>(size)
        var dropped = 0
        bank.contents().forEach { item ->
            if (item.slot in 0 until size) out[item.slot] = item.id to item.amount else dropped++
        }
        if (dropped > 0) {
            logger.warn {
                "bank screen: $dropped slot(s) beyond the $size sent for inv $BANK_INV were not sent " +
                    "(-Dopennxt.experiment.banks.invSlots)"
            }
        }
        return out.toList()
    }

    fun openScreen(player: ContentPlayer): ScreenResult {
        if (!uiEnabled) {
            lastScreen = ScreenResult.Disabled
            return ScreenResult.Disabled
        }
        val sink = sinkSupplier(player) ?: run {
            lastScreen = ScreenResult.NoSink
            return ScreenResult.NoSink
        }

        val bank = bankOf(player)
        val slots = positionalSlots(bank)
        val already = screenOpen[player] == true
        if (!already) viewedTab[player] = Bank.MAIN_TAB
        sendTabState(player, sink, bank, open = true)
        sink.updateInvFull(BANK_INV, slots)

        if (!already) {
            sink.openSub(BANK_INTERFACE, GAMEFRAME, BANK_MOUNT, walkable = true)
            sink.setEvents(BANK_INTERFACE, CLOSE_COMPONENT, -1, -1, OP1_MASK)
            sink.setEvents(BANK_INTERFACE, ITEM_LAYER_COMPONENT, 0, ITEM_LAYER_SLOTS, ITEM_LAYER_MASK)
            recordArm(player, ITEM_LAYER_COMPONENT, ITEM_LAYER_MASK)
            sink.setEvents(BANK_INTERFACE, BACKPACK_LAYER_COMPONENT, 0, BACKPACK_LAYER_SLOTS,
                BACKPACK_LAYER_MASK)
            recordArm(player, BACKPACK_LAYER_COMPONENT, BACKPACK_LAYER_MASK)
            sink.setEvents(BANK_INTERFACE, TAB_ROW_COMPONENT, 0, TAB_SLOTS_TO, TAB_ROW_MASK)
            recordArm(player, TAB_ROW_COMPONENT, TAB_ROW_MASK)
            sink.setEvents(BANK_INTERFACE, TAB_DROP_COMPONENT, 0, TAB_SLOTS_TO, TAB_DROP_MASK)
            sink.setEvents(BANK_INTERFACE, TAB_DRAG_COMPONENT, 0, TAB_SLOTS_TO, TAB_DRAG_MASK)
            sink.setEvents(BANK_INTERFACE, TAB_AREA_COMPONENT, 0, TAB_SLOTS_TO, TAB_DROP_MASK)
            sink.setEvents(BANK_INTERFACE, TAB_ICON_COMPONENT, 0, TAB_SLOTS_TO, TAB_DROP_MASK)
            screenOpen[player] = true
            opens++
        }

        val result = ScreenResult.Opened(
            interfaceId = BANK_INTERFACE, parent = GAMEFRAME, component = BANK_MOUNT,
            inv = BANK_INV, slots = slots.size, occupied = bank.usedSlots(), resent = already
        )
        lastScreen = result
        logger.info {
            "bank screen: ${player.name} " + (if (already) "re-sent" else "opened") +
                " interface $BANK_INTERFACE at $GAMEFRAME:$BANK_MOUNT with inv $BANK_INV " +
                "(${slots.size} slots, ${bank.usedSlots()} occupied)"
        }
        return result
    }

    fun closeScreen(player: ContentPlayer): Boolean {
        if (screenOpen[player] != true) return false
        val sink = sinkSupplier(player) ?: return false
        val bank = bankOf(player)
        bank.compact()
        sendTabState(player, sink, bank, open = false)
        sink.closeSub(GAMEFRAME, BANK_MOUNT)
        screenOpen[player] = false
        closes++
        logger.info { "bank screen: ${player.name} closed $GAMEFRAME:$BANK_MOUNT (was interface $BANK_INTERFACE)" }
        return true
    }

    fun handleClose(player: ContentPlayer, interfaceId: Int, component: Int): Boolean {
        if (interfaceId != BANK_INTERFACE || component != CLOSE_COMPONENT) return false
        return closeScreen(player)
    }

    const val DEPOSIT_BACKPACK_COMPONENT = 39

    const val DEPOSIT_WORN_COMPONENT = 42

    const val DEPOSIT_FAMILIAR_COMPONENT = 45

    const val DEPOSIT_COINPOUCH_COMPONENT = 48

    const val ITEM_LAYER_COMPONENT = 201

    const val TAB_STRIP_COMPONENT = 165

    const val TAB_ROW_COMPONENT = 169
    const val TAB_ROW_MASK = 2097166
    const val TAB_DROP_COMPONENT = 171
    const val TAB_DROP_MASK = 2097152
    const val TAB_DRAG_COMPONENT = 170
    const val TAB_DRAG_MASK = 8388608
    const val TAB_SLOTS_TO = 15

    val TAB_OPS: Map<Int, String> = mapOf(1 to "View tab", 2 to "Delete tab", 3 to "Customise tab")

    val TAB_SIZE_VARPS: IntArray = intArrayOf(4759, 4760, 4761, 4762, 8988, 8989, 8990)
    const val BANK_USED_VARP = 8971
    const val BANK_STATE_VARP = 110
    const val VIEWED_TAB_SHIFT = 27
    const val VIEWED_TAB_BITS = 0xF
    const val BANK_OPEN_BIT = 31
    const val QUANTITY_VARP = 8958
    const val AUTO_SWITCH_BIT = 4
    const val TAB_HINT_SCRIPT = 7774
    const val TAB_HINT_TEXT = "Drag an item onto this icon to create a new tab."

    const val TAB_AREA_COMPONENT = 205
    const val TAB_ICON_COMPONENT = 206

    val TAB_DROP_TARGETS: Set<Int> = setOf(TAB_ROW_COMPONENT, TAB_AREA_COMPONENT, TAB_ICON_COMPONENT)

    fun tabVarps(bank: Bank): List<Pair<Int, Int>> = TAB_SIZE_VARPS.mapIndexed { i, varp ->
        varp to ((bank.tabSize(Bank.FIRST_TAB + 2 * i) and 0xFFFF) or ((bank.tabSize(Bank.FIRST_TAB + 2 * i + 1) and 0xFFFF) shl 16))
    }

    fun bankStateValue(current: Int, viewed: Int, open: Boolean): Int {
        val cleared = current and (VIEWED_TAB_BITS shl VIEWED_TAB_SHIFT).inv() and (1 shl BANK_OPEN_BIT).inv()
        return cleared or ((viewed and VIEWED_TAB_BITS) shl VIEWED_TAB_SHIFT) or (if (open) 1 shl BANK_OPEN_BIT else 0)
    }

    const val NO_ITEM = 0xFFFFFF

    const val DEFAULT_517 =
        "517 events: 517:201 slots 0..1820 mask 11012094, " +
            "517:15 slots 0..27 mask 14682110 (inv 93), both enabling ops 1..10"

    const val ITEM_LAYER_SLOTS = 1820

    const val ITEM_LAYER_MASK = 11012094

    const val BACKPACK_LAYER_COMPONENT = 15

    const val BACKPACK_LAYER_SLOTS = 27

    const val BACKPACK_LAYER_MASK = 14682110

    const val QUANTITY_TOOLTIP = "Change the default number of items to move to "

    const val ALL = Int.MAX_VALUE

    const val UNSUPPORTED = -1

    const val USE_QUANTITY_MODE = -2

    val ROW_AMOUNTS: List<Int> = listOf(USE_QUANTITY_MODE, 1, 5, 10, ALL, UNSUPPORTED)

    data class Containers(val backpack: ItemContainer? = null, val worn: ItemContainer? = null, val coinPouch: CoinPouch? = null)

    sealed class ButtonResult {
        object NotOurs : ButtonResult()

        data class Deposited(val component: Int, val source: String, val ids: Int, val units: Long,
                             val leftBehind: Int, val bankSlots: Int) : ButtonResult()

        data class Withdrew(val id: Int, val slot: Int, val requested: Int, val withdrawn: Int,
                            val stillBanked: Long) : ButtonResult()

        data class DepositedOne(val id: Int, val slot: Int, val requested: Int, val moved: Int,
                                val bankedTotal: Long, val bankSlots: Int) : ButtonResult()

        data class QuantityChanged(val component: Int, val was: Int, val now: Int) : ButtonResult()

        data class Refused(val component: Int, val why: String, val message: String? = null) : ButtonResult()

        data class TabChanged(val component: Int, val op: Int, val tab: Int, val detail: String) : ButtonResult()
    }

    private val viewedTab: MutableMap<ContentPlayer, Int> =
        java.util.Collections.synchronizedMap(java.util.WeakHashMap())

    fun viewedTabOf(player: ContentPlayer): Int = viewedTab[player] ?: Bank.MAIN_TAB

    private var tabActions = 0

    fun tabActionCount(): Int = tabActions

    private fun sendTabState(player: ContentPlayer, sink: Sink, bank: Bank, open: Boolean) {
        tabVarps(bank).forEach { (varp, value) -> sink.setVarp(varp, value) }
        sink.setVarp(BANK_USED_VARP, bank.usedSlots())
        sink.setVarp(BANK_STATE_VARP, bankStateValue(sink.varpValue(BANK_STATE_VARP), viewedTabOf(player), open))
    }

    private fun shiftViewAfterRemoval(player: ContentPlayer, removed: Int) {
        val viewed = viewedTabOf(player)
        if (viewed == removed) viewedTab[player] = Bank.MAIN_TAB
        else if (viewed > removed) viewedTab[player] = viewed - 1
    }

    private var deposits = 0
    private var withdrawals = 0
    private var refusals = 0

    fun depositCount(): Int = deposits
    fun withdrawCount(): Int = withdrawals
    fun refusalCount(): Int = refusals

    @Volatile
    private var quantityButtonCache: Map<Int, Int>? = null

    fun quantityButtons(): Map<Int, Int> {
        quantityButtonCache?.let { return it }
        val out = LinkedHashMap<Int, Int>()
        runCatching {
            RsDatabase.queryAll(
                "SELECT id, value FROM interfaces_attr WHERE field = 'scripts' AND id BETWEEN " +
                    "${BANK_INTERFACE shl 16} AND ${(BANK_INTERFACE shl 16) or 0xffff} ORDER BY id"
            ) { it.getInt(1) to (it.getString(2) ?: "") }
        }.getOrDefault(emptyList()).forEach { (id, blob) ->
            val at = blob.indexOf(QUANTITY_TOOLTIP)
            if (at < 0) return@forEach
            val tail = blob.substring(at + QUANTITY_TOOLTIP.length).substringBefore('.').trim()
            val amount = when {
                tail.equals("all", ignoreCase = true) -> ALL
                tail.toIntOrNull() != null && tail.toInt() >= 1 -> tail.toInt()
                else -> UNSUPPORTED
            }
            out[id and 0xffff] = amount
        }
        quantityButtonCache = out
        return out
    }

    private val quantityMode: MutableMap<ContentPlayer, Int> =
        java.util.Collections.synchronizedMap(java.util.WeakHashMap())

    const val DEFAULT_QUANTITY = 1

    fun quantityOf(player: ContentPlayer): Int = quantityMode[player] ?: DEFAULT_QUANTITY

    private val armedMasks: MutableMap<ContentPlayer, MutableMap<Int, Int>> =
        java.util.Collections.synchronizedMap(java.util.WeakHashMap())

    fun armedMaskFor(player: ContentPlayer, component: Int): Int? =
        synchronized(armedMasks) { armedMasks[player]?.get(component) }

    private fun recordArm(player: ContentPlayer, component: Int, mask: Int) {
        synchronized(armedMasks) { armedMasks.getOrPut(player) { HashMap() }[component] = mask }
    }

    fun rowFor(player: ContentPlayer, component: Int, op: Int): Int? =
        ItemOps.rowForOp(armedMaskFor(player, component), op)

    private fun amountForRow(player: ContentPlayer, row: Int?): Int? {
        val declared = row?.let { ROW_AMOUNTS.getOrNull(it) } ?: return null
        val amount = if (declared == USE_QUANTITY_MODE) quantityOf(player) else declared
        return if (amount == UNSUPPORTED || amount < 1) null else amount
    }

    fun refresh(player: ContentPlayer): Boolean {
        if (screenOpen[player] != true) return false
        val sink = sinkSupplier(player) ?: return false
        val bank = bankOf(player)
        sink.updateInvFull(BANK_INV, positionalSlots(bank))
        sendTabState(player, sink, bank, open = true)
        return true
    }

    fun depositCoinPouch(player: ContentPlayer, pouch: CoinPouch, component: Int = DEPOSIT_COINPOUCH_COMPONENT): ButtonResult {
        val held = pouch.amount()
        if (held <= 0) {
            refusals++
            return ButtonResult.Refused(component, "the money pouch is empty", "You have no coins in your money pouch.")
        }
        val bank = bankOf(player)
        val staging = ItemContainer(1, stackAll = true)
        staging[0] = com.opennxt.model.items.Item(MoneyPouch.COINS, held)
        val result = bank.deposit(MoneyPouch.COINS, held, staging, viewedTabOf(player))
        val left = staging.count(MoneyPouch.COINS).coerceIn(0L, held.toLong()).toInt()
        val moved = held - left
        if (moved <= 0) {
            refusals++
            return ButtonResult.Refused(component, "the bank took none of the pouch's $held coins ($result)")
        }
        pouch.set(left)
        deposits++
        logger.info {
            "bank: ${player.name} deposited $moved coin(s) from the money pouch (517:$component); " +
                "$left left, ${bank.count(MoneyPouch.COINS)} banked"
        }
        return ButtonResult.Deposited(component, "money pouch", 1, moved.toLong(), if (left > 0) 1 else 0, bank.usedSlots())
    }

    class CoinPouch(val amount: () -> Int, val set: (Int) -> Unit)

    fun depositAll(player: ContentPlayer, from: ItemContainer, source: String, component: Int): ButtonResult {
        val bank = bankOf(player)
        val ids = LinkedHashSet<Int>()
        from.toArray().forEach { item -> item?.let { ids += it.id } }
        if (ids.isEmpty()) {
            refusals++
            return ButtonResult.Refused(component, "$source is empty", "You have nothing to deposit.")
        }
        var movedIds = 0
        var movedUnits = 0L
        var leftBehind = 0
        ids.forEach { id ->
            val held = from.count(id)
            if (held <= 0L) return@forEach
            val want = held.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            when (val r = bank.deposit(id, want, from, viewedTabOf(player))) {
                is Bank.DepositResult.Deposited -> {
                    if (r.deposited > 0) {
                        movedIds++
                        movedUnits += r.deposited.toLong()
                    }
                    if (!r.complete) leftBehind++
                }
                else -> leftBehind++
            }
        }
        deposits++
        logger.info {
            "bank: ${player.name} deposited $movedUnits item(s) over $movedIds id(s) from $source " +
                "(517:$component); $leftBehind id(s) did not fit, ${bank.usedSlots()}/${Bank.CAPACITY} slots used"
        }
        return ButtonResult.Deposited(component, source, movedIds, movedUnits, leftBehind, bank.usedSlots())
    }

    fun withdrawFrom(player: ContentPlayer, to: ItemContainer, slot: Int, amount: Int,
                     clientItemId: Int = NO_ITEM): ButtonResult {
        val component = ITEM_LAYER_COMPONENT
        if (amount < 1) {
            refusals++
            return ButtonResult.Refused(component, "amount $amount is not positive")
        }
        val bank = bankOf(player)
        val held = bank.itemAt(slot)
        if (held == null) {
            refusals++
            logger.info {
                "bank: ${player.name} withdraw from empty bank slot $slot (client item $clientItemId)"
            }
            return ButtonResult.Refused(component, "bank slot $slot is empty on this server",
                "That item is not in your bank.")
        }
        if (clientItemId != NO_ITEM && clientItemId >= 0 && clientItemId != held.id) {
            refusals++
            logger.info {
                "bank: ${player.name} withdraw refused, slot $slot is item ${held.id}, client sent $clientItemId"
            }
            return ButtonResult.Refused(component,
                "stale item: client $clientItemId, server ${held.id} in slot $slot")
        }
        return when (val r = bank.withdraw(held.id, amount, to)) {
            is Bank.WithdrawResult.Withdrawn -> {
                withdrawals++
                logger.info {
                    "bank: ${player.name} withdrew ${r.withdrawn} of ${r.requested} x ${held.id} from bank " +
                        "slot $slot; ${r.stillBanked} still banked"
                }
                ButtonResult.Withdrew(held.id, slot, amount, r.withdrawn, r.stillBanked)
            }
            is Bank.WithdrawResult.TargetFull -> {
                refusals++
                ButtonResult.Refused(component, "target container is full", "Your backpack is too full.")
            }
            is Bank.WithdrawResult.NotBanked -> {
                refusals++
                ButtonResult.Refused(component, "item ${held.id} is not banked", "That item is not in your bank.")
            }
            is Bank.WithdrawResult.UnknownItem -> {
                refusals++
                ButtonResult.Refused(component, "item ${held.id} has no definition row")
            }
        }
    }

    fun withdraw(player: ContentPlayer, to: ItemContainer, itemId: Int, slot: Int, amount: Int): ButtonResult =
        withdrawFrom(player, to, slot, amount, itemId)

    fun depositFrom(player: ContentPlayer, from: ItemContainer, slot: Int, amount: Int,
                    clientItemId: Int = NO_ITEM): ButtonResult {
        val component = BACKPACK_LAYER_COMPONENT
        if (amount < 1) {
            refusals++
            return ButtonResult.Refused(component, "amount $amount is not positive")
        }
        if (slot < 0 || slot >= from.size) {
            refusals++
            logger.info { "bank: ${player.name} named backpack slot $slot, outside 0..${from.size - 1}." }
            return ButtonResult.Refused(component, "backpack slot $slot is outside the container")
        }
        val held = from[slot]
        if (held == null) {
            refusals++
            logger.info {
                "bank: ${player.name} deposit from empty backpack slot $slot (client item $clientItemId)"
            }
            return ButtonResult.Refused(component, "backpack slot $slot is empty on this server",
                "There is nothing there to deposit.")
        }
        if (clientItemId != NO_ITEM && clientItemId >= 0 && clientItemId != held.id) {
            refusals++
            logger.info {
                "bank: ${player.name} deposit refused, backpack slot $slot is item ${held.id}, client sent $clientItemId"
            }
            return ButtonResult.Refused(component,
                "stale item: client $clientItemId, server ${held.id} in slot $slot")
        }
        val bank = bankOf(player)
        return when (val r = bank.deposit(held.id, amount, from, viewedTabOf(player))) {
            is Bank.DepositResult.Deposited -> {
                if (r.deposited <= 0) {
                    refusals++
                    return ButtonResult.Refused(component, "nothing of ${held.id} moved")
                }
                deposits++
                logger.info {
                    "bank: ${player.name} deposited ${r.deposited} of ${r.requested} x ${held.id} from " +
                        "backpack slot $slot (517:$component); ${r.bankedTotal} banked, " +
                        "${bank.usedSlots()}/${Bank.CAPACITY} slots"
                }
                ButtonResult.DepositedOne(held.id, slot, amount, r.deposited, r.bankedTotal, bank.usedSlots())
            }
            is Bank.DepositResult.BankFull -> {
                refusals++
                logger.info { "bank: ${player.name} could not deposit ${held.id} - the bank is full." }
                ButtonResult.Refused(component, "the bank is full", "Your bank is too full to hold that.")
            }
            is Bank.DepositResult.NothingToDeposit -> {
                refusals++
                ButtonResult.Refused(component, "the backpack holds none of ${held.id}")
            }
            is Bank.DepositResult.UnknownItem -> {
                refusals++
                logger.info { "bank: ${player.name} tried to deposit item ${held.id}, which has no definition row." }
                ButtonResult.Refused(component, "item ${held.id} has no definition row",
                    "You can't put that in your bank.")
            }
        }
    }

    fun handleButton(player: ContentPlayer, interfaceId: Int, component: Int, op: Int,
                     itemId: Int, slot: Int, containers: Containers): ButtonResult {
        if (interfaceId != BANK_INTERFACE) return ButtonResult.NotOurs
        if (component == TAB_ROW_COMPONENT || component == TAB_STRIP_COMPONENT) {
            if (!isScreenOpen(player)) {
                refusals++
                logger.warn { "bank: ${player.name} sent IF_BUTTON$op on 517:$component slot $slot with no bank open; ignored" }
                return ButtonResult.Refused(component, "no bank screen is open for this player")
            }
            return if (component == TAB_STRIP_COMPONENT) viewAll(player, op) else tabClick(player, op, slot)
        }
        if (component != DEPOSIT_BACKPACK_COMPONENT && component != DEPOSIT_WORN_COMPONENT &&
            component != DEPOSIT_FAMILIAR_COMPONENT && component != DEPOSIT_COINPOUCH_COMPONENT &&
            component != ITEM_LAYER_COMPONENT && component != BACKPACK_LAYER_COMPONENT &&
            component !in quantityButtons()
        ) return ButtonResult.NotOurs

        if (!isScreenOpen(player)) {
            refusals++
            logger.warn {
                "bank: ${player.name} sent IF_BUTTON$op on $interfaceId:$component with no bank open; ignored"
            }
            return ButtonResult.Refused(component, "no bank screen is open for this player")
        }

        val result = route(player, component, op, itemId, slot, containers)
        if (result is ButtonResult.DepositedOne) autoSwitchView(player, result.id)
        if (result is ButtonResult.Deposited || result is ButtonResult.Withdrew ||
            result is ButtonResult.DepositedOne
        ) refresh(player)
        return result
    }

    private fun tabClick(player: ContentPlayer, op: Int, tab: Int): ButtonResult {
        val bank = bankOf(player)
        val sink = sinkSupplier(player)
        return when {
            op == 1 && bank.isTab(tab) -> {
                viewedTab[player] = tab
                sink?.let { sendTabState(player, it, bank, open = true) }
                tabActions++
                ButtonResult.TabChanged(TAB_ROW_COMPONENT, op, tab, "viewing tab $tab (${bank.tabSize(tab)} slot(s))")
            }
            op == 1 && tab == bank.placeholderTab() -> {
                sink?.runClientScript(TAB_HINT_SCRIPT,
                    listOf(TAB_HINT_TEXT, (BANK_INTERFACE shl 16) or TAB_ROW_COMPONENT, tab, 0))
                ButtonResult.TabChanged(TAB_ROW_COMPONENT, op, tab, "'Add tab' on the placeholder: the drag hint")
            }
            op == 2 && bank.isTab(tab) -> {
                val moved = (bank.deleteTab(tab) as Bank.DeleteTabResult.Deleted).itemsMoved
                shiftViewAfterRemoval(player, tab)
                tabActions++
                refresh(player)
                ButtonResult.TabChanged(TAB_ROW_COMPONENT, op, tab,
                    "deleted tab $tab: $moved stack(s) moved to the end of the main tab, later tabs down one")
            }
            else -> {
                refusals++
                val why = if (op == 3) "Customise tab is not implemented"
                else "'${TAB_OPS[op] ?: "op $op"}' on tab child $tab, which is not a tab on this server"
                logger.info { "bank tabs: ${player.name} IF_BUTTON$op on 517:$TAB_ROW_COMPONENT slot $tab refused - $why" }
                ButtonResult.Refused(TAB_ROW_COMPONENT, why)
            }
        }
    }

    private fun viewAll(player: ContentPlayer, op: Int): ButtonResult {
        if (op != 1) {
            refusals++
            return ButtonResult.Refused(TAB_STRIP_COMPONENT, "op $op on View all is not implemented")
        }
        viewedTab[player] = Bank.MAIN_TAB
        sinkSupplier(player)?.let { sendTabState(player, it, bankOf(player), open = true) }
        tabActions++
        return ButtonResult.TabChanged(TAB_STRIP_COMPONENT, op, Bank.MAIN_TAB, "viewing all")
    }

    private fun autoSwitchView(player: ContentPlayer, id: Int) {
        val sink = sinkSupplier(player) ?: return
        if ((sink.varpValue(QUANTITY_VARP) shr AUTO_SWITCH_BIT) and 1 == 0) return
        val bank = bankOf(player)
        val slot = bank.slotOf(id)
        if (slot >= 0) viewedTab[player] = bank.tabOfSlot(slot)
    }

    fun handleDrag(player: ContentPlayer, sourceComponent: Int, sourceSlot: Int,
                   targetComponent: Int, targetSlot: Int, sourceObj: Int = NO_ITEM): ButtonResult {
        val toTab = targetComponent in TAB_DROP_TARGETS
        val toMain = targetComponent == TAB_STRIP_COMPONENT
        if (sourceComponent != ITEM_LAYER_COMPONENT || !(toTab || toMain)) return ButtonResult.NotOurs
        if (!isScreenOpen(player)) {
            refusals++
            return ButtonResult.Refused(targetComponent, "no bank screen is open for this player")
        }
        if (sourceObj != NO_ITEM) {
            val held = bankOf(player).itemAt(sourceSlot)?.id
            if (held != sourceObj) {
                refusals++
                return ButtonResult.Refused(targetComponent,
                    "the client dragged item $sourceObj from bank slot $sourceSlot, which holds ${held ?: "nothing"} on this server")
            }
        }
        val tab = if (toMain) Bank.MAIN_TAB else targetSlot
        return when (val r = bankOf(player).moveToTab(sourceSlot, tab)) {
            is Bank.TabMoveResult.Moved -> {
                r.collapsed.forEach { shiftViewAfterRemoval(player, it) }
                tabActions++
                refresh(player)
                ButtonResult.TabChanged(targetComponent, 0, r.toTab,
                    "item ${r.id} from tab ${r.fromTab} to tab ${r.toTab}, now bank slot ${r.slot}" +
                        (if (r.collapsed.isEmpty()) "" else "; tab(s) ${r.collapsed} emptied and removed"))
            }
            is Bank.TabMoveResult.EmptySlot -> {
                refusals++
                ButtonResult.Refused(targetComponent, "bank slot $sourceSlot is empty on this server")
            }
            is Bank.TabMoveResult.NoSuchTab -> {
                refusals++
                ButtonResult.Refused(targetComponent, "tab $tab is neither a tab nor the 'Add tab' placeholder")
            }
        }
    }

    private fun route(player: ContentPlayer, component: Int, op: Int,
                      itemId: Int, slot: Int, containers: Containers): ButtonResult {
        return when (component) {
            DEPOSIT_BACKPACK_COMPONENT -> {
                if (op != 1) return refusedRow(component, op)
                val backpack = containers.backpack
                    ?: return refusedContainer(component, "backpack")
                depositAll(player, backpack, "backpack", component)
            }
            DEPOSIT_WORN_COMPONENT -> {
                if (op != 1) return refusedRow(component, op)
                val worn = containers.worn
                    ?: return refusedContainer(component, "worn equipment")
                depositAll(player, worn, "worn equipment", component)
            }
            DEPOSIT_FAMILIAR_COMPONENT -> {
                refusals++
                ButtonResult.Refused(component, "familiars are not supported",
                    "You don't have a familiar with you.")
            }
            DEPOSIT_COINPOUCH_COMPONENT -> {
                if (op != 1) return refusedRow(component, op)
                val pouch = containers.coinPouch ?: run {
                    refusals++
                    return ButtonResult.Refused(component, "no money pouch was supplied (MoneyPouch is off)",
                        "You have no coins in your money pouch.")
                }
                depositCoinPouch(player, pouch, component)
            }
            ITEM_LAYER_COMPONENT -> {
                val backpack = containers.backpack
                    ?: return refusedContainer(component, "backpack")
                val amount = amountForRow(player, rowFor(player, component, op))
                    ?: return refusedRow(component, op)
                withdrawFrom(player, backpack, slot, amount, itemId)
            }

            BACKPACK_LAYER_COMPONENT -> {
                val backpack = containers.backpack
                    ?: return refusedContainer(component, "backpack")
                val amount = amountForRow(player, rowFor(player, component, op))
                    ?: return refusedRow(component, op)
                depositFrom(player, backpack, slot, amount, itemId)
            }

            else -> {
                val amount = quantityButtons()[component] ?: return ButtonResult.NotOurs
                if (op != 1) return refusedRow(component, op)
                if (amount == UNSUPPORTED) {
                    refusals++
                    logger.info {
                        "bank: ${player.name} pressed custom quantity 517:$component; not supported"
                    }
                    return ButtonResult.Refused(component, "custom (X) quantity is not supported",
                        "Choosing your own number isn't available on this server yet.")
                }
                val was = quantityOf(player)
                quantityMode[player] = amount
                logger.info {
                    "bank: ${player.name} set the bank move quantity to " +
                        (if (amount == ALL) "ALL" else "$amount") + " (517:$component), was " +
                        (if (was == ALL) "ALL" else "$was")
                }
                ButtonResult.QuantityChanged(component, was, amount)
            }
        }
    }

    private fun refusedRow(component: Int, op: Int): ButtonResult {
        refusals++
        logger.info {
            "bank: IF_BUTTON$op on $BANK_INTERFACE:$component is not implemented " +
                "(enabled ops ${maskOpsOf(component)})"
        }
        return ButtonResult.Refused(component, "menu row for op $op is not implemented for this component")
    }

    private fun maskOpsOf(component: Int): List<Int> = when (component) {
        ITEM_LAYER_COMPONENT -> ItemOps.enabledOps(ITEM_LAYER_MASK)
        BACKPACK_LAYER_COMPONENT -> ItemOps.enabledOps(BACKPACK_LAYER_MASK)
        else -> ItemOps.enabledOps(OP1_MASK)
    }

    private fun refusedContainer(component: Int, what: String): ButtonResult {
        refusals++
        logger.info { "bank: 517:$component has no $what container; ignored" }
        return ButtonResult.Refused(component, "no $what container was supplied")
    }

    fun useDeclaringBankLocs(): List<Int> = RsDatabase.queryAll(
        "SELECT l.id FROM locs l WHERE (l.name LIKE '%ank booth%' OR l.name LIKE '%ank chest%') " +
            "AND (l.actions_0 = 'Use' OR EXISTS (SELECT 1 FROM locs_attr a WHERE a.id = l.id " +
            "AND a.field LIKE 'actions_%' AND a.value = '\"Use\"')) ORDER BY l.id"
    ) { it.getInt("id") }

    var npcBound: Int = -1
        private set

    fun install(): Pair<Int, Int> {
        val bank = ContentRegistry.onLocAction(BANK, ::onBank)
        val useIds = useDeclaringBankLocs()
        useIds.forEach { ContentRegistry.onLoc(it, USE, ::onBank) }
        npcBound = ContentRegistry.onNpcAction(BANK, ::onBankNpc)
        MetalBanks.install()
        logger.info {
            "banks: bound Bank on $bank locs and $npcBound npcs, Use on ${useIds.size} locs; bank screen " +
                (if (uiEnabled) "on ($PROVENANCE_SHORT)"
                 else "off (-Dopennxt.experiment.banks.ui=true to enable)")
        }
        logger.info {
            if (npcClickReaches)
                "banks: npc bank clicks enabled (-D$NPC_DISPATCH_SWITCH=true)"
            else
                "banks: npc bank clicks disabled; -D$NPC_DISPATCH_SWITCH=true to enable"
        }
        return bank to useIds.size
    }
}

package com.opennxt.content.impl

import com.opennxt.model.InterfaceHash
import com.opennxt.model.world.WorldPlayer
import com.opennxt.net.game.serverprot.RunClientScript
import com.opennxt.net.game.serverprot.UpdateInvStopTransmit
import com.opennxt.net.game.serverprot.ifaces.IfClosesub
import com.opennxt.net.game.serverprot.ifaces.IfOpenSub
import com.opennxt.net.game.serverprot.ifaces.IfSetevents
import com.opennxt.net.game.serverprot.variables.ClientSetvarcSmall
import com.opennxt.net.game.serverprot.variables.ClientSetvarcstrSmall
import com.opennxt.net.game.serverprot.variables.VarpLarge
import com.opennxt.net.game.serverprot.variables.VarpSmall
import mu.KotlinLogging
import java.util.Collections
import java.util.WeakHashMap

object MakeXPanel {
    private val logger = KotlinLogging.logger { }

    val enabled: Boolean get() = System.getProperty("opennxt.experiment.makex") != "false"

    const val IFACE = 1370

    const val CONTROLS_IFACE = 1371

    const val TOPLEVEL = 1477

    const val MOUNT = 735

    const val CONTROLS_MOUNT = 0

    const val PRODUCT_GRID = 22

    const val QUANTITY = 20

    const val CONFIRM = 30

    const val CLOSE = 32

    const val CATEGORY_BUTTON = 28

    const val CATEGORY_LIST = 896

    const val GRID_MASK = 2

    const val PRODUCT_SLOT_STRIDE = 4

    const val VARP_CATEGORY_A = 1168
    const val VARP_CATEGORY_B = 7881
    const val VARP_ZERO = 9409
    const val VARP_RECIPE = 1169
    const val VARP_PRODUCT = 1170
    const val VARP_COUNT = 8846
    const val VARP_COUNT_MIRROR = 8847
    const val VARP_KNOWN = 1172
    const val VARP_LAST_MADE = 1175

    const val VARC_EXAMINE = 2391
    const val VARC_MAKEABLE = 2223
    val VARC_CLEARED_ON_OPEN = intArrayOf(2689, 2690, 6579, 6580, 7057, 7058)
    const val VARC_SELECTION = 3678

    const val SCRIPT_RESET = 8178

    const val SCRIPT_CLOSE = 3689
    const val SCRIPT_CLOSE_ARG3 = 40

    const val PRODUCT_INV = 884

    const val LOGS_CATEGORY_A = 6939

    const val LOGS_CATEGORY_B = 6940

    const val LOGS_RECIPE = 6947

    const val LOGS_GRID_TO_SLOT = 24

    const val LOGS_SHAFT_SLOT = 5

    const val LOGS_SHAFT_EXAMINE = "A wooden shaft. Needs feathers."

    fun rowsFor(category: Fletching.PanelCategory): Map<Int, Row> =
        category.products.associate { p ->
            p.gridSlot to Row(p.gridSlot, p.itemId, p.name, examineFor(p.itemId))
        }

    fun categoriesFor(panel: Fletching.Panel): List<CategoryView> =
        panel.categories.map { c ->
            CategoryView(
                index = c.index,
                name = c.name,
                recipeEnum = c.productEnum,
                gridToSlot = Fletching.GRID_STRIDE * c.entryCount,
                rows = rowsFor(c)
            )
        }

    fun examineFor(itemId: Int): String? = EXAMINES[itemId]

    val EXAMINES: Map<Int, String> = mapOf(
        Fletching.SHAFT_ITEM to LOGS_SHAFT_EXAMINE
    )

    data class Row(val slot: Int, val itemId: Int, val name: String, val examine: String? = null)

    data class CategoryView(
        val index: Int,
        val name: String,
        val recipeEnum: Int,
        val gridToSlot: Int,
        val rows: Map<Int, Row>
    )

    data class Session(
        val materialId: Int,
        val materialName: String,
        val categoryA: Int,
        val categoryB: Int,
        val recipe: Int,
        val rows: Map<Int, Row>,
        val gridToSlot: Int,
        var selected: Row,
        val countOf: (WorldPlayer, Row) -> Int,
        val onConfirm: (WorldPlayer, Row, Int) -> Boolean,
        val categories: List<CategoryView> = emptyList(),
        var categoryIndex: Int = 0,
        var dropdownOpen: Boolean = false
    ) {
        fun category(): CategoryView? = categories.firstOrNull { it.index == categoryIndex }

        fun activeRecipe(): Int = category()?.recipeEnum ?: recipe

        fun activeRows(): Map<Int, Row> = category()?.rows ?: rows

        fun activeGridToSlot(): Int = category()?.gridToSlot ?: gridToSlot
    }

    private val sessions: MutableMap<WorldPlayer, Session> =
        Collections.synchronizedMap(WeakHashMap<WorldPlayer, Session>())

    @Volatile var opens: Int = 0; private set
    @Volatile var productClicks: Int = 0; private set
    @Volatile var quantityClicks: Int = 0; private set
    @Volatile var confirms: Int = 0; private set
    @Volatile var closes: Int = 0; private set

    @Volatile var categoryButtons: Int = 0; private set

    @Volatile var categoryChoices: Int = 0; private set

    internal fun resetCounters() {
        opens = 0; productClicks = 0; quantityClicks = 0; confirms = 0; closes = 0
        categoryButtons = 0; categoryChoices = 0
    }

    fun sessionOf(player: WorldPlayer): Session? = sessions[player]
    fun openCount(): Int = sessions.size

    fun open(player: WorldPlayer, session: Session): Boolean {
        if (!enabled) return false
        if (!player.client.channel.isActive) return false
        val w = player.client
        val count = session.countOf(player, session.selected).coerceAtLeast(0)

        w.write(VarpLarge(VARP_CATEGORY_A, session.categoryA))
        w.write(VarpLarge(VARP_CATEGORY_B, session.categoryB))
        w.write(VarpSmall(VARP_ZERO, 0))
        w.write(VarpLarge(VARP_RECIPE, session.activeRecipe()))
        w.write(VarpLarge(VARP_PRODUCT, session.selected.itemId))
        w.write(VarpSmall(VARP_COUNT, count))
        w.write(VarpSmall(VARP_COUNT_MIRROR, count))
        for (id in VARC_CLEARED_ON_OPEN) w.write(ClientSetvarcSmall(id, 0))
        session.selected.examine?.let { w.write(ClientSetvarcstrSmall(VARC_EXAMINE, it)) }
        w.write(ClientSetvarcSmall(VARC_MAKEABLE, if (count > 0) 1 else 0))
        w.write(ClientSetvarcSmall(VARC_SELECTION, -1))
        w.write(RunClientScript(SCRIPT_RESET, emptyArray()))
        w.write(IfOpenSub(IFACE, false, InterfaceHash(TOPLEVEL, MOUNT)))
        w.write(IfOpenSub(CONTROLS_IFACE, true, InterfaceHash(IFACE, CONTROLS_MOUNT)))
        w.write(IfSetevents(InterfaceHash(CONTROLS_IFACE, PRODUCT_GRID), 0, session.activeGridToSlot(), GRID_MASK))
        w.write(IfSetevents(InterfaceHash(CONTROLS_IFACE, QUANTITY), 0, count, GRID_MASK))

        session.dropdownOpen = false
        sessions[player] = session
        opens++
        logger.info {
            "make-X: opened for ${player.name}: ${session.materialName} -> ${session.selected.name} " +
                "(item ${session.selected.itemId}), recipe ${session.recipe}, count $count"
        }
        return true
    }

    fun handleProductClick(player: WorldPlayer, slot: Int): Boolean {
        if (!enabled) return false
        val session = sessions[player] ?: return false
        val row = session.activeRows()[slot] ?: run {
            logger.info {
                "make-X: ${player.name} clicked unsupported slot $slot (product ${Fletching.productIndexOf(slot)}) " +
                    "in category ${session.categoryIndex}; supported: ${session.activeRows().keys.sorted()}"
            }
            return false
        }
        if (!player.client.channel.isActive) return false
        val count = session.countOf(player, row).coerceAtLeast(0)
        session.selected = row
        val w = player.client
        w.write(VarpLarge(VARP_PRODUCT, row.itemId))
        w.write(VarpSmall(VARP_COUNT, count))
        w.write(VarpSmall(VARP_COUNT_MIRROR, count))
        row.examine?.let { w.write(ClientSetvarcstrSmall(VARC_EXAMINE, it)) }
        w.write(ClientSetvarcSmall(VARC_MAKEABLE, if (count > 0) 1 else 0))
        w.write(IfSetevents(InterfaceHash(CONTROLS_IFACE, QUANTITY), 0, count, GRID_MASK))
        productClicks++
        logger.info {
            "make-X: ${player.name} selected slot $slot -> ${row.name} (item ${row.itemId}), count $count"
        }
        return true
    }

    fun handleCategoryButton(player: WorldPlayer): Boolean {
        if (!enabled) return false
        val session = sessions[player] ?: return false
        if (session.categories.isEmpty()) {
            logger.info {
                "make-X: ${player.name} opened the material dropdown on a single-category panel"
            }
            return true
        }
        session.dropdownOpen = true
        categoryButtons++
        logger.info {
            "make-X: ${player.name} opened the material dropdown (${session.categories.size} rows, current " +
                "${session.categoryIndex} '${session.category()?.name}')"
        }
        return true
    }

    fun handleCategoryChoice(player: WorldPlayer, row: Int): Boolean {
        if (!enabled) return false
        val session = sessions[player] ?: return false
        if (session.categories.isEmpty()) return false
        if (!session.dropdownOpen) {
            logger.info {
                "make-X: ignored material row $row from ${player.name}: dropdown not open"
            }
            return false
        }
        session.dropdownOpen = false
        val category = session.categories.firstOrNull { it.index == row } ?: run {
            logger.info {
                "make-X: rejected material row $row from ${player.name}; valid rows ${session.categories.map { it.index }}"
            }
            return true
        }
        if (!player.client.channel.isActive) return true
        val selected = category.rows.entries.minByOrNull { it.key }?.value ?: session.selected
        session.categoryIndex = row
        session.selected = selected
        val count = session.countOf(player, selected).coerceAtLeast(0)

        val w = player.client
        w.write(VarpLarge(VARP_RECIPE, category.recipeEnum))
        w.write(VarpSmall(VARP_PRODUCT, -1))
        w.write(VarpLarge(VARP_PRODUCT, selected.itemId))
        w.write(VarpSmall(VARP_COUNT, count))
        w.write(VarpSmall(VARP_COUNT_MIRROR, count))
        selected.examine?.let { w.write(ClientSetvarcstrSmall(VARC_EXAMINE, it)) }
        w.write(IfSetevents(InterfaceHash(CONTROLS_IFACE, PRODUCT_GRID), 0, category.gridToSlot, GRID_MASK))
        if (count > 0) w.write(IfSetevents(InterfaceHash(CONTROLS_IFACE, QUANTITY), 0, count, GRID_MASK))
        categoryChoices++
        logger.info {
            "make-X: ${player.name} switched to material $row '${category.name}' (recipe ${category.recipeEnum}, " +
                "${category.rows.size} rows), selected ${selected.name} (item ${selected.itemId}), count $count"
        }
        return true
    }

    fun handleQuantityClick(player: WorldPlayer, slot: Int): Boolean {
        if (!enabled) return false
        val session = sessions[player] ?: return false
        quantityClicks++
        val count = session.countOf(player, session.selected)
        logger.warn {
            "make-X: ${player.name} clicked quantity slot $slot; quantity selection is unsupported, count stays $count"
        }
        return true
    }

    fun handleConfirm(player: WorldPlayer): Boolean {
        if (!enabled) return false
        val session = sessions[player] ?: return false
        val row = session.selected
        val count = session.countOf(player, row).coerceAtLeast(0)
        closePanel(player, session, row)
        if (count <= 0) {
            logger.info {
                "make-X: ${player.name} confirmed ${row.name} without materials; panel closed"
            }
            return true
        }
        confirms++
        val started = runCatching { session.onConfirm(player, row, count) }
            .onFailure { logger.error(it) { "make-X: confirm callback failed for ${player.name}" } }
            .getOrDefault(false)
        logger.info {
            "make-X: ${player.name} confirmed ${count} x ${row.name} (item ${row.itemId}) from " +
                "${session.materialName}; ${if (started) "started" else "rejected"}"
        }
        return true
    }

    fun handleClose(player: WorldPlayer): Boolean {
        if (!enabled) return false
        val session = sessions[player] ?: return false
        closePanel(player, session, session.selected)
        logger.info { "make-X: ${player.name} closed the panel" }
        return true
    }

    fun forget(player: WorldPlayer) { sessions.remove(player) }

    private fun closePanel(player: WorldPlayer, session: Session, row: Row) {
        sessions.remove(player)
        closes++
        if (!player.client.channel.isActive) return
        val w = player.client
        w.write(VarpLarge(VARP_KNOWN, row.itemId))
        w.write(VarpSmall(VARP_COUNT, 0))
        w.write(VarpSmall(VARP_COUNT_MIRROR, 0))
        w.write(VarpSmall(VARP_CATEGORY_A, -1))
        w.write(VarpSmall(VARP_RECIPE, -1))
        w.write(VarpSmall(VARP_PRODUCT, -1))
        w.write(VarpSmall(VARP_ZERO, 0))
        w.write(VarpSmall(VARP_CATEGORY_B, -1))
        w.write(UpdateInvStopTransmit(PRODUCT_INV))
        w.write(VarpLarge(VARP_LAST_MADE, row.itemId))
        w.write(IfClosesub(InterfaceHash(IFACE, CONTROLS_MOUNT)))
        w.write(IfClosesub(InterfaceHash(TOPLEVEL, MOUNT)))
        w.write(
            RunClientScript(
                SCRIPT_CLOSE,
                arrayOf(IFACE, (TOPLEVEL shl 16) or MOUNT, SCRIPT_CLOSE_ARG3)
            )
        )
    }

    fun describe(): String =
        "make-X panel: $IFACE at $TOPLEVEL:$MOUNT, controls $CONTROLS_IFACE, recipe varp $VARP_RECIPE, " +
            "count varp $VARP_COUNT, confirm $IFACE:$CONFIRM"
}

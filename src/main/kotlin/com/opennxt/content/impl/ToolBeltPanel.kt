package com.opennxt.content.impl

import com.google.gson.JsonParser
import com.opennxt.model.InterfaceHash
import com.opennxt.model.world.WorldPlayer
import com.opennxt.net.game.serverprot.RunClientScript
import com.opennxt.net.game.serverprot.ifaces.IfSettext
import com.opennxt.net.game.serverprot.variables.VarpLarge
import com.opennxt.net.game.serverprot.variables.VarpSmall
import com.opennxt.resources.sqlite.RsDatabase
import com.opennxt.resources.Names950
import mu.KotlinLogging
import java.util.Collections
import java.util.WeakHashMap

object ToolBeltPanel {
    private val logger = KotlinLogging.logger { }

    val enabled: Boolean get() = System.getProperty("opennxt.experiment.toolbelt.panel") != "false"

    val IFACE = Names950.ifc("toolbelt_v2")

    val TOPLEVEL = Names950.ifc("toplevel_v2")

    val MOUNT = Names950.comp("toplevel_v2:fullmodal_window_content").component

    val SLOT_GRID = Names950.comp("toolbelt_v2:toolbelt_click_layer").component

    val HIDE_COMPONENT = Names950.comp("toolbelt_v2:settings_button_disabled_layer").component

    val SINGLE_COMPONENT = Names950.comp("toolbelt_v2:icon_background").component

    val DESCRIPTION_COMPONENT = Names950.comp("toolbelt_v2:info_desc").component

    val CLOSE_COMPONENT = Names950.comp("toolbelt_v2:mainmodal_window_close_button").component

    const val FIRST_SLOT = 0
    const val LAST_SLOT = 77
    const val SLOT_MASK = 6
    const val SINGLE_MASK = 2

    val SCRIPT_LAYOUT = Names950.clientscriptId("overlaychanged")
    const val SCRIPT_LAYOUT_ARG = 6

    // 14097 is a generic proc (no name in proc.sym); kept numeric.
    const val SCRIPT_FILL = 14097
    const val SCRIPT_FILL_ARG = 0

    val VARP_ON_OPEN = Names950.varpId("last_modal_overlay_id")
    const val VARP_ON_OPEN_VALUE = 0

    val VARP_SELECTED_STRUCT = Names950.varpId("toolbelt_struct")

    val OPEN_BUTTON_IFACE = Names950.ifc("toplevel_v2_worn")
    val OPEN_BUTTON_COMPONENT = Names950.comp("toplevel_v2_worn:button_layer").component

    val OPEN_BUTTON_IFACE_WINDOWED = Names950.ifc("toplevel_v2_parent_suboverlay_worn")
    val OPEN_BUTTON_COMPONENT_WINDOWED = Names950.comp("toplevel_v2_parent_suboverlay_worn:button_layer").component

    const val OPEN_BUTTON_SLOT = 4353

    const val STRIP_MASK = 2046

    val STRIP_RANGES: IntArray = intArrayOf(0, 4096, 4352, 4608, 4864)

    fun isOpenButton(iface: Int, component: Int, slot: Int): Boolean =
        slot == OPEN_BUTTON_SLOT &&
                ((iface == OPEN_BUTTON_IFACE && component == OPEN_BUTTON_COMPONENT) ||
                        (iface == OPEN_BUTTON_IFACE_WINDOWED && component == OPEN_BUTTON_COMPONENT_WINDOWED))

    val P_ITEM = Names950.paramId("toolbelt_object")
    val P_UPGRADEABLE = Names950.paramId("toolbelt_upgradeable")
    val P_OBTAINED = Names950.paramId("toolbelt_unlock_info")

    const val OBTAINED_DEFAULT = "Obtained automatically."
    const val UPGRADE_CLAUSE =
        " To upgrade, select the 'Add to tool belt' menu option on the upgrade item from your backpack."

    data class Slot(val structId: Int, val itemId: Int, val upgradeable: Boolean, val obtained: String?)

    val slots: List<Slot> by lazy { load() }

    private fun load(): List<Slot> {
        if (!RsDatabase.available) return emptyList()
        val byStruct = HashMap<Int, MutableMap<Int, Pair<Int?, String?>>>()
        RsDatabase.queryAll(
            "SELECT struct_id, prop, intvalue, stringvalue FROM struct_param " +
                    " WHERE prop IN ($P_ITEM, $P_UPGRADEABLE, $P_OBTAINED) " +
                    "   AND struct_id IN (SELECT struct_id FROM struct_param WHERE prop = $P_ITEM)"
        ) { rs ->
            val sid = rs.getInt("struct_id")
            val prop = rs.getInt("prop")
            val iv: Int? = rs.getInt("intvalue").let { if (rs.wasNull()) null else it }
            val sv: String? = rs.getString("stringvalue")
            byStruct.getOrPut(sid) { HashMap() }[prop] = iv to sv
            sid
        }
        return byStruct.entries.sortedBy { it.key }.mapNotNull { (sid, props) ->
            val item = props[P_ITEM]?.first ?: return@mapNotNull null
            Slot(
                structId = sid,
                itemId = item,
                upgradeable = (props[P_UPGRADEABLE]?.first ?: 0) == 1,
                obtained = props[P_OBTAINED]?.second
            )
        }
    }

    val slotsByStruct: Map<Int, Slot> by lazy { slots.associateBy { it.structId } }

    val slotMap: Map<Int, Int> by lazy {
        val override = System.getProperty("opennxt.toolbelt.panel.slotmap")
        if (override != null) {
            override.split(',').mapNotNull {
                val p = it.split(':')
                val slot = p.getOrNull(0)?.trim()?.toIntOrNull()
                val struct = p.getOrNull(1)?.trim()?.toIntOrNull()
                if (slot != null && struct != null) slot to struct else null
            }.toMap()
        } else slots.take(LAST_SLOT - FIRST_SLOT + 1).mapIndexed { i, s -> i to s.structId }.toMap()
    }

    fun structForSlot(slot: Int): Slot? = slotMap[slot]?.let { slotsByStruct[it] }

    fun descriptionFor(slot: Slot): String =
        (slot.obtained ?: OBTAINED_DEFAULT) + (if (slot.upgradeable) UPGRADE_CLAUSE else "")

    @Volatile
    var opens: Int = 0
        private set

    @Volatile
    var slotClicks: Int = 0
        private set

    @Volatile
    var arms: Int = 0
        private set

    @Volatile
    var closes: Int = 0
        private set

    internal fun resetCounters() {
        opens = 0; slotClicks = 0; arms = 0; closes = 0
    }

    fun armOpenButton(
        player: WorldPlayer,
        iface: Int = OPEN_BUTTON_IFACE,
        component: Int = OPEN_BUTTON_COMPONENT
    ): Int {
        if (!enabled) return 0
        if (!player.client.channel.isActive) return 0
        for (from in STRIP_RANGES) {
            player.interfaces.events(id = iface, component = component, from = from, to = from + 1, mask = STRIP_MASK)
        }
        arms++
        logger.info {
            "toolbelt panel: armed $iface:$component mask $STRIP_MASK for ${player.name}"
        }
        return STRIP_RANGES.size
    }

    private val openSet: MutableSet<WorldPlayer> =
        Collections.newSetFromMap(Collections.synchronizedMap(WeakHashMap<WorldPlayer, Boolean>()))

    fun isOpen(player: WorldPlayer): Boolean = player in openSet

    fun open(player: WorldPlayer): Boolean {
        if (!enabled) return false
        if (!player.client.channel.isActive) return false
        if (player in openSet) {
            close(player, "the tool belt button was clicked again")
            return true
        }
        try {
            player.client.write(VarpSmall(VARP_ON_OPEN, VARP_ON_OPEN_VALUE))
            player.interfaces.open(id = IFACE, parent = TOPLEVEL, component = MOUNT, walkable = true)
            player.interfaces.hide(IFACE, HIDE_COMPONENT, true)
            player.client.write(RunClientScript(SCRIPT_LAYOUT, arrayOf(SCRIPT_LAYOUT_ARG)))
            player.interfaces.events(IFACE, SLOT_GRID, FIRST_SLOT, LAST_SLOT, SLOT_MASK)
            player.interfaces.events(IFACE, SINGLE_COMPONENT, 65535, 65535, SINGLE_MASK)
            player.client.write(RunClientScript(SCRIPT_FILL, arrayOf(SCRIPT_FILL_ARG)))
            openSet.add(player)
            opens++
            logger.info {
                "toolbelt panel: opened $IFACE at $TOPLEVEL:$MOUNT for ${player.name}, " +
                        "${ToolBelt.storedIdsFor(player.contentPlayer).size} stored tool(s)"
            }
            return true
        } catch (t: Throwable) {
            openSet.remove(player)
            logger.error(t) { "toolbelt panel: could not open $IFACE for ${player.name}" }
            return false
        }
    }

    fun close(player: WorldPlayer, reason: String = "close requested"): Boolean {
        if (!openSet.remove(player)) return false
        if (!player.client.channel.isActive) return false
        try {
            player.client.write(VarpSmall(VARP_ON_OPEN, VARP_ON_OPEN_VALUE))
            player.interfaces.close(id = TOPLEVEL, component = MOUNT)
            player.client.write(RunClientScript(SCRIPT_LAYOUT, arrayOf(SCRIPT_LAYOUT_ARG)))
            closes++
            logger.info {
                "toolbelt panel: closed $IFACE at $TOPLEVEL:$MOUNT for ${player.name} ($reason)"
            }
            return true
        } catch (t: Throwable) {
            logger.error(t) { "toolbelt panel: could not close $IFACE for ${player.name}" }
            return false
        }
    }

    fun handleSlotClick(player: WorldPlayer, slot: Int): Boolean {
        if (!enabled) return false
        val s = structForSlot(slot) ?: run {
            logger.info {
                "toolbelt panel: ${player.name} clicked unmapped belt slot $slot"
            }
            return false
        }
        if (!player.client.channel.isActive) return false
        player.client.write(VarpLarge(VARP_SELECTED_STRUCT, s.structId))
        player.client.write(IfSettext(InterfaceHash(IFACE, DESCRIPTION_COMPONENT), descriptionFor(s)))
        slotClicks++
        logger.info {
            "toolbelt panel: ${player.name} belt slot $slot -> struct ${s.structId} (item ${s.itemId}" +
                    "${if (s.upgradeable) ", upgradeable" else ""})"
        }
        return true
    }

    val STRIP_SCRIPT = Names950.clientscriptId("child_buttons_init")

    val STRIP_KIND_ENUM = Names950.enumId("child_options_enum")

    val STRIP_TABLE = Names950.dbtableId("child_options")

    val TOOL_BELT_ROW = Names950.dbrowId("worn_toolbelt")

    val TOGGLE_DRAGGING_ROW = Names950.dbrowId("inv_drag_toggle")

    val BACKPACK_STRIP_IFACE = Names950.ifc("toplevel_v2_inventory")
    val BACKPACK_STRIP_COMPONENT = Names950.comp("toplevel_v2_inventory:button_layer").component

    val BACKPACK_STRIP_IFACE_WINDOWED = Names950.ifc("toplevel_v2_parent_suboverlay_inventory")
    val BACKPACK_STRIP_COMPONENT_WINDOWED =
        Names950.comp("toplevel_v2_parent_suboverlay_inventory:button_layer").component

    data class StripRow(val kind: Int, val key: Int, val dbrow: Int, val name: String)

    fun keyForSlot(slot: Int): Int? {
        if (slot < 0 || (slot and 0xff) > 1) return null
        val group = slot ushr 8
        return when {
            group == 0 -> 0
            group in 16..79 -> group - 15
            else -> null
        }
    }

    fun slotForKey(key: Int): Int = if (key == 0) 1 else ((15 + key) shl 8) or 1

    val stripKinds: Map<Pair<Int, Int>, Int> by lazy {
        if (!RsDatabase.available) return@lazy emptyMap()
        val out = HashMap<Pair<Int, Int>, Int>()
        RsDatabase.queryAll(
            "SELECT id, value FROM interfaces_attr WHERE field = 'scripts' AND value LIKE '%\"script\":$STRIP_SCRIPT,%'"
        ) { rs ->
            val id = rs.getInt("id")
            val scripts = JsonParser().parse(rs.getString("value")).asJsonObject
            val onload = scripts.getAsJsonObject("37")
            if (onload != null && onload.get("script").asInt == STRIP_SCRIPT) {
                val args = onload.getAsJsonArray("args")
                if (args.size() >= 2) out[(id ushr 16) to (id and 0xffff)] = args[1].asInt
            }
            id
        }
        out
    }

    val stripEnums: Map<Int, Int> by lazy {
        if (!RsDatabase.available) return@lazy emptyMap()
        val out = HashMap<Int, Int>()
        RsDatabase.queryAll(
            "SELECT value FROM enums_attr WHERE id = ? AND field = 'intArrayValue1'", STRIP_KIND_ENUM
        ) { rs ->
            for (pair in JsonParser().parse(rs.getString("value")).asJsonArray) {
                val p = pair.asJsonArray
                if (p.size() == 2 && p[1].asInt >= 0) out[p[0].asInt] = p[1].asInt
            }
            0
        }
        out
    }

    private val rowsCache = HashMap<Int, Map<Int, Int>>()

    fun rowsOf(rowEnum: Int): Map<Int, Int> = synchronized(rowsCache) {
        rowsCache.getOrPut(rowEnum) {
            if (!RsDatabase.available) emptyMap()
            else RsDatabase.queryAll(
                "SELECT key, value FROM enum_entry WHERE enum_id = ?", rowEnum
            ) { rs -> rs.getString("key").toInt() to rs.getString("value").toInt() }.toMap()
        }
    }

    private val nameCache = HashMap<Int, String>()

    fun rowName(dbrow: Int): String = synchronized(nameCache) {
        nameCache.getOrPut(dbrow) {
            if (!RsDatabase.available) return@getOrPut ""
            val raw = RsDatabase.queryAll(
                "SELECT value FROM dbrow_value WHERE table_id = $STRIP_TABLE AND column_id = 0 AND idx = 0 AND row_id = ?",
                dbrow
            ) { it.getString("value") }.firstOrNull() ?: return@getOrPut ""
            runCatching { JsonParser().parse(raw).asJsonArray[0].asString }.getOrDefault(raw)
        }
    }

    fun stripRowAt(iface: Int, component: Int, slot: Int): StripRow? {
        val kind = stripKinds[iface to component] ?: return null
        val key = keyForSlot(slot) ?: return null
        val rowEnum = stripEnums[kind] ?: return null
        val dbrow = rowsOf(rowEnum)[key] ?: return null
        return StripRow(kind, key, dbrow, rowName(dbrow))
    }

    @Volatile
    var stripClicks: Int = 0
        private set

    internal fun resetStripCounter() {
        stripClicks = 0
    }

    fun noteStripClick(player: WorldPlayer, iface: Int, component: Int, slot: Int): StripRow? {
        val row = stripRowAt(iface, component, slot) ?: return null
        stripClicks++
        logger.info {
            "icon strip: ${player.name} clicked $iface:$component slot $slot = kind ${row.kind} key ${row.key} " +
                    "dbrow ${row.dbrow} '${row.name}'" +
                    (if (isWornStatsRow(row)) "" else " (not handled)")
        }
        return row
    }

    val WORN_STATS_ROW = Names950.dbrowId("worn_combat_stats")
    const val WORN_STRIP_KIND = 3
    val WORN_STATS_CATEGORY = Names950.structId("toplevel_v2_parent_my_hero")
    val WORN_STATS_TAB = Names950.structId("toplevel_v2_parent_tab_loadout")

    fun isWornStatsRow(row: StripRow): Boolean =
        row.kind == WORN_STRIP_KIND && row.key == 0 && row.dbrow == WORN_STATS_ROW

    private val INV_OVERLAY_ITEM_LAYER = Names950.comp("toplevel_v2_parent_suboverlay_inventory:item_layer").component
    private val INV_OVERLAY_PREMIUM_ITEM_LAYER =
        Names950.comp("toplevel_v2_parent_suboverlay_inventory:premium_currency_item_layer").component
    private val WORN_OVERLAY_ITEM_LAYER = Names950.comp("toplevel_v2_parent_suboverlay_worn:item_layer").component

    val LOADOUT_WINDOW_EVENTS: List<IntArray> = listOf(
        intArrayOf(BACKPACK_STRIP_IFACE_WINDOWED, INV_OVERLAY_ITEM_LAYER, 0, 27, 15433102),
        intArrayOf(BACKPACK_STRIP_IFACE_WINDOWED, INV_OVERLAY_PREMIUM_ITEM_LAYER, 0, 17, 1422),
        intArrayOf(BACKPACK_STRIP_IFACE_WINDOWED, BACKPACK_STRIP_COMPONENT_WINDOWED, 0, 1, 2099198),
        intArrayOf(BACKPACK_STRIP_IFACE_WINDOWED, BACKPACK_STRIP_COMPONENT_WINDOWED, 4096, 4097, 2099198),
        intArrayOf(BACKPACK_STRIP_IFACE_WINDOWED, BACKPACK_STRIP_COMPONENT_WINDOWED, 4352, 4353, 2099198),
        intArrayOf(BACKPACK_STRIP_IFACE_WINDOWED, BACKPACK_STRIP_COMPONENT_WINDOWED, 4608, 4609, 2099198),
        intArrayOf(BACKPACK_STRIP_IFACE_WINDOWED, BACKPACK_STRIP_COMPONENT_WINDOWED, 4864, 4865, 2099198),
        intArrayOf(BACKPACK_STRIP_IFACE_WINDOWED, BACKPACK_STRIP_COMPONENT_WINDOWED, 5120, 5121, 2099198),
        intArrayOf(OPEN_BUTTON_IFACE_WINDOWED, WORN_OVERLAY_ITEM_LAYER, 0, 18, 15302654),
        intArrayOf(OPEN_BUTTON_IFACE_WINDOWED, OPEN_BUTTON_COMPONENT_WINDOWED, 0, 1, 2046),
        intArrayOf(OPEN_BUTTON_IFACE_WINDOWED, OPEN_BUTTON_COMPONENT_WINDOWED, 4096, 4097, 2046),
        intArrayOf(OPEN_BUTTON_IFACE_WINDOWED, OPEN_BUTTON_COMPONENT_WINDOWED, 4352, 4353, 2046),
        intArrayOf(OPEN_BUTTON_IFACE_WINDOWED, OPEN_BUTTON_COMPONENT_WINDOWED, 4608, 4609, 2046),
        intArrayOf(OPEN_BUTTON_IFACE_WINDOWED, OPEN_BUTTON_COMPONENT_WINDOWED, 4864, 4865, 2046),
        intArrayOf(OPEN_BUTTON_IFACE_WINDOWED, WORN_OVERLAY_ITEM_LAYER, 0, 18, 10749950),
    )

    fun armLoadoutWindow(player: WorldPlayer): Int {
        var n = 0
        for (e in LOADOUT_WINDOW_EVENTS) {
            if (!player.interfaces.isOpened(e[0])) continue
            player.interfaces.events(id = e[0], component = e[1], from = e[2], to = e[3], mask = e[4])
            n++
        }
        return n
    }

    fun answerStripRow(player: WorldPlayer, iface: Int, row: StripRow): Boolean {
        if (!isWornStatsRow(row)) return false
        if (!player.interfaces.isOpened(iface)) {
            logger.info { "icon strip: ignored '${row.name}' from ${player.name}, interface $iface is not open" }
            return true
        }
        val opened = ParentWindows.open(player, WORN_STATS_CATEGORY, WORN_STATS_TAB)
        val armed = if (opened) armLoadoutWindow(player) else 0
        if (armed > 0) logger.info { "icon strip: armed $armed of ${LOADOUT_WINDOW_EVENTS.size} event rows on $BACKPACK_STRIP_IFACE_WINDOWED / $OPEN_BUTTON_IFACE_WINDOWED for ${player.name}" }
        logger.info {
            "icon strip: ${player.name} pressed '${row.name}' ($iface) -> Hero window " +
                    "${if (opened) "opened" else "not opened"}"
        }
        return true
    }

    fun describe(): String =
        "toolbelt panel: interface $IFACE at $TOPLEVEL:$MOUNT, ${slots.size} belt structs, " +
                "opened from slot $OPEN_BUTTON_SLOT of $OPEN_BUTTON_IFACE:$OPEN_BUTTON_COMPONENT"
}

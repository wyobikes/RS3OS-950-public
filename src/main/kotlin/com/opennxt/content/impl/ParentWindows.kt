package com.opennxt.content.impl

import com.opennxt.model.InterfaceHash
import com.opennxt.model.world.WorldPlayer
import com.opennxt.net.game.clientprot.IfButtonN
import com.opennxt.net.game.serverprot.PlayerSnapshot
import com.opennxt.net.game.serverprot.RunClientScript
import com.opennxt.net.game.serverprot.generated.ClearPlayerSnapshot
import com.opennxt.net.game.serverprot.generated.IfSetplayermodelSnapshot
import com.opennxt.net.game.serverprot.variables.ClientSetvarcSmall
import com.opennxt.net.game.serverprot.variables.ClientSetvarcstrSmall
import com.opennxt.net.game.serverprot.ifaces.IfClosesub
import com.opennxt.net.game.serverprot.ifaces.IfSethide
import com.opennxt.resources.sqlite.RsDatabase
import mu.KotlinLogging

object ParentWindows {
    private val logger = KotlinLogging.logger { }

    const val PARENT_IFACE = 1448

    const val LOADING_LAYER = 1

    val TAB_LAYERS = intArrayOf(3, 5, 7, 9, 11)

    private val ALL_LAYER_COMPONENTS = (3..12).toList()

    const val TOPLEVEL = 1477
    const val PARENT_MOUNT = 715

    const val CHROME_MOUNT = 711
    const val CHROME_IFACE = 1893

    const val SKILLGUIDE_IFACE = 1218
    const val SKILLGUIDE_LOADER = 1217

    const val VARC_OPEN_PARENT = 2911

    const val VARC_SKILLGUIDE_SKILL = 1753
    const val SCRIPT_SHOW_SKILLGUIDE = 5682
    const val DEFAULT_SKILLGUIDE_PAGE = 1

    private const val P_PARENT_IFACE = 3444
    private const val P_TAB_FIRST = 3448
    private const val P_TAB_LAST = 3453
    private const val P_DEFAULT_TAB = 3454
    private const val P_TAB_VARBIT = 3481
    private const val P_NAME = 3493

    private const val P_BANNER_IFACE = 6318

    private const val T_NAME = 3455
    private const val T_PANEL_FIRST = 3456
    private const val T_PANEL_STRIDE = 5
    private const val T_PANEL_GROUPS = 5

    private const val SCRIPT_SET_COMPONENT_SIZE = 11145

    val enabled: Boolean get() = System.getProperty("opennxt.experiment.parentWindows") != "false"

    data class Panel(val interfaceId: Int, val x: Int, val y: Int, val width: Int, val height: Int)
    data class Tab(val structId: Int, val name: String?, val panels: List<Panel>)
    data class Category(
        val structId: Int,
        val name: String?,
        val tabStructIds: List<Int>,
        val defaultTabStructId: Int?,
        val tabVarbit: Int?,
        val bannerIface: Int? = null
    )

    private val BOOK_EVENTS = listOf(intArrayOf(1, 0, 264, 97286), intArrayOf(7, 7, 16, 2))
    private val BOOK_EVENTS_MAGIC = listOf(intArrayOf(1, 0, 264, 97358), intArrayOf(7, 7, 16, 2), intArrayOf(7, 7, 10, 10319874))
    private val BOOK_EVENTS_EXTRA = listOf(intArrayOf(1, 0, 264, 97286), intArrayOf(7, 7, 16, 2), intArrayOf(7, 7, 10, 10319874))
    private val BOOK_EVENTS_1460 = listOf(intArrayOf(1, 0, 264, 97286), intArrayOf(5, 7, 16, 2))

    private val SUB_BOOK_OTHER = listOf(intArrayOf(7, 7, 16, 2), intArrayOf(1, 0, 264, 10427462), intArrayOf(1, 0, 264, 8592390))
    private val STATIC_PANEL_EVENTS: Map<Int, List<IntArray>> = mapOf(
        365 to listOf(intArrayOf(13, 0, 71, 2), intArrayOf(20, 0, 300, 2), intArrayOf(17, 0, 71, 1024), intArrayOf(19, 0, 0, 16777218), intArrayOf(19, 4096, 4096, 16777218)),
        1460 to BOOK_EVENTS_1460, 1452 to BOOK_EVENTS, 1449 to BOOK_EVENTS, 1882 to BOOK_EVENTS, 1220 to BOOK_EVENTS, 1221 to BOOK_EVENTS,
        1461 to BOOK_EVENTS_MAGIC, 1884 to BOOK_EVENTS_MAGIC, 1885 to BOOK_EVENTS_MAGIC, 1886 to BOOK_EVENTS_MAGIC, 1887 to BOOK_EVENTS_MAGIC,
        1219 to BOOK_EVENTS_EXTRA, 1883 to BOOK_EVENTS_EXTRA,
        1456 to SUB_BOOK_OTHER, 1459 to SUB_BOOK_OTHER, 1207 to SUB_BOOK_OTHER, 1880 to SUB_BOOK_OTHER
    )

    val PANEL_EVENTS_FILE: java.nio.file.Path = com.opennxt.Constants.DATA_PATH.resolve("seed").resolve("panel_events_950.tsv")

    private fun loadPanelEventsFile(): Map<Int, List<IntArray>> {
        if (!java.nio.file.Files.isRegularFile(PANEL_EVENTS_FILE)) {
            logger.warn { "parentWindows: $PANEL_EVENTS_FILE is missing - only the static panel events are armed" }
            return emptyMap()
        }
        val out = LinkedHashMap<Int, MutableList<IntArray>>()
        for (raw in java.nio.file.Files.readAllLines(PANEL_EVENTS_FILE)) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val f = line.split('\t', ' ').filter { it.isNotEmpty() }.map { it.toInt() }
            if (f.size != 5) continue
            out.getOrPut(f[0]) { ArrayList() }.add(intArrayOf(f[1], f[2], f[3], f[4]))
        }
        return out
    }

    val PANEL_EVENTS: Map<Int, List<IntArray>> by lazy { STATIC_PANEL_EVENTS + loadPanelEventsFile() }

    const val CATEGORY_SETTINGS = 21178
    const val CATEGORY_CUSTOMISATIONS = 32482

    const val VARP_CATEGORY_TABS_A = 3708
    const val VARP_CATEGORY_TABS_B = 3709
    const val CATEGORY_INDEX_BITS = 0x1FF
    data class TabField(val varp: Int, val shift: Int)
    val TAB_FIELD: Map<Int, TabField> = mapOf(
        21142 to TabField(3708, 9),
        32482 to TabField(3708, 13),
        21153 to TabField(3708, 17),
        21159 to TabField(3708, 21),
        21178 to TabField(3709, 4),
        21186 to TabField(3709, 8)
    )

    fun categoryVarps(current3708: Int, current3709: Int, categoryStructId: Int, tabIndex: Int): Pair<Int, Int> {
        var a = current3708
        var b = current3709
        parentIndexOf(categoryStructId)?.let { a = (a and CATEGORY_INDEX_BITS.inv()) or (it and CATEGORY_INDEX_BITS) }
        TAB_FIELD[categoryStructId]?.let { f ->
            val v = ((tabIndex + 1) and 0xF) shl f.shift
            val m = (0xF shl f.shift).inv()
            if (f.varp == VARP_CATEGORY_TABS_A) a = (a and m) or v else b = (b and m) or v
        }
        return a to b
    }

    private val sendSizeScript: Boolean
        get() = !com.opennxt.model.entity.rendering.UpdateBlockType.experimentalBuild() ||
            System.getProperty("opennxt.experiment.parentWindows.sizeScript") == "on"

    const val WARDROBE_IFACE = 1843
    private val WARDROBE_VARCSTRS = listOf(5940, 5941, 5942, 5943, 8417, 8418, 8419, 8420)

    const val SCRIPT_TAB_LEAVE = 187

    const val VARP_CUSTOMISATION = 256

    val TAB_SUBPANEL: Map<Int, Int> = mapOf(32483 to 2, 21152 to 1, 21150 to 3, 21151 to 4, 21145 to 5)

    fun customisationVarpWrites(before: Int, subPanel: Int): IntArray {
        val w1 = (before and 0x1F.inv()) or (subPanel and 0x1F)
        val w2 = (w1 and (0x7F shl 15).inv()) or (0x7F shl 15)
        val w3 = (w2 and (0x3FF shl 5).inv()) or (0x3FF shl 5)
        return intArrayOf(w1, w2, w3)
    }

    fun tabLeaveArgs(v3708: Int, v3709: Int): Pair<Int, Int>? {
        val index = v3708 and CATEGORY_INDEX_BITS
        val category = PARENT_INDEX.entries.firstOrNull { it.value == index }?.key ?: return null
        val field = TAB_FIELD[category] ?: return null
        val source = if (field.varp == VARP_CATEGORY_TABS_A) v3708 else v3709
        return index to ((source ushr field.shift) and 0xF)
    }

    const val SCRIPT_PREVIEW_RESET_A = 2716
    const val SCRIPT_PREVIEW_RESET_B = 6453
    val TAB_RESET_PREVIEW: Set<Int> = setOf(21152, 21150, 21145)

    const val SCRIPT_WARDROBE_MODEL = 13187

    const val TAB_WARDROBE = 32483
    const val TAB_ANIMATIONS = 21151
    const val TAB_PETS = 21145

    const val CUSTOMISATION_HOST = 1311

    val DEFERRED_ARM: Set<Int> = setOf(WARDROBE_IFACE, CUSTOMISATION_HOST)

    const val SCRIPT_MTX_REFRESH = 6874
    const val SCRIPT_ITEM_REFRESH = 10623
    val DRESSING_REFRESH_FIRST = 6196
    val DRESSING_REFRESH_LAST = listOf(35804, 35826)
    const val SCRIPT_PETS_RESET_A = 7422
    const val SCRIPT_PETS_RESET_B = 7425

    val DRESSING_HIDES_FIRST: List<Int> = listOf(449)
    val DRESSING_HIDES_SECOND: List<Int> = listOf(367, 472, 363, 449, 363, 363)

    val DRESSING_GRID_COMPONENTS: List<Int> = (195 downTo 172).filter { it != 176 }
    const val DRESSING_GRID_TO = 3069
    const val DRESSING_GRID_MASK = 14
    const val DRESSING_LIST_COMPONENT = 504
    const val DRESSING_LIST_TO = 204
    const val DRESSING_LIST_MASK = 6

    val WARDROBE_CLEARED_VARCS: List<Int> = listOf(1963, 1964, 1966, 1965)

    private fun sendTabContent(player: WorldPlayer, tabStructId: Int, tabSwitch: Boolean) {
        val w = player.client
        fun hide1311(component: Int) = w.write(IfSethide(InterfaceHash(CUSTOMISATION_HOST, component), true))
        fun armDressingGrids() {
            for (c in DRESSING_GRID_COMPONENTS) player.interfaces.events(id = CUSTOMISATION_HOST, component = c, from = 0, to = DRESSING_GRID_TO, mask = DRESSING_GRID_MASK, native949 = true)
            player.interfaces.events(id = CUSTOMISATION_HOST, component = DRESSING_LIST_COMPONENT, from = 0, to = DRESSING_LIST_TO, mask = DRESSING_LIST_MASK, native949 = true)
        }
        when {
            tabStructId == TAB_WARDROBE -> {
                if (tabSwitch) for (v in WARDROBE_CLEARED_VARCS) w.write(ClientSetvarcSmall(v, -1))
                w.write(RunClientScript(SCRIPT_MTX_REFRESH, emptyArray()))
                if (tabSwitch) w.write(RunClientScript(SCRIPT_WARDROBE_MODEL, arrayOf(1, -1)))
            }
            tabStructId == TAB_ANIMATIONS -> arm(player, CUSTOMISATION_HOST)
            tabStructId in TAB_RESET_PREVIEW && tabSwitch -> {
                for (c in DRESSING_HIDES_FIRST) hide1311(c)
                w.write(RunClientScript(SCRIPT_PREVIEW_RESET_A, arrayOf(-1, 0)))
                w.write(RunClientScript(SCRIPT_PREVIEW_RESET_B, arrayOf(-1, 0, 0)))
                w.write(RunClientScript(SCRIPT_ITEM_REFRESH, arrayOf(DRESSING_REFRESH_FIRST, 0)))
                for (c in DRESSING_HIDES_SECOND) hide1311(c)
                if (tabStructId == TAB_PETS) sendPetsResets(player)
                arm(player, CUSTOMISATION_HOST)
                armDressingGrids()
                w.write(RunClientScript(SCRIPT_MTX_REFRESH, emptyArray()))
                hide1311(363)
                for (id in DRESSING_REFRESH_LAST) w.write(RunClientScript(SCRIPT_ITEM_REFRESH, arrayOf(id, 0)))
            }
            tabStructId in TAB_RESET_PREVIEW -> {
                if (tabStructId == TAB_PETS) sendPetsResets(player)
                arm(player, CUSTOMISATION_HOST)
                armDressingGrids()
                w.write(RunClientScript(SCRIPT_MTX_REFRESH, emptyArray()))
                hide1311(363)
            }
        }
    }

    private fun sendPetsResets(player: WorldPlayer) {
        player.client.write(RunClientScript(SCRIPT_PETS_RESET_A, arrayOf(-1, -1, -1, 0, 0, 0, 0, -1, 0, 0)))
        player.client.write(RunClientScript(SCRIPT_PETS_RESET_B, arrayOf("")))
    }

    private fun arm(player: WorldPlayer, iface: Int) {
        for (e in PANEL_EVENTS[iface].orEmpty()) player.interfaces.events(id = iface, component = e[0], from = e[1], to = e[2], mask = e[3], native949 = true)
    }

    private fun openWardrobeSubs(player: WorldPlayer) {
        wardrobeRequests.remove(player)
        wardrobeAnswered.remove(player)
        val own = if (wardrobeSnapshotsEnabled) ownSnapshotRecord(player) else null
        if (own != null) {
            player.client.write(PlayerSnapshot(WARDROBE_OWN_SNAPSHOT, 0, own))
        } else if (wardrobeSnapshotsEnabled) {
            logger.warn { "parentWindows: ${player.name}: could not build own-outfit snapshot; wardrobe preview left empty" }
        }
        player.interfaces.open(id = 1841, parent = WARDROBE_IFACE, component = 264, walkable = true, native949 = true)
        arm(player, 1841)
        player.interfaces.open(id = 1840, parent = 1841, component = 3, walkable = true, native949 = true)
        for (i in 0..7) player.interfaces.open(id = 1832 + i, parent = WARDROBE_IFACE, component = 252 + i, walkable = true, native949 = true)
        for (v in WARDROBE_VARCSTRS) player.client.write(ClientSetvarcstrSmall(v, ""))
        player.client.write(RunClientScript(SCRIPT_WARDROBE_MODEL, arrayOf(1, -1)))
        if (own != null) {
            player.client.write(IfSetplayermodelSnapshot(InterfaceHash(WARDROBE_PREVIEW_IFACE, WARDROBE_PREVIEW_COMPONENT).hash, WARDROBE_OWN_SNAPSHOT))
        }
        arm(player, WARDROBE_IFACE)
        if (!tutorialSeen(player.varpValue(VARP_MTX_TUTORIAL))) {
            player.interfaces.open(id = TUTORIAL_IFACE, parent = WARDROBE_IFACE, component = TUTORIAL_MOUNT, walkable = true, native949 = true)
        }
    }

    val WARDROBE_SUB_CLOSES: List<Pair<Int, Int>> =
        listOf(WARDROBE_IFACE to 252, WARDROBE_IFACE to 264, 1841 to 3) + (253..259).map { WARDROBE_IFACE to it }

    const val WARDROBE_OWN_SNAPSHOT = 1
    const val WARDROBE_PREVIEW_IFACE = 1840
    const val WARDROBE_PREVIEW_COMPONENT = 3
    const val WARDROBE_GRID_COMPONENT = 183
    val WARDROBE_PANE_OPS: IntRange = 2..9
    val WARDROBE_SNAPSHOT_CLEARS: List<Int> = (1..9).toList()
    const val APPEARANCE_RECORD_OFFSET = 2

    fun storePaneComponent(op: Int): Int = InterfaceHash(1832 + op - WARDROBE_PANE_OPS.first, 5).hash

    val wardrobeSnapshotsEnabled: Boolean
        get() = com.opennxt.model.entity.rendering.UpdateBlockType.experimentalBuild() &&
            System.getProperty("opennxt.experiment.parentWindows.wardrobeSnapshot") != "off"

    private val appearanceSlotCount: Int by lazy {
        com.opennxt.resources.FilesystemResources.instance.defaults
            .get<com.opennxt.resources.defaults.wearpos.WearposDefaults>().slots
            .count { it != com.opennxt.model.entity.player.appearance.PlayerModel.SLOT_SKIPPED_BY_CLIENT }
    }

    fun ownSnapshotRecord(player: WorldPlayer): ByteArray? {
        val model = player.entity.model
        if (model.dirty) model.refresh()
        val data = model.data
        val length = PlayerSnapshot.recordLength(data, APPEARANCE_RECORD_OFFSET, appearanceSlotCount) ?: return null
        return data.copyOfRange(APPEARANCE_RECORD_OFFSET, APPEARANCE_RECORD_OFFSET + length)
    }

    private fun clearWardrobeSnapshots(player: WorldPlayer) {
        wardrobeRequests.remove(player)
        wardrobeAnswered.remove(player)
        if (!wardrobeSnapshotsEnabled) return
        for (slot in WARDROBE_SNAPSHOT_CLEARS) player.client.write(ClearPlayerSnapshot(slot))
    }

    private val wardrobeRequests = java.util.Collections.synchronizedMap(
        java.util.WeakHashMap<WorldPlayer, MutableSet<Long>>()
    )

    private fun noteWardrobePaneRequest(player: WorldPlayer, op: Int, key: Int) {
        if (!player.interfaces.isOpened(WARDROBE_IFACE)) return
        val seen = wardrobeRequests.getOrPut(player) { java.util.HashSet() }
        if (seen.add((op.toLong() shl 32) or (key.toLong() and 0xffffffffL))) {
            val why = when {
                !wardrobePanesEnabled -> "store panes are off (opennxt.experiment.parentWindows.wardrobePanes)"
                outfitFor(key) == null -> "no outfit for that key in dbtable $OUTFIT_TABLE"
                else -> "own appearance snapshot unavailable"
            }
            logger.info { "parentWindows: ${player.name} Wardrobe pane $op key $key not answered: $why" }
        }
    }

    const val OUTFIT_TABLE = 163
    const val OUTFIT_COL_KEY = 0
    const val OUTFIT_COL_ITEM = 4
    const val OUTFIT_COL_CATEGORY = 8
    const val OUTFIT_COL_SET = 13
    const val OUTFIT_COL_GENDER = 19
    const val OUTFIT_CATEGORY_PRESET = 12
    val EMPTY_PRESET_OUTFIT: Map<Int, Int> = mapOf(0 to 39754, 4 to 39755, 7 to 39756, 9 to 39758, 10 to 39757)
    const val SCRIPT_PANE_MODEL = SCRIPT_WARDROBE_MODEL

    val wardrobePanesEnabled: Boolean
        get() = wardrobeSnapshotsEnabled && System.getProperty("opennxt.experiment.parentWindows.wardrobePanes") != "off"

    data class OutfitRow(val row: Int, val key: Int, val item: Int?, val category: Int?, val set: Int?, val gender: Int? = null)

    val outfitRows: Map<Int, OutfitRow> by lazy { loadOutfitRows() }

    private val outfitSets: Map<Int, List<OutfitRow>> by lazy {
        outfitRows.values.filter { it.set != null && it.item != null }.groupBy { it.set!! }.mapValues { e -> e.value.sortedBy { it.row } }
    }

    private fun loadOutfitRows(): Map<Int, OutfitRow> {
        if (!RsDatabase.available) return emptyMap()
        val cols = HashMap<Int, HashMap<Int, Int>>()
        try {
            RsDatabase.queryAll(
                "SELECT row_id, column_id, value FROM dbrow_value WHERE table_id = $OUTFIT_TABLE AND idx = 0 AND column_id IN " +
                    "($OUTFIT_COL_KEY, $OUTFIT_COL_ITEM, $OUTFIT_COL_CATEGORY, $OUTFIT_COL_SET, $OUTFIT_COL_GENDER)"
            ) { rs ->
                val v = rs.getString("value")?.trim()?.removePrefix("[")?.removeSuffix("]")?.trim()?.toIntOrNull()
                if (v != null) cols.getOrPut(rs.getInt("row_id")) { HashMap() }[rs.getInt("column_id")] = v
                0
            }
        } catch (e: Exception) {
            logger.warn(e) { "parentWindows: cannot read dbtable $OUTFIT_TABLE - Wardrobe store panes stay unanswered" }
            return emptyMap()
        }
        val out = HashMap<Int, OutfitRow>()
        for ((row, c) in cols) {
            val key = c[OUTFIT_COL_KEY] ?: continue
            out.putIfAbsent(key, OutfitRow(row, key, c[OUTFIT_COL_ITEM], c[OUTFIT_COL_CATEGORY], c[OUTFIT_COL_SET], c[OUTFIT_COL_GENDER]))
        }
        logger.info { "parentWindows: dbtable $OUTFIT_TABLE - ${out.size} Wardrobe keys" }
        return out
    }

    data class ItemWear(val slot: Int, val hides: Set<Int>)

    private val itemWearCache = HashMap<Int, ItemWear?>()

    fun itemWear(item: Int): ItemWear? = synchronized(itemWearCache) {
        if (itemWearCache.containsKey(item)) return itemWearCache[item]
        val wear = readItemWear(item)
        itemWearCache[item] = wear
        wear
    }

    private fun readItemWear(item: Int): ItemWear? {
        if (!RsDatabase.available) return null
        val row = RsDatabase.queryAll("SELECT equipSlotId, equipId FROM items WHERE id = ?", item) { rs ->
            (rs.getString("equipSlotId")?.trim()?.toIntOrNull()) to (rs.getString("equipId")?.trim()?.toIntOrNull())
        }.firstOrNull() ?: return null
        val slot = row.first ?: return null
        val third = RsDatabase.queryAll("SELECT value FROM items_attr WHERE id = ? AND field = 'unknown_1B'", item) {
            it.getString("value")?.trim()?.toIntOrNull()
        }.firstOrNull()
        return ItemWear(slot, setOfNotNull(row.second?.takeIf { it >= 0 }, third?.takeIf { it >= 0 }))
    }

    fun outfitFor(key: Int, female: Boolean = false): Map<Int, Int>? {
        val row = outfitRows[key] ?: return null
        if (row.category == OUTFIT_CATEGORY_PRESET && row.item == null) return EMPTY_PRESET_OUTFIT
        val body = if (female) 1 else 0
        val members = when {
            row.item != null -> listOf(row)
            row.set != null -> outfitSets[row.set].orEmpty().filter { it.gender == null || it.gender == body }
            else -> emptyList()
        }
        val out = LinkedHashMap<Int, Int>()
        for (m in members) {
            val item = m.item ?: continue
            val wear = itemWear(item) ?: continue
            out.putIfAbsent(wear.slot, item)
        }
        return out.takeIf { it.isNotEmpty() }
    }

    val appearanceReadIndices: List<Int> by lazy {
        val slots = com.opennxt.resources.FilesystemResources.instance.defaults
            .get<com.opennxt.resources.defaults.wearpos.WearposDefaults>().slots
        slots.indices.filter { slots[it] != com.opennxt.model.entity.player.appearance.PlayerModel.SLOT_SKIPPED_BY_CLIENT }
    }

    fun recordTail(record: ByteArray, slotCount: Int): ByteArray? {
        var p = 0
        for (i in 0 until slotCount) {
            val (v, next) = PlayerSnapshot.varint(record, p) ?: return null
            if (i == 0 && v == PlayerSnapshot.MODEL_OVERRIDE) return null
            p = next
        }
        if (p + 2 > record.size || record[p].toInt() != 0 || record[p + 1].toInt() != 0) return null
        return record.copyOfRange(p + 2, record.size)
    }

    fun buildPaneRecord(readIndices: List<Int>, outfit: Map<Int, Int>, hidden: Set<Int>, kitValue: (Int) -> Int, tail: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        for (index in readIndices) {
            val item = outfit[index]
            var v = when {
                item != null -> com.opennxt.model.entity.player.appearance.PlayerModel.OBJ_BIAS + item
                index in hidden -> com.opennxt.model.entity.player.appearance.PlayerModel.SLOT_EMPTY
                else -> kitValue(index)
            }
            while (v > 0x7f) { out.write((v and 0x7f) or 0x80); v = v ushr 7 }
            out.write(v)
        }
        out.write(0); out.write(0)
        out.write(tail)
        return out.toByteArray()
    }

    private val wardrobeAnswered = java.util.Collections.synchronizedMap(
        java.util.WeakHashMap<WorldPlayer, IntArray>()
    )

    fun answerWardrobePane(player: WorldPlayer, op: Int, key: Int): Boolean {
        if (!wardrobePanesEnabled || op !in WARDROBE_PANE_OPS || !player.interfaces.isOpened(WARDROBE_IFACE)) return false
        val answered = wardrobeAnswered.getOrPut(player) { IntArray(WARDROBE_PANE_OPS.last + 1) { Int.MIN_VALUE } }
        if (answered[op] == key) {
            val seen = wardrobeRequests.getOrPut(player) { java.util.HashSet() }
            if (seen.add(-((op.toLong() shl 32) or (key.toLong() and 0xffffffffL)) - 1)) {
                logger.warn { "parentWindows: ${player.name} Wardrobe pane $op requested key $key again after it was answered" }
            }
            return true
        }
        val female = player.entity.model.gender == com.opennxt.model.entity.player.appearance.Gender.FEMALE
        val outfit = outfitFor(key, female) ?: return false
        val own = ownSnapshotRecord(player) ?: return false
        val readIndices = appearanceReadIndices
        val tail = recordTail(own, readIndices.size) ?: return false
        val hidden = outfit.values.flatMap { itemWear(it)?.hides.orEmpty() }.toSet()
        val model = player.entity.model
        val record = buildPaneRecord(readIndices, outfit, hidden, model::kitSlotValue, tail)
        player.client.write(PlayerSnapshot(op, 0, record))
        player.client.write(IfSetplayermodelSnapshot(storePaneComponent(op), op))
        player.client.write(RunClientScript(SCRIPT_PANE_MODEL, arrayOf(op, key)))
        answered[op] = key
        logger.info { "parentWindows: ${player.name} Wardrobe pane $op key $key -> ${outfit.values.joinToString(",")} (hides ${hidden.sorted()}), ${record.size}-byte snapshot" }
        return true
    }

    val PANEL_LOADERS: Map<Int, Pair<Int, Int>> = mapOf(1426 to (742 to 0))

    val categories: Map<Int, Category> by lazy { loadCategories() }

    val SLOT_TO_PARENT: Map<Int, Int> by lazy {
        val override = System.getProperty("opennxt.experiment.parentWindows.slots")
        if (override != null) {
            override.split(',').mapNotNull {
                val parts = it.split(':')
                val slot = parts.getOrNull(0)?.trim()?.toIntOrNull()
                val parent = parts.getOrNull(1)?.trim()?.toIntOrNull()
                if (slot != null && parent != null) slot to parent else null
            }.toMap()
        } else mapOf(
            0 to 21142,
            1 to 32482,
            2 to 21153,
            3 to 21159,
            5 to 21186
        )
    }

    const val RIBBON_LOGOUT_ID = 7

    const val VARP_SIDE_PANEL = 9778
    const val RIBBON_IFACE = 1431
    fun openPanelCount(player: WorldPlayer): Int = openPanels[player]?.size ?: 0
    val RIBBON_SIDE_PANEL: Map<Int, Int> = mapOf(8 to 1, 9 to 3, 10 to 4, 12 to 6)

    private val currentCategory = java.util.Collections.synchronizedMap(
        java.util.WeakHashMap<WorldPlayer, Int>()
    )

    fun tabIndexOf(slot: Int): Int? = if (slot >= 3 && (slot - 3) % 4 == 0) (slot - 3) / 4 else null

    fun handleTabClick(player: WorldPlayer, slot: Int): Boolean {
        if (!enabled) return false
        val category = currentCategory[player] ?: run {
            logger.info { "parentWindows: ${player.name} clicked tab strip slot $slot with no category window open - ignored" }
            return false
        }
        val index = tabIndexOf(slot) ?: run {
            logger.info { "parentWindows: ${player.name} clicked 1477:714 slot $slot, which is not a tab (3 + 4n) - ignored" }
            return false
        }
        val tabs = categories[category]?.tabStructIds ?: return false
        val tab = tabs.getOrNull(index) ?: run {
            logger.info { "parentWindows: ${player.name} clicked tab $index of '${categories[category]?.name}', which declares ${tabs.size} tabs - ignored" }
            return false
        }
        return open(player, category, tab)
    }

    const val SCRIPT_PANEL_CLOSED = 8320
    const val PANEL_ID_MANAGEMENT_WINDOWS = 1001

    fun handleCloseClick(player: WorldPlayer, slot: Int): Boolean {
        if (!enabled) return false
        if (slot != 1) return false
        if (!player.interfaces.isOpened(PARENT_IFACE) || (currentCategory[player] == null && openPanels[player].isNullOrEmpty())) {
            logger.info { "parentWindows: ${player.name} sent 1477:717 with no category window open - ignored" }
            return false
        }
        val category = currentCategory.remove(player)
        val leave = tabLeaveArgs(player.varpValue(VARP_CATEGORY_TABS_A), player.varpValue(VARP_CATEGORY_TABS_B))
        if (category == CATEGORY_CUSTOMISATIONS) {
            for (v in customisationVarpWrites(player.varpValue(VARP_CUSTOMISATION), 0)) player.setVarpOverride(VARP_CUSTOMISATION, v)
        }
        player.client.write(ClientSetvarcSmall(VARC_OPEN_PARENT, -1))
        for ((layer, iface) in openPanels[player].orEmpty()) {
            if (iface == WARDROBE_IFACE) clearWardrobeSnapshots(player)
            player.interfaces.close(PARENT_IFACE, layer, native949 = true)
            closeSubMounts(player, iface)
        }
        player.interfaces.close(SKILLGUIDE_IFACE, 0, native949 = true)
        openPanels.remove(player)
        leave?.let { player.client.write(RunClientScript(SCRIPT_TAB_LEAVE, arrayOf(it.first, it.second))) }
        player.client.write(RunClientScript(SCRIPT_PANEL_CLOSED, arrayOf(PANEL_ID_MANAGEMENT_WINDOWS)))
        if (category == CATEGORY_CUSTOMISATIONS) for (v in WARDROBE_CLEARED_VARCS) player.client.write(ClientSetvarcSmall(v, -1))
        logger.info { "parentWindows: ${player.name} closed the category window" + (category?.let { " ('${categories[it]?.name}')" } ?: "") + "; 187 ${leave?.let { "[${it.first}, ${it.second}]" } ?: "unsent"}" }
        return true
    }

    private fun params(structId: Int): Map<Int, Pair<Int?, String?>> {
        val out = HashMap<Int, Pair<Int?, String?>>()
        RsDatabase.queryAll(
            "SELECT prop, intvalue, stringvalue FROM struct_param WHERE struct_id = ?", structId
        ) { rs ->
            val prop = rs.getInt("prop")
            val iv = rs.getInt("intvalue").let { if (rs.wasNull()) null else it }
            val sv = rs.getString("stringvalue")
            prop to (iv to sv)
        }.forEach { (prop, v) -> out[prop] = v }
        return out
    }

    private fun loadCategories(): Map<Int, Category> {
        if (!RsDatabase.available) return emptyMap()
        val ids = try {
            RsDatabase.queryAll(
                "SELECT DISTINCT struct_id FROM struct_param WHERE prop = $P_PARENT_IFACE " +
                    "AND intvalue = $PARENT_IFACE"
            ) { it.getInt("struct_id") }
        } catch (e: Exception) {
            logger.warn(e) { "parentWindows: cannot read struct_param - the ribbon categories are unavailable" }
            return emptyMap()
        }
        val out = LinkedHashMap<Int, Category>()
        for (id in ids) {
            val p = params(id)
            val tabs = (P_TAB_FIRST..P_TAB_LAST).mapNotNull { p[it]?.first }
            out[id] = Category(
                structId = id,
                name = p[P_NAME]?.second,
                tabStructIds = tabs,
                defaultTabStructId = p[P_DEFAULT_TAB]?.first ?: tabs.firstOrNull(),
                tabVarbit = p[P_TAB_VARBIT]?.first?.takeIf { it > 0 },
                bannerIface = p[P_BANNER_IFACE]?.first?.takeIf { it > 0 }
            )
        }
        logger.info {
            "parentWindows: ${out.size} ribbon categories from the cache - " +
                out.values.joinToString { "${it.structId} '${it.name}' (${it.tabStructIds.size} tabs)" }
        }
        return out
    }

    fun tab(structId: Int): Tab {
        val p = params(structId)
        val panels = ArrayList<Panel>(T_PANEL_GROUPS)
        for (g in 0 until T_PANEL_GROUPS) {
            val base = T_PANEL_FIRST + g * T_PANEL_STRIDE
            val iface = p[base]?.first ?: continue
            if (iface <= 0) continue
            panels += Panel(
                interfaceId = iface,
                x = p[base + 1]?.first ?: 0,
                y = p[base + 2]?.first ?: 0,
                width = p[base + 3]?.first ?: 0,
                height = p[base + 4]?.first ?: 0
            )
        }
        return Tab(structId, p[T_NAME]?.second, panels)
    }

    private const val CATEGORY_HERO = 21142
    private const val TAB_SKILLS = 21144

    const val SKILLS_IFACE = 1466
    const val SKILLS_BUTTON_LAYER = 7
    const val SKILLS_IFACE_ALT = 320
    const val SKILLS_BUTTON_LAYER_ALT = 9

    private const val ENUM_SKILL_ORDER = 7674
    private const val S_SKILL_NAME = 3439
    private const val S_SKILL_ID = 3440
    private const val S_SKILL_GUIDE = 3441

    data class SkillSlot(
        val slot: Int,
        val structId: Int,
        val name: String?,
        val skillId: Int,
        val guideId: Int
    )

    val skillSlots: Map<Int, SkillSlot> by lazy { loadSkillSlots() }

    val skillGuideStrip: Map<Int, Int> by lazy { loadSkillGuideStrip() }

    fun guideIdForSlot(slot: Int): Int? = skillSlots[slot]?.guideId

    fun guideIdForStripComponent(component: Int): Int? = skillGuideStrip[component]

    private fun loadSkillSlots(): Map<Int, SkillSlot> {
        if (!RsDatabase.available) return emptyMap()
        val order = com.opennxt.resources.sqlite.CacheEnums.intMap(ENUM_SKILL_ORDER)
        val out = LinkedHashMap<Int, SkillSlot>()
        for ((slot, structId) in order) {
            val p = params(structId)
            val guide = p[S_SKILL_GUIDE]?.first ?: continue
            val skill = p[S_SKILL_ID]?.first ?: continue
            out[slot] = SkillSlot(slot, structId, p[S_SKILL_NAME]?.second, skill, guide)
        }
        logger.info {
            "parentWindows: ${out.size} skills-panel slots from enum $ENUM_SKILL_ORDER - " +
                out.values.joinToString { "${it.slot}='${it.name}'(skill ${it.skillId}, guide ${it.guideId})" }
        }
        return out
    }

    private fun loadSkillGuideStrip(): Map<Int, Int> {
        if (!RsDatabase.available) return emptyMap()
        val rows = try {
            RsDatabase.queryAll(
                "SELECT i.component AS c, a.value AS v FROM interfaces i JOIN interfaces_attr a " +
                    "ON a.id = i.id WHERE i.iface = $SKILLGUIDE_IFACE AND a.field = 'scripts' " +
                    "ORDER BY i.component"
            ) { it.getInt("c") to it.getString("v") }
        } catch (e: Exception) {
            logger.warn(e) { "parentWindows: cannot read interface $SKILLGUIDE_IFACE's scripts" }
            return emptyMap()
        }
        val out = LinkedHashMap<Int, Int>()
        for ((component, json) in rows) {
            if (json == null) continue
            val obj = try {
                com.google.gson.JsonParser().parse(json).asJsonObject
            } catch (e: Exception) {
                continue
            }
            for ((_, value) in obj.entrySet()) {
                val trigger = value as? com.google.gson.JsonObject ?: continue
                val script = trigger.get("script")?.takeIf { it.isJsonPrimitive }?.asInt ?: continue
                if (script != SCRIPT_SHOW_SKILLGUIDE) continue
                val args = trigger.getAsJsonArray("args") ?: continue
                if (args.size() != 1 || !args.get(0).isJsonPrimitive) continue
                out[component] = args.get(0).asInt
            }
        }
        logger.info {
            "parentWindows: ${out.size} skill-guide strip buttons on $SKILLGUIDE_IFACE " +
                "(components ${out.keys.joinToString("/")})"
        }
        return out
    }

    private val currentSkillGuide = java.util.Collections.synchronizedMap(
        java.util.WeakHashMap<WorldPlayer, Int>()
    )

    fun handleSkillClick(player: WorldPlayer, skillSlot: Int): Boolean {
        if (!enabled) return false
        val entry = skillSlots[skillSlot] ?: run {
            logger.info {
                "parentWindows: ${player.name} clicked skills-panel slot $skillSlot, which is not one " +
                    "of the ${skillSlots.size} slots enum $ENUM_SKILL_ORDER declares - ignored"
            }
            return false
        }
        logger.info {
            "parentWindows: ${player.name} clicked skill slot $skillSlot ('${entry.name}', skill " +
                "${entry.skillId}) - opening the skill guide on page ${entry.guideId}"
        }
        return open(player, CATEGORY_HERO, TAB_SKILLS, skill = entry.guideId)
    }

    fun handleSkillGuideClick(player: WorldPlayer, component: Int): Boolean {
        if (!enabled) return false
        val guide = skillGuideStrip[component] ?: return false
        if (!player.interfaces.isOpened(SKILLGUIDE_IFACE)) {
            logger.info {
                "parentWindows: ${player.name} clicked $SKILLGUIDE_IFACE:$component with the skill " +
                    "guide not open - ignored"
            }
            return false
        }
        currentSkillGuide[player] = guide
        player.interfaces.open(
            id = SKILLGUIDE_LOADER, parent = SKILLGUIDE_IFACE, component = 0,
            walkable = true, native949 = true
        )
        logger.info {
            "parentWindows: ${player.name} picked skill guide page $guide off the strip " +
                "($SKILLGUIDE_IFACE:$component) - re-opened $SKILLGUIDE_LOADER on $SKILLGUIDE_IFACE:0"
        }
        return true
    }

    fun handleRibbonClick(player: WorldPlayer, slot: Int): Boolean {
        if (!enabled) return false
        if (slot == RIBBON_LOGOUT_ID) {
            logger.info { "parentWindows: ${player.name} pressed the ribbon Logout button" }
            return false
        }
        RIBBON_SIDE_PANEL[slot]?.let { value ->
            if (!player.interfaces.isOpened(RIBBON_IFACE)) {
                logger.info { "parentWindows: ${player.name} sent ribbon id $slot with $RIBBON_IFACE not open - ignored" }
                return false
            }
            player.client.write(com.opennxt.net.game.serverprot.variables.VarpSmall(VARP_SIDE_PANEL, value))
            logger.info { "parentWindows: ${player.name} selected side panel via ribbon id $slot -> varp $VARP_SIDE_PANEL = $value" }
            return true
        }
        val parent = SLOT_TO_PARENT[slot]
        if (parent == null) {
            logger.info {
                "parentWindows: ribbon slot $slot is unmapped; add it with " +
                    "-Dopennxt.experiment.parentWindows.slots=$slot:<categoryStructId>"
            }
            return false
        }
        return open(player, parent, null)
    }

    fun open(player: WorldPlayer, categoryStructId: Int, tabStructId: Int?, skill: Int? = null): Boolean {
        if (!enabled) return false
        val category = categories[categoryStructId] ?: run {
            logger.warn { "parentWindows: no category struct $categoryStructId in the cache" }
            return false
        }
        val chosen = tabStructId ?: category.defaultTabStructId ?: run {
            logger.warn { "parentWindows: category ${category.name} declares no tabs" }
            return false
        }
        var tab = tab(chosen)
        if (tab.panels.isEmpty()) {
            val fallback = category.tabStructIds.map { tab(it) }.firstOrNull { it.panels.isNotEmpty() }
            if (fallback == null) {
                logger.warn {
                    "parentWindows: no tab of '${category.name}' declares any panels - nothing to mount"
                }
                return false
            }
            logger.info {
                "parentWindows: '${category.name}' default tab $chosen declares no panels - " +
                    "using '${fallback.name}' (${fallback.structId}) instead"
            }
            tab = fallback
        }
        if (!player.interfaces.isOpened(PARENT_IFACE)) {
            logger.warn {
                "parentWindows: $PARENT_IFACE is not open for ${player.name}; cannot mount panels"
            }
            return false
        }

        val before3708 = player.varpValue(VARP_CATEGORY_TABS_A)
        val before3709 = player.varpValue(VARP_CATEGORY_TABS_B)
        val leave = tabLeaveArgs(before3708, before3709)
        val tabSwitch = currentCategory[player] == categoryStructId && !openPanels[player].isNullOrEmpty()
        val tabIndex = category.tabStructIds.indexOf(tab.structId).coerceAtLeast(0)
        val (v3708, v3709) = categoryVarps(before3708, before3709, categoryStructId, tabIndex)
        player.setVarpOverride(VARP_CATEGORY_TABS_A, v3708)
        if (TAB_FIELD[categoryStructId]?.varp == VARP_CATEGORY_TABS_B) player.setVarpOverride(VARP_CATEGORY_TABS_B, v3709)
        TAB_SUBPANEL[tab.structId]?.let { sub ->
            for (v in customisationVarpWrites(player.varpValue(VARP_CUSTOMISATION), sub)) player.setVarpOverride(VARP_CUSTOMISATION, v)
        }
        if (!tabSwitch) {
            player.client.write(IfSethide(InterfaceHash(TOPLEVEL, PARENT_MOUNT), false))
            player.interfaces.events(id = TOPLEVEL, component = 714, from = 0, to = 24, mask = 2, native949 = true)
            player.interfaces.events(id = TOPLEVEL, component = 717, from = 1, to = 1, mask = 2, native949 = true)
            player.interfaces.events(id = TOPLEVEL, component = 716, from = 0, to = 1, mask = 2, native949 = true)
            parentIndexOf(categoryStructId)?.let {
                player.client.write(ClientSetvarcSmall(VARC_OPEN_PARENT, it))
            }
        }

        closeOpenPanels(player, chrome = !tabSwitch)

        val banner = category.bannerIface
        if (!tabSwitch && System.getProperty("opennxt.experiment.ui.parentChrome") != "false" && banner != null) {
            player.interfaces.open(
                id = banner, parent = TOPLEVEL, component = CHROME_MOUNT,
                walkable = true, native949 = true
            )
            hide(player, CHROME_MOUNT, false, iface = TOPLEVEL)
            arm(player, banner)
        }

        val used = minOf(tab.panels.size, TAB_LAYERS.size)
        var guidePage: Int? = null
        for (i in 0 until used) {
            val panel = tab.panels[i]
            val layer = TAB_LAYERS[i]
            player.interfaces.open(
                id = panel.interfaceId, parent = PARENT_IFACE, component = layer,
                walkable = true, native949 = true
            )
            if (sendSizeScript) player.client.write(
                RunClientScript(
                    script = SCRIPT_SET_COMPONENT_SIZE,
                    args = arrayOf(
                        panel.width, panel.height, panel.x, panel.y,
                        InterfaceHash(PARENT_IFACE, layer).hash
                    )
                )
            )
            hide(player, layer, false)
            if (panel.interfaceId !in DEFERRED_ARM) for (e in PANEL_EVENTS[panel.interfaceId].orEmpty()) {
                if (panel.interfaceId == AbilityBar.WINDOW_BAR_IFACE && AbilityBar.isSlotRow(panel.interfaceId, e[0])) continue
                player.interfaces.events(id = panel.interfaceId, component = e[0], from = e[1], to = e[2], mask = e[3], native949 = true)
            }
            if (panel.interfaceId == AbilityBar.WINDOW_BAR_IFACE) AbilityBar.armSlots(player)
            hide(player, layer + 1, true)
            PANEL_LOADERS[panel.interfaceId]?.let { (child, component) ->
                player.interfaces.open(id = child, parent = panel.interfaceId, component = component, walkable = true, native949 = true)
            }
            if (panel.interfaceId == WARDROBE_IFACE) openWardrobeSubs(player)
            if (panel.interfaceId == SKILLGUIDE_IFACE) {
                val page = skill ?: currentSkillGuide[player] ?: DEFAULT_SKILLGUIDE_PAGE
                player.client.write(ClientSetvarcSmall(VARC_SKILLGUIDE_SKILL, page))
                player.client.write(RunClientScript(SCRIPT_SHOW_SKILLGUIDE, arrayOf(page)))
                currentSkillGuide[player] = page
                guidePage = page
            }
            openPanels[player] = (openPanels[player] ?: emptyMap()) + (layer to panel.interfaceId)
        }
        if (tab.panels.take(used).any { it.interfaceId == ACHIEVEMENTS_IFACE }) {
            sendAchievementSideTab(player, achievementSideTabFor(player.varpValue(VARP_ACHIEVEMENTS_TAB)), onOpen = true)
        }
        val usedComponents = TAB_LAYERS.take(used).flatMap { listOf(it, it + 1) }.toSet()
        for (comp in ALL_LAYER_COMPONENTS) if (comp !in usedComponents) hide(player, comp, true)

        if (leave != null) {
            player.client.write(RunClientScript(SCRIPT_TAB_LEAVE, arrayOf(leave.first, leave.second)))
        } else {
            logger.info { "parentWindows: ${player.name}: no tab field for window index ${before3708 and CATEGORY_INDEX_BITS}; script 187 not sent" }
        }
        hide(player, LOADING_LAYER, true)
        if (tabSwitch) parentIndexOf(categoryStructId)?.let { player.client.write(ClientSetvarcSmall(VARC_OPEN_PARENT, it)) }
        if (guidePage != null) {
            player.interfaces.open(
                id = SKILLGUIDE_LOADER, parent = SKILLGUIDE_IFACE, component = 0,
                walkable = true, native949 = true
            )
        }
        sendTabContent(player, tab.structId, tabSwitch)
        currentCategory[player] = categoryStructId

        logger.info {
            "parentWindows: ${player.name} ${if (tabSwitch) "switched to" else "opened"} '${category.name}' tab '${tab.name}' - " +
                tab.panels.joinToString {
                    "${com.opennxt.resources.Names949.iface(it.interfaceId)}@(${it.x},${it.y},${it.width}x${it.height})"
                } +
                " into 1448:${TAB_LAYERS.take(used).joinToString("/")}" +
                (if (skill != null) "; skill guide page $skill selected" else "") +
                "; 187 ${leave?.let { "[${it.first}, ${it.second}]" } ?: "unsent"}" +
                (TAB_SUBPANEL[tab.structId]?.let { "; varp 256 sub-panel $it" } ?: "")
        }
        return true
    }

    private val openPanels = java.util.Collections.synchronizedMap(
        java.util.WeakHashMap<WorldPlayer, Map<Int, Int>>()
    )

    private fun closeOpenPanels(player: WorldPlayer, chrome: Boolean = true) {
        if (chrome) {
            player.interfaces.close(TOPLEVEL, CHROME_MOUNT, native949 = true)
            hide(player, CHROME_MOUNT, true, iface = TOPLEVEL)
        }
        for ((layer, iface) in openPanels[player].orEmpty()) {
            if (iface == WARDROBE_IFACE) clearWardrobeSnapshots(player)
            hide(player, layer, false)
            player.interfaces.close(PARENT_IFACE, layer, native949 = true)
            closeSubMounts(player, iface)
        }
        player.interfaces.close(SKILLGUIDE_IFACE, 0, native949 = true)
        openPanels.remove(player)
    }

    private fun closeSubMounts(player: WorldPlayer, iface: Int) {
        val subs = subMountClosesFor(iface)
        for ((subIface, component) in subs) player.client.write(IfClosesub(InterfaceHash(subIface, component)))
    }

    fun subMountClosesFor(iface: Int): List<Pair<Int, Int>> = when (iface) {
        WARDROBE_IFACE -> WARDROBE_SUB_CLOSES
        ACHIEVEMENTS_IFACE -> listOf(ACHIEVEMENTS_IFACE to ACHIEVEMENTS_HOST)
        else -> PANEL_LOADERS[iface]?.let { listOf(iface to it.second) } ?: emptyList()
    }

    fun handlePanelButton(player: WorldPlayer, packet: IfButtonN): Boolean {
        if (!enabled) return false
        if (packet.interfaceId == WARDROBE_IFACE && packet.component == WARDROBE_GRID_COMPONENT && packet.buttonOp in WARDROBE_PANE_OPS) {
            if (answerWardrobePane(player, packet.buttonOp, packet.arg2)) return true
            noteWardrobePaneRequest(player, packet.buttonOp, packet.arg2)
            return false
        }
        if (packet.buttonOp != 1) return false
        return when (packet.interfaceId) {
            TUTORIAL_IFACE -> packet.component == TUTORIAL_CONTINUE && handleWardrobeTutorial(player)
            ACHIEVEMENTS_IFACE -> handleAchievementSideTab(player, packet.component)
            CUSTOMISATION_HOST -> petGridIndexFor(packet.component) != null && handlePetClick(player, packet.component, packet.arg2)
            SETTINGS_IFACE -> packet.component == SETTINGS_TREE_COMPONENT && handleSettingsTreeClick(player, packet.arg2)
            else -> false
        }
    }

    const val TUTORIAL_IFACE = 1846
    const val TUTORIAL_MOUNT = 318
    const val TUTORIAL_CONTINUE = 33
    const val VARP_MTX_TUTORIAL = 6807
    const val TUTORIAL_SEEN_BIT = 1 shl 5

    fun tutorialSeen(v6807: Int): Boolean = (v6807 and TUTORIAL_SEEN_BIT) != 0

    fun tutorialDismissedValue(v6807: Int): Int = v6807 or TUTORIAL_SEEN_BIT

    private fun handleWardrobeTutorial(player: WorldPlayer): Boolean {
        if (!player.interfaces.isOpened(TUTORIAL_IFACE)) {
            logger.info { "parentWindows: ${player.name} clicked $TUTORIAL_IFACE:$TUTORIAL_CONTINUE with the wardrobe tutorial not open - ignored" }
            return false
        }
        val value = tutorialDismissedValue(player.varpValue(VARP_MTX_TUTORIAL))
        player.setVarpOverride(VARP_MTX_TUTORIAL, value)
        player.interfaces.close(WARDROBE_IFACE, TUTORIAL_MOUNT, native949 = true)
        logger.info { "parentWindows: ${player.name} dismissed the wardrobe tutorial - varp $VARP_MTX_TUTORIAL = $value, closed $WARDROBE_IFACE:$TUTORIAL_MOUNT" }
        return true
    }

    const val ACHIEVEMENTS_IFACE = 1850
    const val ACHIEVEMENTS_HOST = 29
    const val VARP_ACHIEVEMENTS_TAB = 7121
    const val ACHIEVEMENTS_TAB_SHIFT = 29
    const val SCRIPT_ACHIEVEMENT_HIGHLIGHT = 13316
    const val TRACKER_IFACE = 1855
    const val PATHS_IFACE = 1895

    data class AchievementSideTab(
        val component: Int,
        val value: Int,
        val highlightComponent: Int,
        val child: Int,
        val unhideBefore: Int? = null,
        val unhideAfter: Int? = null,
        val events: List<IntArray> = emptyList()
    )

    val ACHIEVEMENT_SIDE_TABS: List<AchievementSideTab> = listOf(
        AchievementSideTab(12, 1, 13, 1852, unhideAfter = 7),
        AchievementSideTab(17, 2, 18, 1851, events = listOf(intArrayOf(11, 0, 9, 2), intArrayOf(21, 0, 65, 2), intArrayOf(40, 0, 3201, 62), intArrayOf(43, 0, 307, 2))),
        AchievementSideTab(24, 3, 25, 1857, unhideBefore = 5),
        AchievementSideTab(22, 4, 30, 755, events = listOf(intArrayOf(85, 0, 0, 16777218), intArrayOf(6, 0, 0, 6))),
        AchievementSideTab(23, 5, 35, 1329)
    )

    fun achievementTabVarp(current: Int, value: Int): Int =
        (current and (7 shl ACHIEVEMENTS_TAB_SHIFT).inv()) or ((value and 7) shl ACHIEVEMENTS_TAB_SHIFT)

    fun achievementSideTabFor(v7121: Int): AchievementSideTab {
        val value = (v7121 ushr ACHIEVEMENTS_TAB_SHIFT) and 7
        return ACHIEVEMENT_SIDE_TABS.firstOrNull { it.value == value } ?: ACHIEVEMENT_SIDE_TABS.first()
    }

    private fun handleAchievementSideTab(player: WorldPlayer, component: Int): Boolean {
        val tab = ACHIEVEMENT_SIDE_TABS.firstOrNull { it.component == component } ?: return false
        if (!player.interfaces.isOpened(ACHIEVEMENTS_IFACE)) {
            logger.info { "parentWindows: ${player.name} clicked $ACHIEVEMENTS_IFACE:$component with Achievements not open - ignored" }
            return false
        }
        val value = achievementTabVarp(player.varpValue(VARP_ACHIEVEMENTS_TAB), tab.value)
        player.setVarpOverride(VARP_ACHIEVEMENTS_TAB, value)
        sendAchievementSideTab(player, tab, onOpen = false)
        logger.info { "parentWindows: ${player.name} Achievements side tab $ACHIEVEMENTS_IFACE:$component -> varp $VARP_ACHIEVEMENTS_TAB = $value, ${tab.child} into $ACHIEVEMENTS_IFACE:$ACHIEVEMENTS_HOST" }
        return true
    }

    private fun sendAchievementSideTab(player: WorldPlayer, tab: AchievementSideTab, onOpen: Boolean) {
        if (onOpen) player.interfaces.events(id = TRACKER_IFACE, component = 5, from = 0, to = 4, mask = 66, native949 = true)
        hide(player, 5, true)
        hide(player, 7, true)
        player.interfaces.close(ACHIEVEMENTS_IFACE, ACHIEVEMENTS_HOST, native949 = true)
        player.interfaces.events(id = PATHS_IFACE, component = 18, from = 0, to = 2, mask = 2, native949 = true)
        player.client.write(RunClientScript(SCRIPT_ACHIEVEMENT_HIGHLIGHT, arrayOf<Any>(InterfaceHash(ACHIEVEMENTS_IFACE, tab.highlightComponent).hash)))
        tab.unhideBefore?.let { hide(player, it, false) }
        player.interfaces.open(id = tab.child, parent = ACHIEVEMENTS_IFACE, component = ACHIEVEMENTS_HOST, walkable = true, native949 = true)
        for (e in tab.events) player.interfaces.events(id = tab.child, component = e[0], from = e[1], to = e[2], mask = e[3], native949 = true)
        tab.unhideAfter?.let { hide(player, it, false) }
    }

    const val PET_GRID_COMPONENT = 195
    const val PET_GRID_STRIDE = 3
    const val PET_LIST_ENUM = 7229
    const val PET_NPC_PARAM = 5051
    const val PET_NAME_PARAM = 2533
    const val PET_SCALE_A_PARAM = 2540
    const val PET_SCALE_B_PARAM = 2541
    const val PET_STAGE_LABEL = "Baby"
    const val SCRIPT_PET_LABELS = 7424
    const val SCRIPT_PET_MODEL = SCRIPT_PETS_RESET_A
    const val VARP_PREVIEW_FLAG = 265
    const val PREVIEW_FLAG_ON = 64
    const val VARP_PREVIEW_ID = 5060
    const val GRID_195_CATEGORY = 22

    data class PetPreview(val structId: Int, val npcId: Int, val animId: Int, val name: String, val scaleA: Int, val scaleB: Int) {
        fun modelArgs(): Array<Any> = arrayOf(structId, npcId, animId, 0, scaleA, scaleB, 0, -1, 0, 0)
    }

    fun petForSlot(slot: Int): Int? {
        if (slot < 0) return null
        val key = slot / PET_GRID_STRIDE
        return RsDatabase.queryAll("SELECT value FROM enum_entry WHERE enum_id = $PET_LIST_ENUM AND key = '$key'") { it.getString(1) }
            .firstOrNull()?.trim()?.toIntOrNull()
    }

    private val petNpcByStruct: Map<Int, Int> by lazy {
        val out = HashMap<Int, Int>()
        val re = Regex("\"prop\":$PET_NPC_PARAM,\"intvalue\":(-?\\d+)")
        RsDatabase.queryAll("SELECT id, value FROM npcs_attr WHERE field = 'extra' AND value LIKE '%\"prop\":$PET_NPC_PARAM,%'") { it.getInt(1) to it.getString(2) }
            .forEach { (npc, json) ->
                re.find(json ?: "")?.groupValues?.get(1)?.toIntOrNull()?.let { s -> out.merge(s, npc) { a, b -> minOf(a, b) } }
            }
        out
    }

    private val petPreviews = java.util.concurrent.ConcurrentHashMap<Int, java.util.Optional<PetPreview>>()

    fun petPreview(structId: Int): PetPreview? =
        petPreviews.computeIfAbsent(structId) { java.util.Optional.ofNullable(loadPetPreview(it)) }.orElse(null)

    private fun loadPetPreview(structId: Int): PetPreview? {
        val npc = petNpcByStruct[structId] ?: return null
        val group = RsDatabase.queryAll("SELECT animation_group FROM npcs WHERE id = $npc") { rs ->
            rs.getInt(1).let { v -> if (rs.wasNull()) null else v }
        }.firstOrNull() ?: return null
        val anim = petAnimation(group) ?: return null
        var name: String? = null
        var scaleA = PET_SCALE_DEFAULT
        var scaleB = PET_SCALE_DEFAULT
        RsDatabase.queryAll("SELECT prop, intvalue, stringvalue FROM struct_param WHERE struct_id = $structId") { rs ->
            Triple(rs.getInt(1), rs.getInt(2).let { v -> if (rs.wasNull()) null else v }, rs.getString(3))
        }.forEach { (prop, int, string) ->
            when (prop) {
                PET_NAME_PARAM -> name = string
                PET_SCALE_A_PARAM -> scaleA = int ?: PET_SCALE_DEFAULT
                PET_SCALE_B_PARAM -> scaleB = int ?: PET_SCALE_DEFAULT
            }
        }
        return PetPreview(structId, npc, anim, name ?: return null, scaleA, scaleB)
    }

    private fun petAnimation(group: Int): Int? {
        val attrs = RsDatabase.queryAll("SELECT field, value FROM animgroups_attr WHERE id = $group") { it.getString(1) to it.getString(2) }.toMap()
        attrs["idleVariations"]?.let { v -> Regex("\"animid\":(-?\\d+)").find(v)?.groupValues?.get(1)?.toIntOrNull()?.let { return it } }
        return attrs["baseAnims"]?.let { v -> Regex("\"idle\":(-?\\d+)").find(v)?.groupValues?.get(1)?.toIntOrNull() }?.takeIf { it != -1 }
    }

    const val PET_SCALE_DEFAULT = 0

    fun petSelectVarpWrites(before256: Int, entry: Int, grid: Int = GRID_195_CATEGORY): IntArray {
        fun field(v: Int, shift: Int, mask: Int, value: Int) = (v and (mask shl shift).inv()) or ((value and mask) shl shift)
        val out = ArrayList<Int>(4)
        var v = before256
        if (((v ushr 5) and 0x3FF) != 0x3FF || ((v ushr 15) and 0x7F) != 0x7F) {
            v = field(v, 15, 0x7F, 0x7F); out += v
            v = field(v, 5, 0x3FF, 0x3FF); out += v
        }
        v = field(v, 5, 0x3FF, entry); out += v
        v = field(v, 15, 0x7F, grid); out += v
        return out.toIntArray()
    }

    const val DRESSING_GRID_ENUM = 5961
    const val PET_FILTER_ENUM = 7231
    const val PET_CATEGORY_PARAM = 2532
    const val GRID_ALL = GRID_195_CATEGORY

    val dressingGridIndex: Map<Int, Int> by lazy {
        if (!RsDatabase.available) emptyMap() else
            com.opennxt.resources.sqlite.CacheEnums.intMap(DRESSING_GRID_ENUM).entries
                .filter { (it.value ushr 16) == CUSTOMISATION_HOST }
                .associate { (it.value and 0xffff) to it.key }
    }

    val petGridIndices: Set<Int> by lazy {
        if (!RsDatabase.available) emptySet() else
            RsDatabase.queryAll("SELECT value FROM enums_attr WHERE id = $PET_FILTER_ENUM AND field = 'stringArrayValue1'") { it.getString(1) ?: "" }
                .flatMap { json -> Regex("\\[(-?\\d+),\"").findAll(json).map { m -> m.groupValues[1].toInt() }.toList() }.toSet()
    }

    fun petGridIndexFor(component: Int): Int? = dressingGridIndex[component]?.takeIf { it in petGridIndices }

    private val petCategories = java.util.concurrent.ConcurrentHashMap<Int, java.util.Optional<Int>>()

    fun petCategory(structId: Int): Int? = petCategories.computeIfAbsent(structId) { id ->
        java.util.Optional.ofNullable(
            RsDatabase.queryAll("SELECT intvalue FROM struct_param WHERE struct_id = $id AND prop = $PET_CATEGORY_PARAM") { rs ->
                rs.getInt(1).let { v -> if (rs.wasNull()) null else v }
            }.firstOrNull()
        )
    }.orElse(null)

    fun petForGridClick(component: Int, slot: Int): Int? {
        val grid = petGridIndexFor(component) ?: return null
        val struct = petForSlot(slot) ?: return null
        if (grid != GRID_ALL && petCategory(struct) != grid) return null
        return struct
    }

    private fun handlePetClick(player: WorldPlayer, component: Int, slot: Int): Boolean {
        if (currentCategory[player] != CATEGORY_CUSTOMISATIONS || !player.interfaces.isOpened(CUSTOMISATION_HOST)) return false
        if ((player.varpValue(VARP_CUSTOMISATION) and 0x1F) != TAB_SUBPANEL[TAB_PETS]) return false
        val grid = petGridIndexFor(component) ?: return false
        val entry = slot / PET_GRID_STRIDE
        val struct = petForGridClick(component, slot)
        val preview = struct?.let { petPreview(it) }
        if (slot < 0 || entry > 0x3FF || struct == null || preview == null) {
            val raw = petForSlot(slot)
            logger.info { "parentWindows: ${player.name} clicked pet grid 1311:$component slot $slot (entry $entry, pet $raw); no preview available" }
            return false
        }
        val writes = petSelectVarpWrites(player.varpValue(VARP_CUSTOMISATION), entry, grid)
        if (writes.size == 4) {
            player.setVarpOverride(VARP_CUSTOMISATION, writes[0])
            player.setVarpOverride(VARP_CUSTOMISATION, writes[1])
            player.setVarpOverride(VARP_PREVIEW_FLAG, 0, store = false)
        }
        player.setVarpOverride(VARP_PREVIEW_FLAG, PREVIEW_FLAG_ON, store = false)
        player.setVarpOverride(VARP_CUSTOMISATION, writes[writes.size - 2])
        player.setVarpOverride(VARP_CUSTOMISATION, writes[writes.size - 1])
        player.setVarpOverride(VARP_PREVIEW_ID, struct, store = false)
        player.client.write(RunClientScript(SCRIPT_PET_LABELS, arrayOf<Any>(PET_STAGE_LABEL, preview.name)))
        player.client.write(RunClientScript(SCRIPT_PET_MODEL, preview.modelArgs()))
        logger.info { "parentWindows: ${player.name} previewed pet '${preview.name}' (struct $struct, npc ${preview.npcId}, anim ${preview.animId}) from 1311:195 slot $slot" }
        return true
    }

    const val SETTINGS_IFACE = 365
    const val SETTINGS_TREE_COMPONENT = 13
    const val VARP_SETTINGS_TREE = 8172
    val SETTINGS_PENDING_VARPS: List<Int> = listOf(8173, 8174)
    const val SETTINGS_SECTION_COUNT = 12
    val SETTINGS_SECTION_PAGE: Map<Int, Int> = mapOf(0 to 25, 2 to 13, 3 to 29, 7 to 70)

    fun settingsTreeWrites(current: Int, slot: Int): IntArray? {
        if (slot < 0 || slot > 0x3FF) return null
        if (slot >= SETTINGS_SECTION_COUNT) return intArrayOf(current, (current and 0x3FF.inv()) or slot)
        val page = SETTINGS_SECTION_PAGE[slot] ?: return null
        val w1 = (current and (0x3FF shl 10).inv()) or (slot shl 10)
        return intArrayOf(w1, (w1 and 0x3FF.inv()) or page)
    }

    private fun handleSettingsTreeClick(player: WorldPlayer, slot: Int): Boolean {
        if (!player.interfaces.isOpened(SETTINGS_IFACE)) return false
        val writes = settingsTreeWrites(player.varpValue(VARP_SETTINGS_TREE), slot) ?: run {
            logger.info { "parentWindows: ${player.name} clicked Settings section header $slot, whose default page is unknown - nothing sent" }
            return false
        }
        for (w in writes) player.setVarpOverride(VARP_SETTINGS_TREE, w)
        for (v in SETTINGS_PENDING_VARPS) player.client.write(com.opennxt.net.game.serverprot.variables.VarpSmall(v, -1))
        logger.info { "parentWindows: ${player.name} Settings tree slot $slot -> varp $VARP_SETTINGS_TREE = ${writes[1]} (section ${(writes[1] ushr 10) and 0x3FF}, page ${writes[1] and 0x3FF})" }
        return true
    }

    val PARENT_INDEX: Map<Int, Int> = mapOf(21142 to 0, 32482 to 1, 21153 to 2, 21159 to 3, 21186 to 7, 21178 to 9)

    private fun parentIndexOf(categoryStructId: Int): Int? =
        PARENT_INDEX[categoryStructId] ?: SLOT_TO_PARENT.entries.firstOrNull { it.value == categoryStructId }?.key

    private fun hide(player: WorldPlayer, component: Int, hidden: Boolean, iface: Int = PARENT_IFACE) {
        player.client.write(IfSethide(InterfaceHash(iface, component), hidden))
    }
}

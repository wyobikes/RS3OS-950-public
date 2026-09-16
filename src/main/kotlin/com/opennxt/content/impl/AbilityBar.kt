package com.opennxt.content.impl

import com.opennxt.model.world.WorldPlayer
import com.opennxt.net.game.clientprot.generated.IfButtond
import com.opennxt.net.game.serverprot.RunClientScript
import com.opennxt.resources.sqlite.RsDatabase
import mu.KotlinLogging

object AbilityBar {
    private val logger = KotlinLogging.logger { }

    const val BAR_IFACE = 1430
    const val WINDOW_BAR_IFACE = 1436
    const val PANEL_ACTION_BAR = 1003
    const val SCRIPT_REFRESH_SLOT = 7681
    const val SLOTS = 14
    const val SLOT_STRIDE = 13
    const val BAR_SLOT_BASE = 66
    const val WINDOW_SLOT_BASE = 20
    const val SLOT_PAIR_OFFSET = 3
    const val VARP_SLOT_BASE = 738
    const val VARP_AUX_BASE = 822
    const val AUX_CLEARED = -1

    const val VALUE_SHIFT = 4
    const val VALUE_BITS = 13
    const val TYPE_SHIFT = 17
    const val TYPE_BITS = 7
    const val PARAM_BOOK = 2806
    const val PARAM_INDEX = 2793
    const val PARAM_NAME = 2794

    val BOOK_TYPES: Map<Int, List<Int>> = mapOf(
        1450 to listOf(1), 1460 to listOf(1),
        1456 to listOf(5),
        1459 to listOf(6), 1461 to listOf(6),
        1207 to listOf(17),
        1880 to listOf(3, 4),
        1457 to listOf(7),
    )

    val BAR_TYPE_ENUMS: Map<Int, Int> = mapOf(1 to 10147, 3 to 6736, 4 to 6737, 5 to 6738, 6 to 6740, 7 to 6739, 17 to 16973)

    const val BAR_TYPE_TOGGLE_RUN = 8
    const val TOGGLE_RUN_STRUCT = 14722

    val BAR_TYPE_STYLE: Map<Int, Int> = mapOf(1 to 1, 3 to 5, 4 to 6, 5 to 3, 6 to 4, 7 to 7, 17 to 29)

    val enabled: Boolean get() = System.getProperty("opennxt.content.abilitybar") != "off"

    data class Ability(val structId: Int, val name: String, val type: Int, val index: Int)

    fun slotOf(iface: Int, component: Int): Int? {
        val base = when (iface) { BAR_IFACE -> BAR_SLOT_BASE; WINDOW_BAR_IFACE -> WINDOW_SLOT_BASE; else -> return null }
        val rel = component - base
        if (rel < 0) return null
        val off = rel % SLOT_STRIDE
        if (off != 0 && off != SLOT_PAIR_OFFSET) return null
        val slot = rel / SLOT_STRIDE + 1
        return if (slot in 1..SLOTS) slot else null
    }

    val EXTRA_BAR_SLOT_BASE: Map<Int, Int> = mapOf(1670 to 21, 1671 to 19, 1672 to 16, 1673 to 16)

    fun extraBarSlotOf(iface: Int, component: Int): Int? {
        val base = EXTRA_BAR_SLOT_BASE[iface] ?: return null
        val rel = component - base
        if (rel < 0) return null
        val off = rel % SLOT_STRIDE
        if (off != 0 && off != SLOT_PAIR_OFFSET) return null
        val slot = rel / SLOT_STRIDE + 1
        return if (slot in 1..SLOTS) slot else null
    }

    fun componentOf(slot: Int, iface: Int = BAR_IFACE): Int {
        require(slot in 1..SLOTS) { "slot $slot" }
        val base = when (iface) { BAR_IFACE -> BAR_SLOT_BASE; WINDOW_BAR_IFACE -> WINDOW_SLOT_BASE; else -> throw IllegalArgumentException("iface $iface") }
        return base + (slot - 1) * SLOT_STRIDE
    }

    fun slotVarp(slot: Int): Int = VARP_SLOT_BASE + slot
    fun auxVarp(slot: Int): Int = VARP_AUX_BASE + slot

    private const val VALUE_MASK = (1 shl VALUE_BITS) - 1
    private const val TYPE_MASK = (1 shl TYPE_BITS) - 1

    fun pack(type: Int, index: Int): Int {
        require(type in 0..TYPE_MASK) { "type $type" }
        require(index in 0..VALUE_MASK) { "index $index" }
        return (type shl TYPE_SHIFT) or (index shl VALUE_SHIFT)
    }

    fun packInto(current: Int, type: Int, index: Int): Int {
        val keep = current and ((VALUE_MASK shl VALUE_SHIFT) or (TYPE_MASK shl TYPE_SHIFT)).inv()
        return keep or pack(type, index)
    }

    fun unpackType(value: Int): Int = (value ushr TYPE_SHIFT) and TYPE_MASK
    fun unpackIndex(value: Int): Int = (value ushr VALUE_SHIFT) and VALUE_MASK
    fun isEmpty(value: Int): Boolean = unpackType(value) == 0 && unpackIndex(value) == 0

    fun dragIndex(sourceSlot: Int): Int = sourceSlot and 0xffff
    fun dragLow(sourceSlot: Int): Int = 0

    val abilities: Map<Pair<Int, Int>, List<Ability>> by lazy {
        if (!RsDatabase.available) emptyMap()
        else RsDatabase.queryAll(
            "SELECT b.struct_id AS sid, b.intvalue AS book, i.intvalue AS idx, n.stringvalue AS name FROM struct_param b " +
                "JOIN struct_param i ON i.struct_id = b.struct_id AND i.prop = $PARAM_INDEX " +
                "LEFT JOIN struct_param n ON n.struct_id = b.struct_id AND n.prop = $PARAM_NAME " +
                "WHERE b.prop = $PARAM_BOOK ORDER BY b.struct_id"
        ) { Ability(it.getInt("sid"), it.getString("name") ?: "?", it.getInt("book"), it.getInt("idx")) }
            .groupBy { it.type to it.index }
    }

    val byStruct: Map<Int, Ability> by lazy { abilities.values.flatten().associateBy { it.structId } }

    val BOOK_ENUMS: Map<Int, Int> = mapOf(6 to 6740)

    private val enumTables: Map<Int, Map<Int, Int>> by lazy {
        if (!RsDatabase.available) emptyMap()
        else BOOK_ENUMS.values.distinct().associateWith { e ->
            RsDatabase.queryAll("SELECT key, value FROM enum_entry WHERE enum_id = $e") { it.getString("key").toInt() to it.getString("value").toInt() }.toMap()
        }
    }

    fun tieBreak(candidates: List<Ability>): Ability? {
        if (candidates.isEmpty()) return null
        if (candidates.size == 1) return candidates[0]
        val unlockable = unlockableStructs
        val base = candidates.filter { it.structId !in unlockable && !it.name.startsWith("Greater ") }
        return (base.ifEmpty { candidates }).minByOrNull { it.structId }
    }

    private val unlockableStructs: Set<Int> by lazy {
        if (!RsDatabase.available) emptySet()
        else RsDatabase.queryAll("SELECT struct_id FROM struct_param WHERE prop = 4650") { it.getInt(1) }.toSet()
    }

    val BOOK_ENUM_TYPES: Map<Int, Int> = mapOf(10147 to 1, 6738 to 5, 6740 to 6, 16973 to 17, 6736 to 3, 6737 to 4)

    val SEED_PATH: java.nio.file.Path = (System.getProperty("opennxt.seed.dir")?.let { java.nio.file.Paths.get(it) }
        ?: com.opennxt.Constants.DATA_PATH.resolve("seed")).resolve("abilities_950.tsv")

    val seedSlots: Map<Pair<Int, Int>, Int> by lazy { loadSeed().first }
    val seedNames: Map<Int, String> by lazy { loadSeed().second }

    private val seedLoaded: Pair<Map<Pair<Int, Int>, Int>, Map<Int, String>> by lazy {
        if (!java.nio.file.Files.isRegularFile(SEED_PATH)) {
            logger.warn { "abilityBar: $SEED_PATH is missing; using cache enums for ability slots" }
            return@lazy emptyMap<Pair<Int, Int>, Int>() to emptyMap<Int, String>()
        }
        val lines = java.nio.file.Files.readAllLines(SEED_PATH).filter { it.isNotEmpty() && !it.startsWith("#") }
        val cols = lines.firstOrNull()?.split('\t') ?: return@lazy emptyMap<Pair<Int, Int>, Int>() to emptyMap<Int, String>()
        val iSid = cols.indexOf("struct_id"); val iName = cols.indexOf("name"); val iEnum = cols.indexOf("book_enum"); val iSlot = cols.indexOf("slot")
        if (listOf(iSid, iName, iEnum, iSlot).any { it < 0 }) {
            logger.warn { "abilityBar: $SEED_PATH lacks struct_id/name/book_enum/slot columns; ignored" }
            return@lazy emptyMap<Pair<Int, Int>, Int>() to emptyMap<Int, String>()
        }
        val slots = LinkedHashMap<Pair<Int, Int>, Int>(); val names = LinkedHashMap<Int, String>()
        for (l in lines.drop(1)) {
            val f = l.split('\t')
            val sid = f.getOrNull(iSid)?.toIntOrNull() ?: continue
            val type = BOOK_ENUM_TYPES[f.getOrNull(iEnum)?.toIntOrNull() ?: continue] ?: continue
            val slot = f.getOrNull(iSlot)?.toIntOrNull() ?: continue
            slots.putIfAbsent(type to slot, sid)
            names[sid] = f.getOrNull(iName) ?: "?"
        }
        logger.info { "abilityBar: ${slots.size} (book, slot) rows from $SEED_PATH" }
        slots to names
    }
    private fun loadSeed() = seedLoaded

    fun abilityFor(type: Int, index: Int): Ability? {
        seedSlots[type to index]?.let { sid -> return (byStruct[sid] ?: Ability(sid, seedNames[sid] ?: "?", type, index)).copy(type = type, index = index) }
        val e = BOOK_ENUMS[type]
        if (e != null) {
            val sid = enumTables[e]?.get(index) ?: return null
            return (byStruct[sid] ?: Ability(sid, "?", type, index)).copy(type = type, index = index)
        }
        val style = BAR_TYPE_STYLE[type] ?: return null
        return tieBreak(abilities[style to index].orEmpty())?.copy(type = type, index = index)
    }

    sealed class Resolution {
        data class Found(val ability: Ability) : Resolution()
        data class NotABook(val iface: Int) : Resolution()
        data class Unknown(val iface: Int, val types: List<Int>, val index: Int) : Resolution()
        data class Ambiguous(val iface: Int, val candidates: List<Ability>) : Resolution()
    }

    fun resolveDrag(sourceIface: Int, sourceSlot: Int): Resolution {
        val types = BOOK_TYPES[sourceIface] ?: return Resolution.NotABook(sourceIface)
        val index = dragIndex(sourceSlot)
        val found = types.mapNotNull { abilityFor(it, index) }
        return when (found.size) {
            0 -> Resolution.Unknown(sourceIface, types, index)
            1 -> Resolution.Found(found[0])
            else -> Resolution.Ambiguous(sourceIface, found)
        }
    }

    fun swapPlan(src: Int, dst: Int, valueOf: (Int) -> Int): List<Pair<Int, Int>> {
        val srcValue = valueOf(slotVarp(src))
        val dstValue = valueOf(slotVarp(dst))
        return listOf(auxVarp(src) to AUX_CLEARED, slotVarp(src) to dstValue, auxVarp(dst) to AUX_CLEARED, slotVarp(dst) to srcValue)
    }

    fun placePlan(slot: Int, ability: Ability, valueOf: (Int) -> Int): List<Pair<Int, Int>> =
        listOf(auxVarp(slot) to AUX_CLEARED, slotVarp(slot) to packInto(valueOf(slotVarp(slot)), ability.type, ability.index))

    fun refreshScripts(vararg slots: Int): List<RunClientScript> =
        slots.map { RunClientScript(SCRIPT_REFRESH_SLOT, arrayOf<Any>(PANEL_ACTION_BAR, it)) }

    fun handleDrag(player: WorldPlayer, packet: IfButtond): Boolean {
        if (!enabled) return false
        val srcIf = (packet.sourcehash ushr 16) and 0xffff
        val srcC = packet.sourcehash and 0xffff
        val tgtIf = (packet.targethash ushr 16) and 0xffff
        val tgtC = packet.targethash and 0xffff
        val extraDst = extraBarSlotOf(tgtIf, tgtC)
        val extraSrc = extraBarSlotOf(srcIf, srcC)
        if (extraDst != null || extraSrc != null) {
            logger.warn {
                "abilityBar: ${player.name} dragged $srcIf:$srcC[${packet.sourceslot}] -> $tgtIf:$tgtC" +
                    (extraSrc?.let { " (off additional bar $srcIf, slot $it)" } ?: "") +
                    (extraDst?.let { " (onto additional bar $tgtIf, slot $it)" } ?: "") +
                    "; additional bars are not supported"
            }
            return true
        }
        val dst = slotOf(tgtIf, tgtC) ?: return false
        val who = player.name
        if (!player.interfaces.isOpened(tgtIf)) {
            logger.info { "abilityBar: $who dropped on $tgtIf:$tgtC (slot $dst) with $tgtIf not open - ignored" }
            return true
        }
        if (ActionBarLock.isLocked(player)) {
            logger.info { "abilityBar: $who dropped on slot $dst while the bar is locked; ignored" }
            return true
        }
        val src = slotOf(srcIf, srcC)
        if (src != null) {
            if (!player.interfaces.isOpened(srcIf)) {
                logger.info { "abilityBar: $who dragged from $srcIf:$srcC with $srcIf not open - ignored" }
                return true
            }
            if (src == dst && srcIf == tgtIf) {
                logger.info { "abilityBar: $who dropped slot $src on itself - nothing to do" }
                return true
            }
            val plan = swapPlan(src, dst) { player.varpValue(it) }
            for ((id, v) in plan) player.setVarpOverride(id, v)
            for (s in refreshScripts(src, dst)) player.client.write(s)
            val armed = rearm(player)
            logger.info { "abilityBar: $who swapped slot $src <-> $dst: " + plan.joinToString { "${it.first}=${it.second}" } + "; $armed rows re-armed" }
            return true
        }
        when (val r = resolveDrag(srcIf, packet.sourceslot)) {
            is Resolution.NotABook -> {
                logger.info { "abilityBar: $who dropped $srcIf:$srcC[${packet.sourceslot}] on slot $dst; $srcIf is not a known ability book" }
            }
            is Resolution.Unknown -> {
                logger.warn { "abilityBar: $who dragged index ${r.index} from book $srcIf onto slot $dst; no ability for bar type(s) ${r.types}" }
            }
            is Resolution.Ambiguous -> {
                logger.warn { "abilityBar: $who dragged index ${dragIndex(packet.sourceslot)} from book $srcIf onto slot $dst; ambiguous (${r.candidates.joinToString { "${it.structId} ${it.name}" }})" }
            }
            is Resolution.Found -> {
                if (!player.interfaces.isOpened(srcIf)) {
                    logger.info { "abilityBar: $who dragged from book $srcIf with it not open - ignored" }
                    return true
                }
                val a = r.ability
                val plan = placePlan(dst, a) { player.varpValue(it) }
                for ((id, v) in plan) player.setVarpOverride(id, v)
                for (s in refreshScripts(dst)) player.client.write(s)
                val armed = rearm(player)
                logger.info { "abilityBar: $who placed ${a.name} (struct ${a.structId}) on slot $dst of $tgtIf: " + plan.joinToString { "${it.first}=${it.second}" } + "; $armed rows re-armed" }
            }
        }
        return true
    }

    const val MASK_UNLOCKED_FILLED = 11239422
    const val MASK_UNLOCKED_EMPTY = 2098176
    const val MASK_LOCKED_FILLED = 2326526
    const val MASK_LOCKED_EMPTY = 2098176

    fun slotMask(locked: Boolean, filled: Boolean): Int = when {
        locked && filled -> MASK_LOCKED_FILLED
        locked -> MASK_LOCKED_EMPTY
        filled -> MASK_UNLOCKED_FILLED
        else -> MASK_UNLOCKED_EMPTY
    }

    fun slotRows(iface: Int, locked: Boolean, valueOf: (Int) -> Int): List<Pair<Int, Int>> =
        (1..SLOTS).flatMap { slot ->
            val mask = slotMask(locked, !isEmpty(valueOf(slotVarp(slot))))
            val c = componentOf(slot, iface)
            listOf(c to mask, c + SLOT_PAIR_OFFSET to mask)
        }

    fun armSlots(player: WorldPlayer, locked: Boolean = ActionBarLock.isLocked(player)): Int {
        var armed = 0
        for (iface in listOf(BAR_IFACE, WINDOW_BAR_IFACE)) {
            if (!player.interfaces.isOpened(iface)) continue
            for ((c, mask) in slotRows(iface, locked) { player.varpValue(it) }) {
                player.interfaces.events(id = iface, component = c, from = -1, to = -1, mask = mask, native949 = true)
                armed++
            }
        }
        return armed
    }

    fun isSlotRow(iface: Int, component: Int): Boolean = slotOf(iface, component) != null

    private fun rearm(player: WorldPlayer): Int {
        var armed = 0
        for (e in ActionBarLock.unlockedEvents) {
            if (!player.interfaces.isOpened(e[0]) || isSlotRow(e[0], e[1])) continue
            player.interfaces.events(id = e[0], component = e[1], from = e[2], to = e[3], mask = e[4], native949 = true)
            armed++
        }
        return armed + armSlots(player, locked = false)
    }
}

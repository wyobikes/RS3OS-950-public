package com.opennxt.content.impl

import com.opennxt.Constants
import com.opennxt.api.stat.Stat
import com.opennxt.content.ability.AbilityDefinitions.Style
import com.opennxt.model.combat.CombatStyle
import com.opennxt.model.world.WorldNpc
import com.opennxt.model.world.WorldPlayer
import com.opennxt.net.game.GamePacket
import com.opennxt.net.game.clientprot.IfButtonN
import com.opennxt.net.game.serverprot.MessageGame
import com.opennxt.net.game.serverprot.variables.VarpLarge
import mu.KotlinLogging
import java.nio.file.Files
import java.nio.file.Path
import java.util.IdentityHashMap

object PrayerBook {
    private val logger = KotlinLogging.logger { }

    const val FLAG = "opennxt.content.prayers"
    val enabled: Boolean get() = System.getProperty(FLAG) != "off"

    const val IFACE = 1458
    const val LIST_COMPONENT = 40
    const val LIST_FROM = 0
    const val LIST_TO = 45
    const val ARM_MASK_LOCKED = 2
    const val ARM_MASK_UNLOCKED = 8388610

    const val VARP_POINTS = 3274
    const val POINTS_MASK = 0x7fff
    const val LEVEL_SHIFT = 16
    const val LEVEL_MASK = 0x7f
    const val VARP_BOOK = 12219

    const val BAR_TYPE = 7

    const val MSG_NO_POINTS = "You need to recharge your Prayer at an altar."
    const val MSG_RAN_OUT = "You have run out of Prayer points; you can recharge at an altar."

    val SEED_FILE: Path = Constants.DATA_PATH.resolve("seed").resolve("prayers_950.tsv")

    enum class Book { PRAYERS, CURSES }

    data class Prayer(
        val book: Book, val slot: Int, val tier: Int, val struct: Int, val name: String, val level: Int,
        val varbit: Int, val varp: Int, val bit: Int, val drainPerMinute: Int, val barIndex: Int,
        val statReq: List<Pair<Int, Int>>, val effect: String,
    ) {
        val drainTenthsPerTick: Int get() = if (drainPerMinute <= 0) 0 else drainPerMinute / 10
        val damagePercent: Map<Style, Int> by lazy { parseBoosts(effect).first }
        val accuracyLevels: Map<Style, Int> by lazy { parseBoosts(effect).second }
        val defenceLevels: Int by lazy { parseBoosts(effect).third }
        val protects: CombatStyle? by lazy { protectedStyle(name) }
        val groups: Set<String> by lazy { groupsOf(this) }
    }

    val rows: List<Prayer> by lazy { load(SEED_FILE) }

    fun load(file: Path): List<Prayer> {
        if (!Files.isRegularFile(file)) {
            logger.warn { "prayers: $file not found; prayers are unavailable" }
            return emptyList()
        }
        val out = ArrayList<Prayer>()
        var header: List<String>? = null
        for (line in Files.readAllLines(file)) {
            if (line.isBlank() || line.startsWith("#")) continue
            val f = line.split('\t')
            if (header == null) { header = f; continue }
            val h: List<String> = header
            fun col(name: String): String = f.getOrElse(h.indexOf(name)) { "" }
            val req = col("stat_req").takeIf { it != "-" && it.isNotBlank() }?.split(',')?.map {
                val (s, l) = it.split(':'); s.trim().toInt() to l.trim().toInt()
            } ?: emptyList()
            out += Prayer(
                book = if (col("book") == "curses") Book.CURSES else Book.PRAYERS,
                slot = col("slot").toInt(), tier = col("tier").toInt(), struct = col("struct").toInt(), name = col("name"),
                level = col("level").toInt(), varbit = col("varbit").toInt(), varp = col("varp").toInt(), bit = col("bit").toInt(),
                drainPerMinute = col("drain").toInt(), barIndex = col("bar_index").toIntOrNull() ?: -1,
                statReq = req, effect = col("effect").takeIf { it != "-" } ?: "",
            )
        }
        return out
    }

    fun byStruct(struct: Int): Prayer? = rows.firstOrNull { it.struct == struct }

    fun slotRows(book: Book, slot: Int): List<Prayer> = rows.filter { it.book == book && it.slot == slot }.sortedBy { it.tier }

    fun resolve(book: Book, slot: Int, level: Int): Prayer? {
        val tiers = slotRows(book, slot)
        if (tiers.isEmpty()) return null
        return tiers.lastOrNull { it.level <= level } ?: tiers.first()
    }

    val ownedMasks: Map<Int, Int> by lazy {
        val m = HashMap<Int, Int>()
        for (r in rows) m[r.varp] = (m[r.varp] ?: 0) or (1 shl r.bit)
        m
    }

    private fun styleIn(segment: String): Style? {
        val s = segment.lowercase()
        return when {
            "necromancy" in s -> Style.NECROMANCY
            "ranged" in s -> Style.RANGED
            "magic" in s -> Style.MAGIC
            "melee" in s || "attack" in s || "strength" in s -> Style.MELEE
            else -> null
        }
    }

    private val PLUS = Regex("""\+(\d+)""")

    fun parseBoosts(effect: String): Triple<Map<Style, Int>, Map<Style, Int>, Int> {
        val dmg = HashMap<Style, Int>()
        val acc = HashMap<Style, Int>()
        var def = 0
        for (raw in effect.split(Regex("""<br\s*/?\s*>""", RegexOption.IGNORE_CASE))) {
            val seg = raw.lowercase()
            if ("enemy" in seg) continue
            val n = PLUS.find(seg)?.groupValues?.get(1)?.toInt() ?: continue
            when {
                "%" in seg && "damage" in seg -> styleIn(seg)?.let { dmg[it] = maxOf(dmg[it] ?: 0, n) }
                "armour" in seg && "defence" in seg -> def = maxOf(def, n)
                "accuracy" in seg || "hit chance" in seg -> styleIn(seg.replace("defence", ""))?.let { acc[it] = maxOf(acc[it] ?: 0, n) }
            }
        }
        return Triple(dmg, acc, def)
    }

    private fun protectedStyle(name: String): CombatStyle? = when (name) {
        "Protect from Melee", "Deflect Melee" -> CombatStyle.MELEE
        "Protect from Ranged", "Deflect Ranged" -> CombatStyle.RANGED
        "Protect from Magic", "Deflect Magic" -> CombatStyle.MAGIC
        else -> null
    }

    private val OVERHEADS = setOf(
        "Protect from Melee", "Protect from Ranged", "Protect from Magic", "Protect from Necromancy",
        "Deflect Melee", "Deflect Ranged", "Deflect Magic", "Deflect Necromancy",
        "Retribution", "Redemption", "Smite", "Wrath", "Soul Split",
    )

    fun groupsOf(p: Prayer): Set<String> {
        val g = LinkedHashSet<String>()
        if (p.name in OVERHEADS) g += "overhead"
        p.damagePercent.keys.forEach { g += "damage:$it" }
        p.accuracyLevels.keys.forEach { g += "accuracy:$it" }
        if (p.defenceLevels > 0) g += "defence"
        Regex("""^(?:Sap|Leech) (.+)$""").find(p.name)?.groupValues?.get(1)?.let { stat ->
            val style = styleIn(stat)
            when {
                stat == "Defence" -> g += "defence"
                stat.endsWith("Attack") && style != null -> g += "accuracy:$style"
                stat.endsWith("Strength") && style != null -> g += "damage:$style"
                else -> g += "curse:$stat"
            }
        }
        if (p.name == "Rapid Heal" || p.name == "Rapid Renewal") g += "lifepoint-regen"
        if (p.name == "Light Form" || p.name == "Dark Form") g += "form"
        return g
    }

    class State(var pointsTenths: Int) {
        val active = LinkedHashSet<Prayer>()
        var sentPoints: Int = Int.MIN_VALUE
    }

    private val states = IdentityHashMap<WorldPlayer, State>()

    @Volatile
    var observer: ((WorldPlayer, GamePacket) -> Unit)? = null

    fun prayerLevel(player: WorldPlayer): Int = player.stats.getLevel(Stat.PRAYER, boosted = false).coerceAtLeast(1)
    fun maxPointsTenths(player: WorldPlayer): Int = (prayerLevel(player) * 100).coerceAtMost(POINTS_MASK)

    fun stateFor(player: WorldPlayer): State = states.getOrPut(player) { State(maxPointsTenths(player)) }
    fun stateOrNull(player: WorldPlayer): State? = states[player]

    fun bookOf(player: WorldPlayer): Book = if (player.varpValue(VARP_BOOK) and 1 != 0) Book.CURSES else Book.PRAYERS

    fun isActive(player: WorldPlayer, name: String): Boolean = states[player]?.active?.any { it.name == name } == true
    fun activeNames(player: WorldPlayer): List<String> = states[player]?.active?.map { it.name }.orEmpty()

    private fun send(player: WorldPlayer, packet: GamePacket) {
        observer?.invoke(player, packet)
        if (player.client.channel.isActive) player.client.write(packet)
    }

    private fun message(player: WorldPlayer, text: String) = send(player, MessageGame(0, text))

    fun pointsValue(player: WorldPlayer, st: State): Int {
        val keep = player.varpValue(VARP_POINTS) and (POINTS_MASK or (LEVEL_MASK shl LEVEL_SHIFT)).inv()
        return keep or (st.pointsTenths.coerceIn(0, POINTS_MASK)) or ((prayerLevel(player) and LEVEL_MASK) shl LEVEL_SHIFT)
    }

    private fun sendPoints(player: WorldPlayer, st: State, force: Boolean = false) {
        val v = pointsValue(player, st)
        if (!force && v == st.sentPoints) return
        st.sentPoints = v
        if (com.opennxt.model.lobby.TODORefactorThisClass.varpIsDefined(VARP_POINTS)) send(player, VarpLarge(VARP_POINTS, v))
    }

    fun activeVarpValue(player: WorldPlayer, varp: Int, st: State?): Int {
        val owned = ownedMasks[varp] ?: return player.varpValue(varp)
        var bits = 0
        st?.active?.forEach { if (it.varp == varp) bits = bits or (1 shl it.bit) }
        return (player.varpValue(varp) and owned.inv()) or bits
    }

    private fun sendActive(player: WorldPlayer, st: State, varps: Collection<Int>) {
        for (v in varps.toSortedSet()) {
            if (com.opennxt.model.lobby.TODORefactorThisClass.varpIsDefined(v)) send(player, VarpLarge(v, activeVarpValue(player, v, st)))
            else logger.warn { "prayers: varp $v is not defined by this cache - not sent" }
        }
    }

    fun arm(player: WorldPlayer): Boolean {
        if (!enabled) return false
        if (!player.interfaces.isOpened(IFACE)) {
            logger.info { "prayers: ${player.name} has no $IFACE open - list not armed" }
            return false
        }
        val mask = if (ActionBarLock.isLocked(player)) ARM_MASK_LOCKED else ARM_MASK_UNLOCKED
        player.interfaces.events(id = IFACE, component = LIST_COMPONENT, from = LIST_FROM, to = LIST_TO, mask = mask, native949 = true)
        return true
    }

    fun onLogin(player: WorldPlayer) {
        if (!enabled) return
        val st = State(maxPointsTenths(player))
        states[player] = st
        sendPoints(player, st, force = true)
        sendActive(player, st, ownedMasks.keys.filter { player.varpValue(it) and (ownedMasks[it] ?: 0) != 0 })
        logger.info { "prayers: ${player.name} login - ${st.pointsTenths / 10.0} points (Prayer ${prayerLevel(player)}), book ${bookOf(player)}" }
    }

    fun handleButton(player: WorldPlayer, packet: IfButtonN): Boolean {
        if (!enabled || packet.interfaceId != IFACE || packet.component != LIST_COMPONENT) return false
        if (packet.buttonOp != 1) {
            logger.info { "prayers: ${player.name} op ${packet.buttonOp} on $IFACE:$LIST_COMPONENT[${packet.arg2}] - only op 1 toggles" }
            return true
        }
        if (!player.interfaces.isOpened(IFACE)) {
            logger.info { "prayers: ${player.name} clicked $IFACE:$LIST_COMPONENT[${packet.arg2}] with $IFACE not open - ignored" }
            return true
        }
        val book = bookOf(player)
        val prayer = resolve(book, packet.arg2, prayerLevel(player))
        if (prayer == null) {
            logger.info { "prayers: ${player.name} clicked slot ${packet.arg2} of the $book book - no such slot" }
            return true
        }
        logger.info { "prayers: ${player.name} clicked slot ${packet.arg2} ($book) = struct ${prayer.struct} ${prayer.name}" }
        toggle(player, prayer, "book slot ${packet.arg2}")
        return true
    }

    fun activateFromBar(player: WorldPlayer, type: Int, index: Int): Boolean {
        if (!enabled || type != BAR_TYPE) return false
        val row = rows.firstOrNull { it.barIndex == index && it.book == bookOf(player) } ?: rows.firstOrNull { it.barIndex == index }
        if (row == null) {
            logger.info { "prayers: ${player.name} bar slot names prayer index $index - no row" }
            return true
        }
        val resolved = resolve(row.book, row.slot, prayerLevel(player)) ?: row
        toggle(player, resolved, "action bar index $index")
        return true
    }

    enum class Result { ON, OFF, LEVEL, STAT_REQUIREMENT, NO_POINTS, WRONG_BOOK, DISABLED }

    fun toggle(player: WorldPlayer, prayer: Prayer, via: String): Result {
        if (!enabled) return Result.DISABLED
        val st = stateFor(player)
        val live = st.active.firstOrNull { it.varp == prayer.varp && it.bit == prayer.bit }
        if (live != null) {
            st.active.remove(live)
            sendActive(player, st, listOf(live.varp))
            logger.info { "prayers: ${player.name} ${live.name} OFF ($via)" }
            return Result.OFF
        }
        if (prayer.book != bookOf(player)) return Result.WRONG_BOOK
        if (prayerLevel(player) < prayer.level) {
            message(player, "You need a Prayer level of at least ${prayer.level} to use ${prayer.name}.")
            return Result.LEVEL
        }
        if (prayer.statReq.isNotEmpty() && prayer.statReq.none { (stat, lvl) ->
                val s = Stat.values().firstOrNull { it.id == stat }
                s != null && player.stats.getLevel(s, boosted = false) >= lvl
            }) {
            val (stat, lvl) = prayer.statReq.first()
            val s = Stat.values().firstOrNull { it.id == stat }
            message(player, "You need a ${s?.name?.lowercase()?.replaceFirstChar { it.uppercase() } ?: "stat"} level of at least $lvl to use ${prayer.name}.")
            return Result.STAT_REQUIREMENT
        }
        if (st.pointsTenths <= 0) {
            message(player, MSG_NO_POINTS)
            return Result.NO_POINTS
        }
        val touched = LinkedHashSet<Int>()
        val clash = st.active.filter { a -> a.groups.any { it in prayer.groups } }
        for (c in clash) { st.active.remove(c); touched += c.varp }
        st.active += prayer
        touched += prayer.varp
        sendActive(player, st, touched)
        logger.info {
            "prayers: ${player.name} ${prayer.name} ON ($via; drain ${prayer.drainPerMinute}/min)" +
                (if (clash.isEmpty()) "" else " - switched off ${clash.joinToString { it.name }}")
        }
        return Result.ON
    }

    fun deactivateAll(player: WorldPlayer, why: String): Int {
        val st = states[player] ?: return 0
        if (st.active.isEmpty()) return 0
        val n = st.active.size
        val varps = st.active.map { it.varp }.toSet()
        st.active.clear()
        sendActive(player, st, varps)
        logger.info { "prayers: ${player.name} all $n prayer(s) OFF - $why" }
        return n
    }

    fun tick(players: List<WorldPlayer>) {
        if (!enabled) return
        if (states.isNotEmpty()) {
            val live = java.util.Collections.newSetFromMap(IdentityHashMap<WorldPlayer, Boolean>())
            live.addAll(players)
            states.keys.retainAll { it in live }
        }
        for (p in players) {
            val st = states[p] ?: continue
            if (st.active.isEmpty()) continue
            val drain = st.active.sumOf { it.drainTenthsPerTick }
            st.pointsTenths = (st.pointsTenths - drain).coerceAtLeast(0)
            if (st.pointsTenths == 0) {
                message(p, MSG_RAN_OUT)
                deactivateAll(p, "out of prayer points")
            }
            sendPoints(p, st)
        }
    }

    fun onDeath(player: WorldPlayer) {
        if (!enabled) return
        deactivateAll(player, "death")
        val st = stateFor(player)
        st.pointsTenths = maxPointsTenths(player)
        sendPoints(player, st)
    }

    fun recharge(player: WorldPlayer, why: String) {
        if (!enabled) return
        val st = stateFor(player)
        st.pointsTenths = maxPointsTenths(player)
        sendPoints(player, st)
        logger.info { "prayers: ${player.name} recharged to ${st.pointsTenths / 10} points ($why)" }
    }

    fun rechargeByName(name: String, why: String): Boolean {
        val p = states.keys.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: return false
        recharge(p, why)
        return true
    }

    fun cull(player: WorldPlayer) { states.remove(player) }

    fun incomingMultiplier(player: WorldPlayer, npc: WorldNpc): Double {
        if (!enabled || states[player]?.active.isNullOrEmpty()) return 1.0
        return incomingMultiplierFor(player, npc.combat?.combatStyle)
    }

    fun incomingMultiplierFor(player: WorldPlayer, style: CombatStyle?): Double {
        if (!enabled || style == null) return 1.0
        val st = states[player] ?: return 1.0
        return if (st.active.any { it.protects == style }) PROTECT_MULTIPLIER else 1.0
    }

    const val PROTECT_MULTIPLIER = 0.5

    fun damageMultiplier(player: WorldPlayer, style: Style): Double {
        if (!enabled) return 1.0
        val st = states[player] ?: return 1.0
        val pct = st.active.maxOfOrNull { it.damagePercent[style] ?: 0 } ?: 0
        return 1.0 + pct / 100.0
    }

    fun accuracyLevelBonus(player: WorldPlayer, style: Style): Int =
        if (!enabled) 0 else states[player]?.active?.maxOfOrNull { it.accuracyLevels[style] ?: 0 } ?: 0

    fun defenceLevelBonus(player: WorldPlayer): Int =
        if (!enabled) 0 else states[player]?.active?.maxOfOrNull { it.defenceLevels } ?: 0
}

package com.opennxt.content.skills

import com.opennxt.api.stat.Stat
import com.opennxt.content.ContentPlayer
import com.opennxt.content.impl.Smithing
import com.opennxt.model.items.Item
import com.opennxt.resources.sqlite.RsDatabase
import mu.KotlinLogging
import java.util.Collections

object MagicSpells {
    private val logger = KotlinLogging.logger { }

    val enabled: Boolean get() = System.getProperty("opennxt.experiment.magic") != "false"

    const val BOOK_ENUM = 6740

    val BOOK_INTERFACES: Set<Int> = setOf(1461, 1459)
    const val BOOK_COMPONENT = 1

    const val PARAM_NAME = 2794
    const val PARAM_SKILL = 2806
    const val PARAM_LEVEL = 2807
    const val PARAM_BOOK = 2871
    const val PARAM_ANIMATION = 2914

    const val MAGIC_SKILL_KEY = 4

    val RUNE_PARAMS: Map<Int, String> = linkedMapOf(
        2898 to "Air rune", 2899 to "Earth rune", 2900 to "Water rune", 2901 to "Fire rune",
        2902 to "Mind rune", 2903 to "Body rune",
        2906 to "Blood rune", 2907 to "Soul rune", 2908 to "Astral rune", 2909 to "Nature rune",
        2910 to "Cosmic rune", 2911 to "Law rune"
    )

    val UNRESOLVED_RUNE_PARAMS: Set<Int> = setOf(2904, 2905)

    const val HIGH_ALCH_PERCENT = 60
    const val LOW_ALCH_PERCENT = 40

    const val CAST_TICKS = 3

    const val COINS = 995

    data class Spell(
        val slot: Int,
        val structId: Int,
        val name: String,
        val level: Int,
        val book: Int,
        val animation: Int?,
        val runes: Map<Int, Int>?,
        val xp: Double?
    )

    @Volatile private var cached: Map<Int, Spell>? = null

    val spells: Map<Int, Spell>
        get() = cached ?: synchronized(this) { cached ?: load().also { cached = it } }

    internal fun invalidate() = synchronized(this) { cached = null }

    fun isLoaded(): Boolean = cached != null

    private fun load(): Map<Int, Spell> {
        if (!RsDatabase.available) return emptyMap()
        val slots = RsDatabase.queryAll("SELECT key, value FROM enum_entry WHERE enum_id = $BOOK_ENUM") { rs ->
            (rs.getString(1)?.trim('"')?.toIntOrNull() ?: -1) to (rs.getString(2)?.trim('"')?.toIntOrNull() ?: -1)
        }.filter { it.first >= 0 && it.second >= 0 }
        if (slots.isEmpty()) return emptyMap()
        val structIds = slots.map { it.second }.toSet()
        val props = HashMap<Int, MutableMap<Int, Any>>()
        val wanted = (listOf(PARAM_NAME, PARAM_SKILL, PARAM_LEVEL, PARAM_BOOK, PARAM_ANIMATION) + RUNE_PARAMS.keys + UNRESOLVED_RUNE_PARAMS).joinToString(",")
        RsDatabase.queryAll("SELECT struct_id, prop, intvalue, stringvalue FROM struct_param WHERE prop IN ($wanted)") { rs ->
            val iv = rs.getInt(3)
            val value: Any? = if (rs.wasNull()) rs.getString(4) else iv
            Triple(rs.getInt(1), rs.getInt(2), value)
        }.forEach { (s, p, v) -> if (s in structIds && v != null) props.getOrPut(s) { HashMap() }[p] = v }
        val out = LinkedHashMap<Int, Spell>()
        for ((slot, struct) in slots.sortedBy { it.first }) {
            val p = props[struct] ?: continue
            if ((p[PARAM_SKILL] as? Int) != MAGIC_SKILL_KEY) continue
            val name = p[PARAM_NAME] as? String ?: continue
            var runes: MutableMap<Int, Int>? = LinkedHashMap()
            if (UNRESOLVED_RUNE_PARAMS.any { it in p }) runes = null
            else for ((param, runeName) in RUNE_PARAMS) {
                val n = p[param] as? Int ?: continue
                val id = SkillInteractions.itemIdByName(runeName)
                if (id == null) { runes = null; break }
                runes!![id] = n
            }
            out[slot] = Spell(slot, struct, name, p[PARAM_LEVEL] as? Int ?: 1, p[PARAM_BOOK] as? Int ?: 0,
                p[PARAM_ANIMATION] as? Int, runes, SkillInteractions.refRow("Magic", name)?.xp)
        }
        logger.info { "magic spells: ${out.size} spells from enum $BOOK_ENUM (${out.values.count { it.book == 0 }} standard; ${out.values.count { it.runes == null }} with unresolved runes)" }
        return out
    }

    fun valueOf(itemId: Int): Long? {
        if (!RsDatabase.available) return null
        val raw = RsDatabase.queryAll("SELECT value FROM items_attr WHERE id = $itemId AND field = 'big_value'") { it.getString(1) }.firstOrNull() ?: return null
        val parts = raw.trim('[', ']').split(',').mapNotNull { it.trim().toLongOrNull() }
        if (parts.size != 2) return null
        return (parts[0] shl 32) or (parts[1] and 0xffffffffL)
    }

    @Volatile var pouchDeposit: (ContentPlayer, Int) -> Boolean = { _, _ -> false }

    fun resetSeams() { pouchDeposit = { _, _ -> false } }

    private val nextCastAt: MutableMap<ContentPlayer, Long> = Collections.synchronizedMap(java.util.WeakHashMap())

    @Volatile private var casts = 0
    fun castCount(): Int = casts
    internal fun resetState() { nextCastAt.clear(); casts = 0 }

    enum class Outcome { OFF, UNKNOWN_SPELL, NOT_STANDARD_BOOK, LEVEL_TOO_LOW, UNSUPPORTED, NO_RUNES, BAD_TARGET, TOO_SOON, NO_SPACE, CAST }

    data class Result(val outcome: Outcome, val spell: Spell? = null, val detail: String = "")

    private fun msg(p: ContentPlayer, s: String) = ProductionActions.messageSink(p, 0, s)

    fun castOnItem(player: ContentPlayer, slot: Int, targetSlot: Int, targetItemId: Int): Result {
        if (!enabled) return Result(Outcome.OFF)
        val spell = spells[slot] ?: return Result(Outcome.UNKNOWN_SPELL, detail = "no spell in book slot $slot")
        if (spell.book != 0) return Result(Outcome.NOT_STANDARD_BOOK, spell)
        val c = ProductionActions.containerSupplier(player)
        val held = if (targetSlot in 0 until c.size) c[targetSlot] else null
        if (held == null || held.id != targetItemId) return Result(Outcome.BAD_TARGET, spell, "slot $targetSlot holds ${held?.id}")
        val now = ProductionActions.clockTick()
        if ((nextCastAt[player] ?: Long.MIN_VALUE) > now) return Result(Outcome.TOO_SOON, spell)
        nextCastAt[player] = now + 1
        if (ProductionActions.levelSupplier(player, Stat.MAGIC) < spell.level) {
            msg(player, "You need a Magic level of ${spell.level} to cast ${spell.name}.")
            return Result(Outcome.LEVEL_TOO_LOW, spell)
        }
        val runes = spell.runes ?: run {
            msg(player, "That spell is not available on this server yet.")
            return Result(Outcome.UNSUPPORTED, spell, "unresolved rune params")
        }
        if (runes.any { (id, n) -> c.count(id) < n + (if (id == held.id) 1 else 0) }) {
            msg(player, "You do not have enough runes to cast this spell.")
            return Result(Outcome.NO_RUNES, spell)
        }
        nextCastAt[player] = now + CAST_TICKS
        val r = when (spell.name.lowercase()) {
            "high level alchemy" -> alch(player, spell, runes, targetSlot, held, HIGH_ALCH_PERCENT)
            "low level alchemy" -> alch(player, spell, runes, targetSlot, held, LOW_ALCH_PERCENT)
            "superheat item" -> superheat(player, spell, runes, held)
            else -> {
                msg(player, "That spell is not available on this server yet.")
                Result(Outcome.UNSUPPORTED, spell)
            }
        }
        if (r.outcome != Outcome.CAST) nextCastAt[player] = now + 1
        if (r.outcome == Outcome.CAST) {
            casts++
            spell.animation?.let { ProductionActions.animationSink(player, intArrayOf(it, it, it, it)) }
            logger.info { "magic ${player.name}: ${spell.name} (slot $slot) on ${held.id} - ${r.detail}" }
        }
        return r
    }

    private fun alch(player: ContentPlayer, spell: Spell, runes: Map<Int, Int>, targetSlot: Int, held: Item, percent: Int): Result {
        if (held.id == COINS) { msg(player, "You can't cast that spell on coins."); return Result(Outcome.BAD_TARGET, spell, "coins") }
        val value = valueOf(held.id)?.takeIf { it > 0 }
            ?: run { msg(player, "You can't cast that spell on that item."); return Result(Outcome.BAD_TARGET, spell, "no value") }
        val coins = (value * percent / 100).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
        val c = ProductionActions.containerSupplier(player)
        val snapshot = c.toArray()
        val live = c[targetSlot]
        if (live == null || live.id != held.id) return Result(Outcome.BAD_TARGET, spell, "the target moved")
        if (live.amount > 1) c[targetSlot] = Item(live.id, live.amount - 1) else c.removeSlot(targetSlot)
        for ((id, n) in runes) {
            if (c.remove(id, n).removed != n) {
                for (i in snapshot.indices) c[i] = snapshot[i]
                msg(player, "You do not have enough runes to cast this spell.")
                return Result(Outcome.NO_RUNES, spell)
            }
        }
        if (coins > 0 && !pouchDeposit(player, coins)) {
            if (c.add(COINS, coins).remaining > 0) {
                for (i in snapshot.indices) c[i] = snapshot[i]
                msg(player, "You don't have enough inventory space.")
                return Result(Outcome.NO_SPACE, spell)
            }
        }
        ProductionActions.inventoryResend(player)
        spell.xp?.let { runCatching { ProductionActions.xpSink(player, Stat.MAGIC, it) } }
        return Result(Outcome.CAST, spell, "$coins coins ($percent% of $value) [approximate rate]; ${spell.xp} Magic xp")
    }

    private fun superheat(player: ContentPlayer, spell: Spell, runes: Map<Int, Int>, held: Item): Result {
        val recipe = runCatching { Smithing.smeltRecipes.values }.getOrDefault(emptyList())
            .filter { r -> r.materials.any { it.itemId == held.id } }
            .minByOrNull { it.level }
            ?: run { msg(player, "You need to cast superheat item on ore."); return Result(Outcome.BAD_TARGET, spell, "not an ore") }
        if (ProductionActions.levelSupplier(player, Stat.SMITHING) < recipe.level) {
            msg(player, "You need a Smithing level of ${recipe.level} to smelt ${recipe.barName.lowercase()}.")
            return Result(Outcome.LEVEL_TOO_LOW, spell)
        }
        val c = ProductionActions.containerSupplier(player)
        if (recipe.materials.any { m -> c.count(m.itemId) < m.count + (runes[m.itemId] ?: 0) }) {
            msg(player, "You don't have the ores to make ${recipe.barName.lowercase()}.")
            return Result(Outcome.BAD_TARGET, spell, "materials short")
        }
        val snapshot = c.toArray()
        for ((id, n) in runes) c.remove(id, n)
        for (m in recipe.materials) c.remove(m.itemId, m.count)
        if (c.add(recipe.barId, 1).remaining > 0) {
            for (i in snapshot.indices) c[i] = snapshot[i]
            return Result(Outcome.NO_SPACE, spell)
        }
        ProductionActions.inventoryResend(player)
        spell.xp?.let { runCatching { ProductionActions.xpSink(player, Stat.MAGIC, it) } }
        runCatching { ProductionActions.xpSink(player, Stat.SMITHING, recipe.xpTenths / 10.0) }
        return Result(Outcome.CAST, spell, "${recipe.barName} (+${recipe.xpTenths / 10.0} Smithing, ${spell.xp} Magic)")
    }
}

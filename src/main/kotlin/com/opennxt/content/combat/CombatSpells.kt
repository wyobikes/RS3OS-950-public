package com.opennxt.content.combat

import com.opennxt.api.stat.Stat
import com.opennxt.content.ability.AbilityActivation
import com.opennxt.content.ability.AbilityDefinitions
import com.opennxt.model.combat.CombatStyle
import com.opennxt.model.combat.NpcCombatParams
import com.opennxt.model.combat.PlayerCombat
import com.opennxt.model.entity.player.PlayerInventory
import com.opennxt.model.world.WorldNpc
import com.opennxt.model.world.WorldPlayer
import com.opennxt.net.game.GamePacket
import com.opennxt.net.game.clientprot.IfButtonN
import com.opennxt.net.game.serverprot.MessageGame
import com.opennxt.net.game.serverprot.variables.VarpLarge
import com.opennxt.resources.sqlite.RsDatabase
import mu.KotlinLogging
import java.util.Collections
import java.util.Random
import java.util.WeakHashMap

object CombatSpells {
    private val logger = KotlinLogging.logger { }

    const val FLAG = "opennxt.combat.spells"
    val enabled: Boolean get() = System.getProperty(FLAG) != "off"

    const val ABILITY_RUNE_PERCENT_PROP = "opennxt.combat.spells.abilityRunePercent"
    const val ABILITY_RUNE_PERCENT_DEFAULT = 15
    val abilityRunePercent: Int get() = System.getProperty(ABILITY_RUNE_PERCENT_PROP)?.trim()?.toIntOrNull()?.coerceIn(0, 100) ?: ABILITY_RUNE_PERCENT_DEFAULT

    const val VARP_SELECTED_SPELL = 3170
    const val NONE = -1
    const val BOOK_ENUM = 6740
    val BOOK_INTERFACES: Set<Int> = setOf(1461, 1885)
    const val SLOT_COMPONENT = 7
    const val OP_CAST = 1
    const val OP_AUTOCAST = 2

    const val PARAM_NAME = 2794
    const val PARAM_LEVEL = 2807
    const val PARAM_BOOK = 2871
    const val PARAM_AUTOCASTABLE = 2874
    const val PARAM_DAMAGE_X10 = 2877
    const val PARAM_TIER_CAP = 2879
    const val PARAM_KIND = 2880
    const val PARAM_ANIMATION = 2914

    const val DAMAGE_X10_PER_TIER = 96

    val RUNE_PARAMS: Map<Int, String> = linkedMapOf(
        2898 to "Air rune", 2899 to "Earth rune", 2900 to "Water rune", 2901 to "Fire rune",
        2902 to "Mind rune", 2903 to "Body rune", 2904 to "Chaos rune", 2905 to "Death rune",
        2906 to "Blood rune", 2907 to "Soul rune", 2908 to "Astral rune", 2909 to "Nature rune",
        2910 to "Cosmic rune", 2911 to "Law rune", 2912 to "Armadyl rune", 9235 to "Time rune"
    )

    val STAFF_PARAMS: Map<Int, String> = linkedMapOf(972 to "Air rune", 973 to "Water rune", 974 to "Earth rune", 975 to "Fire rune")

    const val ONE_SHOT_TICKS = 10

    const val MESSAGE_REPEAT_TICKS = 10

    const val MSG_NO_SPELL = "You need to select a spell to auto-cast."
    const val MSG_NO_RUNES = "You do not have enough runes to cast this spell."
    fun msgLevel(spell: Spell) = "You need a Magic level of ${spell.level} to cast ${spell.name}."

    private const val SPELL_SEED = 0x5350454C4C53L

    data class Spell(
        val slot: Int,
        val structId: Int,
        val name: String,
        val book: Int,
        val level: Int,
        val damageX10: Int,
        val tierCap: Int?,
        val autocastable: Boolean,
        val kind: Int,
        val animation: Int?,
        val runes: Map<Int, Int>,
        val unresolved: List<Int>
    )

    @Volatile private var cached: Map<Int, Spell>? = null

    val spells: Map<Int, Spell>
        get() = cached ?: synchronized(this) { cached ?: load().also { cached = it } }

    val bySlot: Map<Int, Spell> get() = spells.values.associateBy { it.slot }

    fun isLoaded(): Boolean = cached != null
    internal fun invalidate() = synchronized(this) { cached = null }

    private val runeIds: Map<String, Int> by lazy {
        if (!RsDatabase.available) emptyMap() else {
            val names = (RUNE_PARAMS.values + STAFF_PARAMS.values).distinct()
            val inList = names.joinToString(",") { "'" + it.replace("'", "''") + "'" }
            RsDatabase.queryAll("SELECT name, MIN(id) FROM items WHERE name IN ($inList) GROUP BY name") { rs -> rs.getString(1) to rs.getInt(2) }
                .filter { it.first in names }.toMap()
        }
    }

    fun runeId(name: String): Int? = runeIds[name]

    private fun load(): Map<Int, Spell> {
        if (!RsDatabase.available) return emptyMap()
        val slots = RsDatabase.queryAll("SELECT key, value FROM enum_entry WHERE enum_id = $BOOK_ENUM") { rs ->
            (rs.getString(1)?.trim('"')?.toIntOrNull() ?: -1) to (rs.getString(2)?.trim('"')?.toIntOrNull() ?: -1)
        }.filter { it.first >= 0 && it.second >= 0 }
        if (slots.isEmpty()) return emptyMap()
        val structIds = slots.map { it.second }.toSet()
        val wanted = (listOf(PARAM_NAME, PARAM_LEVEL, PARAM_BOOK, PARAM_AUTOCASTABLE, PARAM_DAMAGE_X10, PARAM_TIER_CAP, PARAM_KIND, PARAM_ANIMATION) + RUNE_PARAMS.keys).joinToString(",")
        val props = HashMap<Int, MutableMap<Int, Any>>()
        RsDatabase.queryAll("SELECT struct_id, prop, intvalue, stringvalue FROM struct_param WHERE prop IN ($wanted)") { rs ->
            val iv = rs.getInt(3)
            val value: Any? = if (rs.wasNull()) rs.getString(4) else iv
            Triple(rs.getInt(1), rs.getInt(2), value)
        }.forEach { (s, p, v) -> if (s in structIds && v != null) props.getOrPut(s) { HashMap() }[p] = v }
        val out = LinkedHashMap<Int, Spell>()
        for ((slot, struct) in slots.sortedBy { it.first }) {
            val p = props[struct] ?: continue
            val damage = p[PARAM_DAMAGE_X10] as? Int ?: continue
            val runes = LinkedHashMap<Int, Int>()
            val unresolved = ArrayList<Int>()
            for ((param, runeName) in RUNE_PARAMS) {
                val n = p[param] as? Int ?: continue
                if (n <= 0) continue
                val id = runeIds[runeName]
                if (id == null) unresolved += param else runes[id] = (runes[id] ?: 0) + n
            }
            out[struct] = Spell(
                slot = slot, structId = struct, name = p[PARAM_NAME] as? String ?: "struct $struct",
                book = p[PARAM_BOOK] as? Int ?: 0, level = p[PARAM_LEVEL] as? Int ?: 1, damageX10 = damage,
                tierCap = p[PARAM_TIER_CAP] as? Int, autocastable = (p[PARAM_AUTOCASTABLE] as? Int) == 1,
                kind = p[PARAM_KIND] as? Int ?: 0, animation = p[PARAM_ANIMATION] as? Int, runes = runes, unresolved = unresolved
            )
        }
        logger.info {
            "combat spells: ${out.size} damaging spells from enum $BOOK_ENUM (${out.values.count { it.book == 0 }} standard, " +
                "${out.values.count { it.book == 1 }} ancient, ${out.values.count { it.unresolved.isNotEmpty() }} with an unresolved rune param)"
        }
        return out
    }

    @Volatile var observer: ((WorldPlayer, GamePacket) -> Unit)? = null

    @Volatile var projectileHook: ((WorldPlayer, WorldNpc, Int) -> Unit)? = null

    private val oneShot: MutableMap<WorldPlayer, Pair<Int, Int>> = Collections.synchronizedMap(WeakHashMap())
    private val lastMessage: MutableMap<WorldPlayer, Pair<String, Int>> = Collections.synchronizedMap(WeakHashMap())

    var random: Random = Random(PlayerCombat.bootSeed xor SPELL_SEED)

    @Volatile var selections = 0; private set
    @Volatile var deselections = 0; private set
    @Volatile var selectionRefusals = 0; private set
    @Volatile var gateCalls = 0; private set
    @Volatile var swingRefusals = 0; private set
    @Volatile var autoCasts = 0; private set
    @Volatile var runesConsumed = 0L; private set
    @Volatile var abilityCastRolls = 0; private set
    @Volatile var abilityRuneConsumptions = 0; private set
    @Volatile var abilityRuneShortfalls = 0; private set
    @Volatile var loginResends = 0; private set
    private val loggedTierGap = Collections.synchronizedSet(HashSet<Int>())

    fun selected(player: WorldPlayer): Spell? = spells[player.varpValue(VARP_SELECTED_SPELL)]

    enum class SelectOutcome { OFF, NOT_A_COMBAT_SPELL, NOT_AUTOCASTABLE, LEVEL_TOO_LOW, SELECTED, DESELECTED }

    fun select(player: WorldPlayer, spell: Spell): SelectOutcome {
        if (!enabled) return SelectOutcome.OFF
        if (spell.structId !in spells) { selectionRefusals++; return SelectOutcome.NOT_A_COMBAT_SPELL }
        if (!spell.autocastable) { selectionRefusals++; return SelectOutcome.NOT_AUTOCASTABLE }
        if (player.level(Stat.MAGIC) < spell.level) {
            selectionRefusals++
            message(player, msgLevel(spell), "select")
            return SelectOutcome.LEVEL_TOO_LOW
        }
        if (selected(player)?.structId == spell.structId) return deselect(player)
        varp(player, VARP_SELECTED_SPELL, spell.structId, store = true)
        selections++
        logger.info { "spells: ${player.name} auto-casts ${spell.name} (struct ${spell.structId}, slot ${spell.slot}, level ${spell.level})" }
        return SelectOutcome.SELECTED
    }

    fun deselect(player: WorldPlayer): SelectOutcome {
        if (!enabled) return SelectOutcome.OFF
        varp(player, VARP_SELECTED_SPELL, NONE, store = true)
        deselections++
        logger.info { "spells: ${player.name} auto-cast cleared (varp $VARP_SELECTED_SPELL = $NONE)" }
        return SelectOutcome.DESELECTED
    }

    fun sendLoginState(player: WorldPlayer): Boolean {
        if (!enabled) return false
        val struct = player.varpOverride(VARP_SELECTED_SPELL) ?: return false
        if (struct !in spells) return false
        varp(player, VARP_SELECTED_SPELL, struct, store = false)
        loginResends++
        return true
    }

    private val resentThisSession: MutableSet<WorldPlayer> = Collections.newSetFromMap(Collections.synchronizedMap(WeakHashMap()))

    fun handleButton(player: WorldPlayer, packet: IfButtonN): Boolean {
        if (!enabled) return false
        if (packet.interfaceId !in BOOK_INTERFACES || packet.component != SLOT_COMPONENT) return false
        val spell = bySlot[packet.arg2] ?: return false
        return when (packet.buttonOp) {
            OP_AUTOCAST -> { select(player, spell); true }
            OP_CAST -> {
                if (PlayerCombat.targetOf(player) == null) {
                    message(player, "You need a target to cast ${spell.name} on.", "cast")
                    logger.info { "spells: ${player.name} cast ${spell.name} with no target" }
                } else if (player.level(Stat.MAGIC) < spell.level) {
                    message(player, msgLevel(spell), "cast")
                } else {
                    oneShot[player] = spell.structId to PlayerCombat.ticks() + ONE_SHOT_TICKS
                    logger.info { "spells: ${player.name} queued ${spell.name} for the next attack ($ONE_SHOT_TICKS ticks)" }
                }
                true
            }
            else -> false
        }
    }

    fun spellForSwing(player: WorldPlayer): Spell? {
        val pending = oneShot[player]
        if (pending != null) {
            if (pending.second >= PlayerCombat.ticks()) spells[pending.first]?.let { return it }
            oneShot.remove(player)
        }
        return selected(player)
    }

    fun costFor(player: WorldPlayer, spell: Spell): Map<Int, Int> {
        val weapon = player.wornWeapon ?: return spell.runes
        val free = STAFF_PARAMS.filter { (param, _) -> weapon.params[param] == 1 }.mapNotNull { runeIds[it.value] }.toSet()
        if (free.isEmpty()) return spell.runes
        return spell.runes.filterKeys { it !in free }
    }

    fun missingRune(player: WorldPlayer, cost: Map<Int, Int>): Triple<Int, Int, Long>? {
        val backpack = PlayerInventory.backpackOf(player)
        for ((id, n) in cost) {
            val held = backpack.count(id)
            if (held < n) return Triple(id, n, held)
        }
        return null
    }

    private fun consumeRunes(player: WorldPlayer, cost: Map<Int, Int>, why: String): Boolean {
        if (cost.isEmpty()) return true
        if (missingRune(player, cost) != null) return false
        val backpack = PlayerInventory.backpackOf(player)
        var removed = 0L
        for ((id, n) in cost) removed += backpack.remove(id, n).removed
        runesConsumed += removed
        runCatching { PlayerInventory.sendBackpack(player) }.onFailure { logger.warn { "spells: backpack not re-sent to ${player.name}: ${it.message}" } }
        logger.debug { "spells: ${player.name} $why consumed $removed rune(s): $cost" }
        return true
    }

    fun gate(player: WorldPlayer, npc: WorldNpc, style: CombatStyle): String? {
        if (!enabled || style != CombatStyle.MAGIC) return null
        gateCalls++
        if (resentThisSession.add(player)) sendLoginState(player)
        val spell = spellForSwing(player) ?: return refuse(player, MSG_NO_SPELL, "no auto-cast spell selected (varp $VARP_SELECTED_SPELL = ${player.varpValue(VARP_SELECTED_SPELL)})")
        if (spell.unresolved.isNotEmpty()) return refuse(player, MSG_NO_RUNES, "${spell.name} has unresolved rune params ${spell.unresolved}")
        val level = player.level(Stat.MAGIC)
        if (level < spell.level) return refuse(player, msgLevel(spell), "${spell.name} needs Magic ${spell.level}, ${player.name} has $level")
        val short = missingRune(player, costFor(player, spell))
        if (short != null) return refuse(player, MSG_NO_RUNES, "${spell.name} needs ${short.second} x item ${short.first}, ${player.name} holds ${short.third}")
        return null
    }

    private fun refuse(player: WorldPlayer, line: String, detail: String): String {
        swingRefusals++
        message(player, line, detail)
        return "magic swing refused: $detail"
    }

    fun maxHitOverride(player: WorldPlayer, style: CombatStyle, derivedX10: Int): Int {
        if (!enabled || style != CombatStyle.MAGIC) return derivedX10
        val spell = spellForSwing(player) ?: return derivedX10
        return maxHitX10For(player, spell)
    }

    fun maxHitX10For(player: WorldPlayer, spell: Spell): Int {
        var tier = player.level(Stat.MAGIC).coerceAtLeast(1)
        spell.tierCap?.let { tier = minOf(tier, it) }
        val weapon = player.wornWeapon
        val weaponTier = weapon?.params?.get(NpcCombatParams.ITEM_REQUIREMENT_LEVEL)
        if (weaponTier != null) tier = minOf(tier, weaponTier.coerceAtLeast(1))
        else if (weapon != null && loggedTierGap.add(weapon.definition.id)) {
            logger.info { "spells: ${weapon.definition.name} (${weapon.definition.id}) has no tier param ${NpcCombatParams.ITEM_REQUIREMENT_LEVEL}; spell tier not capped" }
        }
        return minOf(spell.damageX10, DAMAGE_X10_PER_TIER * tier)
    }

    fun ownsMaxHit(player: WorldPlayer, style: CombatStyle): Boolean = enabled && style == CombatStyle.MAGIC

    fun abilityMaxHitOverride(player: WorldPlayer, style: AbilityDefinitions.Style, derivedX10: Int?): Int? {
        if (!enabled || style != AbilityDefinitions.Style.MAGIC) return derivedX10
        val spell = selected(player) ?: return derivedX10
        return maxHitX10For(player, spell)
    }

    fun consume(player: WorldPlayer, npc: WorldNpc, style: CombatStyle) {
        if (!enabled || style != CombatStyle.MAGIC) return
        val spell = spellForSwing(player) ?: return
        oneShot.remove(player)
        if (!consumeRunes(player, costFor(player, spell), "auto-cast ${spell.name}")) {
            logger.warn { "spells: ${player.name}'s runes for ${spell.name} were missing at consumption; attack still applied" }
        }
        autoCasts++
        projectileHook?.let { hook -> runCatching { hook(player, npc, spell.structId) }.onFailure { logger.error(it) { "spells: projectile hook failed for ${player.name}" } } }
    }

    fun onAbilityCast(player: WorldPlayer, def: AbilityDefinitions.Definition) {
        if (!enabled || def.style != AbilityDefinitions.Style.MAGIC) return
        val spell = selected(player) ?: return
        abilityCastRolls++
        val percent = abilityRunePercent
        val roll = random.nextInt(100)
        if (roll >= percent) return
        if (consumeRunes(player, costFor(player, spell), "ability ${def.name}")) abilityRuneConsumptions++
        else { abilityRuneShortfalls++; logger.debug { "spells: ${player.name}'s ${def.name} lacks runes for ${spell.name}; nothing consumed" } }
    }

    @Volatile private var installed = false

    fun install() {
        if (installed) return
        installed = true
        invalidate()
        val prevGate = PlayerCombat.swingGate
        PlayerCombat.swingGate = { p, n, s -> prevGate?.invoke(p, n, s) ?: gate(p, n, s) }
        val prevOverride = PlayerCombat.swingMaxHitX10Override
        PlayerCombat.swingMaxHitX10Override = { p, s, x -> maxHitOverride(p, s, prevOverride?.invoke(p, s, x) ?: x) }
        val prevConsumer = PlayerCombat.swingConsumer
        PlayerCombat.swingConsumer = { p, n, s -> prevConsumer?.invoke(p, n, s); consume(p, n, s) }
        val prevCast = AbilityActivation.onAbilityCast
        AbilityActivation.onAbilityCast = { p, d -> prevCast?.invoke(p, d); onAbilityCast(p, d) }
        val prevOwner = PlayerCombat.swingMaxHitOwner
        PlayerCombat.swingMaxHitOwner = { p, s -> prevOwner?.invoke(p, s) == true || ownsMaxHit(p, s) }
        val prevAbility = AbilityActivation.abilityMaxHitX10Override
        AbilityActivation.abilityMaxHitX10Override = { p, s, x -> abilityMaxHitOverride(p, s, if (prevAbility != null) prevAbility(p, s, x) else x) }
        logger.info { "spells: installed ${spells.size} combat spells (ability rune chance $abilityRunePercent%, -D$FLAG=off to disable)" }
    }

    private fun varp(player: WorldPlayer, id: Int, value: Int, store: Boolean) {
        observer?.invoke(player, VarpLarge(id, value))
        runCatching { player.setVarpOverride(id, value, store = store) }.onFailure { logger.warn { "spells: varp $id = $value not sent to ${player.name}: ${it.message}" } }
    }

    private fun message(player: WorldPlayer, line: String, reason: String) {
        val now = PlayerCombat.ticks()
        val last = lastMessage[player]
        if (last != null && last.first == line && now - last.second < MESSAGE_REPEAT_TICKS) return
        lastMessage[player] = line to now
        val packet = MessageGame(0, line)
        observer?.invoke(player, packet)
        runCatching { player.client.write(packet) }.onFailure { logger.debug { "spells: message not written to ${player.name} ($reason): ${it.message}" } }
    }
}

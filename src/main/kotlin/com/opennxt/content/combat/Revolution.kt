package com.opennxt.content.combat

import com.opennxt.content.ability.AbilityActivation
import com.opennxt.content.ability.AbilityActivation.Outcome
import com.opennxt.content.ability.AbilityActivation.Reason
import com.opennxt.content.ability.AbilityDefinitions
import com.opennxt.content.ability.AbilityDefinitions.Category
import com.opennxt.content.impl.AbilityBar
import com.opennxt.content.impl.LoginVarps
import com.opennxt.model.combat.PlayerCombat
import com.opennxt.model.lobby.TODORefactorThisClass
import com.opennxt.model.vars.SqliteVarBits
import com.opennxt.model.world.WorldPlayer
import com.opennxt.net.game.GamePacket
import com.opennxt.net.game.clientprot.IfButtonN
import com.opennxt.net.game.serverprot.MessageGame
import com.opennxt.resources.sqlite.VarBitDefinition
import mu.KotlinLogging
import java.util.Collections
import java.util.EnumMap
import java.util.IdentityHashMap

object Revolution {
    private val logger = KotlinLogging.logger { }

    const val FLAG = "opennxt.combat.revolution"
    const val SIZE_FLAG = "opennxt.combat.revolution.size"
    const val THRESHOLDS_FLAG = "opennxt.combat.revolution.thresholds"
    const val ULTIMATES_FLAG = "opennxt.combat.revolution.ultimates"

    val enabled: Boolean get() = System.getProperty(FLAG) != "off"
    val size: Int get() = (System.getProperty(SIZE_FLAG)?.toIntOrNull() ?: AbilityBar.SLOTS).coerceIn(1, AbilityBar.SLOTS)
    val thresholdsOn: Boolean get() = System.getProperty(THRESHOLDS_FLAG) == "on"
    val ultimatesOn: Boolean get() = System.getProperty(ULTIMATES_FLAG) == "on"

    const val VARP_COMBAT_MODE = 627
    const val VARBIT_COMBAT_MODE = 21685
    const val VARP_LEGACY = 3680
    const val VARBIT_LEGACY = 27168
    const val MODE_FULL_MANUAL = 0
    const val MODE_REVOLUTION = 1
    const val MODE_LEGACY = 3

    const val ICON_IFACE = 268
    const val ICON_COMPONENT = 0
    const val OP_FULL_MANUAL = 6
    const val OP_REVOLUTION = 7
    const val MESSAGE_TYPE = 109
    const val MSG_ACTIVE = "Revolution is now active."
    const val MSG_INACTIVE = "<col=EB2F2F>Revolution is no longer active."
    const val RATE_KEY = "revolutionMode"
    const val QUIET_TICKS = 17L

    val EXCLUDED: Set<Int> = setOf(14726, 14665, 47129, 1488, 14725, 37203, 45340, 14690)

    val STATIC_REASONS: Set<Reason> = setOf(Reason.LEVEL, Reason.STYLE, Reason.NOT_MODELLED, Reason.UNRESOLVED, Reason.NOT_COMPUTABLE, Reason.REQUIREMENT)

    enum class Mode { FULL_MANUAL, REVOLUTION, LEGACY, UNKNOWN }

    val modeDef: VarBitDefinition by lazy { SqliteVarBits.definition(VARBIT_COMBAT_MODE) ?: VarBitDefinition(VARBIT_COMBAT_MODE, VARP_COMBAT_MODE, 10, 11) }
    val legacyDef: VarBitDefinition by lazy { SqliteVarBits.definition(VARBIT_LEGACY) ?: VarBitDefinition(VARBIT_LEGACY, VARP_LEGACY, 20, 20) }

    var observer: ((WorldPlayer, GamePacket) -> Unit)? = null

    var walks = 0; private set
    var fired = 0; private set
    var slotsTried = 0; private set
    var defaultsWritten = 0; private set
    var modeChanges = 0; private set
    var modeUnchanged = 0; private set
    var throttled = 0; private set
    var refusalsQuieted = 0; private set
    var skippedGcd = 0; private set
    var skippedChannel = 0; private set
    var skippedBarNotOpen = 0; private set
    var skippedExcluded = 0; private set
    var skippedCategory = 0; private set
    var skippedCooldown = 0; private set
    var skippedAdrenaline = 0; private set
    var skippedQuiet = 0; private set
    var offTicks = 0; private set
    private val skippedByMode = EnumMap<Mode, Int>(Mode::class.java)
    fun skippedByMode(mode: Mode): Int = skippedByMode[mode] ?: 0

    private val quiet = IdentityHashMap<WorldPlayer, HashMap<Int, Long>>()
    fun quietedPlayers(): Int = quiet.size

    fun modeOf(player: WorldPlayer): Mode {
        if (legacyDef.read(player.varpValue(VARP_LEGACY)) == 1) return Mode.LEGACY
        return when (modeDef.read(player.varpValue(VARP_COMBAT_MODE))) {
            MODE_FULL_MANUAL -> Mode.FULL_MANUAL
            MODE_REVOLUTION -> Mode.REVOLUTION
            MODE_LEGACY -> Mode.LEGACY
            else -> Mode.UNKNOWN
        }
    }

    fun isRevolution(player: WorldPlayer): Boolean = modeOf(player) == Mode.REVOLUTION

    fun loginTableHasMode(): Boolean {
        loginTableHasModeCache?.let { return it }
        val has = TODORefactorThisClass.fileVarpTable?.containsKey(VARP_COMBAT_MODE) == true || LoginVarps.table.any { it.first == VARP_COMBAT_MODE }
        loginTableHasModeCache = has
        return has
    }
    @Volatile private var loginTableHasModeCache: Boolean? = null

    fun ensureDefault(player: WorldPlayer): Boolean {
        if (player.varpOverride(VARP_COMBAT_MODE) != null || loginTableHasMode()) return false
        val value = modeDef.write(0, MODE_REVOLUTION)
        player.setVarpOverride(VARP_COMBAT_MODE, value, store = true)
        defaultsWritten++
        logger.info { "revolution: ${player.name} had no combat mode stored and the login table sends none - default Revolution written (varp $VARP_COMBAT_MODE = $value, stored)" }
        return true
    }

    fun setMode(player: WorldPlayer, revolution: Boolean, via: String): Boolean {
        val current = player.varpValue(VARP_COMBAT_MODE)
        val target = if (revolution) MODE_REVOLUTION else MODE_FULL_MANUAL
        if (modeDef.read(current) == target) {
            modeUnchanged++
            logger.info { "revolution: ${player.name} selected ${if (revolution) "Revolution" else "Full Manual"} via $via - already the mode (varp $VARP_COMBAT_MODE = $current); nothing written" }
            return false
        }
        val value = modeDef.write(current, target)
        player.setVarpOverride(VARP_COMBAT_MODE, value, store = true)
        send(player, MessageGame(MESSAGE_TYPE, if (revolution) MSG_ACTIVE else MSG_INACTIVE))
        modeChanges++
        logger.info { "revolution: ${player.name} mode -> ${if (revolution) "Revolution" else "Full Manual"} via $via: varp $VARP_COMBAT_MODE $current -> $value (stored); legacy bit ${legacyDef.read(player.varpValue(VARP_LEGACY))}" }
        return true
    }

    fun handleButton(player: WorldPlayer, packet: IfButtonN): Boolean {
        if (!enabled) return false
        if (packet.interfaceId != ICON_IFACE || packet.component != ICON_COMPONENT) return false
        val revolution = when (packet.buttonOp) {
            OP_REVOLUTION -> true
            OP_FULL_MANUAL -> false
            else -> return false
        }
        if (!player.allowOncePerTick(RATE_KEY)) {
            throttled++
            logger.info { "revolution: ${player.name} clicked $ICON_IFACE:$ICON_COMPONENT op ${packet.buttonOp} again in the same tick - dropped (one mode click per tick)" }
            return true
        }
        setMode(player, revolution, "$ICON_IFACE:$ICON_COMPONENT op ${packet.buttonOp}")
        return true
    }

    private val hook: (List<WorldPlayer>) -> Unit = { players -> onTick(players) }

    fun install() {
        AbilityActivation.preTick = hook
    }

    fun onTick(players: List<WorldPlayer>) {
        if (!enabled) { offTicks++; return }
        prune(players)
        for (player in players) {
            ensureDefault(player)
            if (PlayerCombat.targetOf(player) == null) continue
            walk(player)
        }
    }

    private fun prune(players: List<WorldPlayer>) {
        if (quiet.isEmpty()) return
        val live = Collections.newSetFromMap(IdentityHashMap<WorldPlayer, Boolean>())
        live.addAll(players)
        quiet.keys.retainAll(live)
    }

    fun walk(player: WorldPlayer): Outcome.Activated? {
        if (!enabled || !AbilityActivation.enabled || !PlayerCombat.enabled) return null
        val mode = modeOf(player)
        if (mode != Mode.REVOLUTION) { skippedByMode[mode] = (skippedByMode[mode] ?: 0) + 1; return null }
        val st = AbilityActivation.stateFor(player)
        val now = AbilityActivation.clock
        if (st.onGcd(now)) { skippedGcd++; return null }
        st.channel?.let { ch -> if (now < ch.endTick) { skippedChannel++; return null } }
        if (!player.interfaces.isOpened(AbilityBar.BAR_IFACE)) { skippedBarNotOpen++; return null }
        walks++
        val quietFor = quiet[player]
        val thresholds = thresholdsOn
        val ultimates = ultimatesOn
        for (slot in 1..size) {
            val value = player.varpValue(AbilityBar.slotVarp(slot))
            if (AbilityBar.isEmpty(value)) continue
            val ability = AbilityBar.abilityFor(AbilityBar.unpackType(value), AbilityBar.unpackIndex(value)) ?: continue
            val def = AbilityDefinitions.forStruct(ability.structId) ?: continue
            if (def.structId in EXCLUDED) { skippedExcluded++; continue }
            val allowed = when (def.category) {
                Category.BASIC -> true
                Category.THRESHOLD -> thresholds
                Category.ULTIMATE -> ultimates
                else -> false
            }
            if (!allowed) { skippedCategory++; continue }
            if (st.cooldownRemaining(def.structId, now) > 0) { skippedCooldown++; continue }
            if (st.adrenaline.tenths < def.adrenalineCost) { skippedAdrenaline++; continue }
            val quietUntil = quietFor?.get(def.structId)
            if (quietUntil != null) {
                if (now < quietUntil) { skippedQuiet++; continue }
                quietFor.remove(def.structId)
            }
            slotsTried++
            when (val outcome = AbilityActivation.activateSlot(player, AbilityBar.BAR_IFACE, slot)) {
                is Outcome.Activated -> {
                    fired++
                    logger.info {
                        "revolution: ${player.name} FIRED ${def.name} (struct ${def.structId}, slot $slot) at tick $now" +
                            (outcome.target?.let { " on ${it.name ?: "npc"} ${it.gameId}" } ?: "") + ", GCD to ${outcome.gcdEnd}"
                    }
                    return outcome
                }
                is Outcome.Refused -> {
                    if (outcome.reason == Reason.GCD) return null
                    if (outcome.reason in STATIC_REASONS) {
                        quiet.getOrPut(player) { HashMap() }[def.structId] = now + QUIET_TICKS
                        refusalsQuieted++
                    }
                }
            }
        }
        return null
    }

    private fun send(player: WorldPlayer, packet: GamePacket) {
        observer?.invoke(player, packet)
        runCatching { player.client.write(packet) }.onFailure { logger.warn { "revolution: could not write $packet to ${player.name}: ${it.message}" } }
    }
}

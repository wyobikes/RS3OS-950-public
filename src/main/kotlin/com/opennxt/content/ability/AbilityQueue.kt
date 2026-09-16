package com.opennxt.content.ability

import com.opennxt.content.combat.Revolution
import com.opennxt.content.impl.AbilityBar
import com.opennxt.model.combat.PlayerCombat
import com.opennxt.model.world.WorldPlayer
import mu.KotlinLogging

object AbilityQueue {
    private val logger = KotlinLogging.logger { }

    const val FLAG = "opennxt.combat.abilityQueue"
    const val VARP_QUEUED_SLOT = 4164
    const val VARP_QUEUED_BAR = 5861
    const val VARP_QUEUE_TRIGGER_TIME = 4513
    const val VARP_QUEUE_TRIGGER_ADRENALINE = 5731
    const val VARP_QUEUEING_SETTING = 627
    const val QUEUEING_OFF_BIT = 9
    const val MAIN_BAR_ID = AbilityBar.PANEL_ACTION_BAR

    enum class Mode { OFF, ON, SETTING }

    val mode: Mode
        get() = when (System.getProperty(FLAG)?.trim()?.lowercase()) {
            "on", "true" -> Mode.ON
            "setting" -> Mode.SETTING
            else -> Mode.OFF
        }

    data class Queued(val iface: Int, val slot: Int, val structId: Int, val name: String, val queuedAt: Long)

    var queuedCount = 0; private set
    var unqueuedCount = 0; private set
    var replacedCount = 0; private set
    var firedCount = 0; private set
    var droppedCount = 0; private set
    var swingHoldsRequested = 0; private set

    fun allowed(player: WorldPlayer): Boolean {
        val m = mode
        if (m == Mode.OFF) return false
        if (runCatching { Revolution.modeOf(player) }.getOrNull() == Revolution.Mode.LEGACY) return false
        if (m == Mode.SETTING) {
            val v = runCatching { player.varpValue(VARP_QUEUEING_SETTING) }.getOrDefault(0)
            if ((v ushr QUEUEING_OFF_BIT) and 1 == 1) return false
        }
        return true
    }

    fun barIdFor(iface: Int): Int? = when (iface) {
        AbilityBar.BAR_IFACE, AbilityBar.WINDOW_BAR_IFACE -> MAIN_BAR_ID
        else -> null
    }

    fun offer(player: WorldPlayer, st: AbilityState, def: AbilityDefinitions.Definition, iface: Int, slot: Int, now: Long): String? {
        if (!allowed(player)) return null
        barIdFor(iface) ?: return null
        val current = st.queued
        if (current != null && current.slot == slot && current.structId == def.structId) {
            clear(player, st, "clicked again")
            unqueuedCount++
            return "UNQUEUED (clicked again)"
        }
        val replaced = current?.name
        st.queued = Queued(iface, slot, def.structId, def.name, now)
        writeVarps(player, slot, MAIN_BAR_ID)
        queuedCount++
        if (replaced != null) replacedCount++
        logger.info { "abilities: ${player.name} QUEUED ${def.name} (struct ${def.structId}) on bar $iface slot $slot at tick $now" + (replaced?.let { ", replacing $it" } ?: "") }
        return if (replaced != null) "QUEUED (replaced $replaced)" else "QUEUED"
    }

    fun clear(player: WorldPlayer, st: AbilityState, why: String) {
        val q = st.queued ?: return
        st.queued = null
        writeVarps(player, 0, 0)
        logger.info { "abilities: ${player.name}'s queued ${q.name} (slot ${q.slot}) cleared - $why" }
    }

    fun onCommit(player: WorldPlayer, st: AbilityState, def: AbilityDefinitions.Definition) {
        val q = st.queued ?: return
        if (q.structId != def.structId) clear(player, st, "${def.name} was used")
    }

    fun tick(states: List<Pair<WorldPlayer, AbilityState>>, now: Long) {
        for ((player, st) in states) {
            val q = st.queued ?: continue
            if (mode == Mode.OFF) { clear(player, st, "-D$FLAG=off"); droppedCount++; continue }
            val cooldownLeft = st.cooldownRemaining(q.structId, now)
            val ignoresGcd = AbilityDefinitions.forStruct(q.structId)?.let { MovementAbilities.handles(it) } == true
            if ((!ignoresGcd && st.onGcd(now)) || cooldownLeft > 0) {
                if (st.cooldownRemaining(q.structId, now + 1) == 0L) {
                    swingHoldsRequested++
                    runCatching { PlayerCombat.holdSwing(player, PlayerCombat.ticks() + 2) }
                }
                continue
            }
            st.queued = null
            writeVarps(player, 0, 0)
            when (val o = AbilityActivation.activateSlot(player, q.iface, q.slot, fromQueue = q.structId)) {
                is AbilityActivation.Outcome.Activated -> {
                    firedCount++
                    logger.info { "abilities: ${player.name}'s queued ${q.name} FIRED at tick $now (queued at ${q.queuedAt})" }
                }
                is AbilityActivation.Outcome.Refused -> {
                    droppedCount++
                    logger.info { "abilities: ${player.name}'s queued ${q.name} DROPPED at tick $now - ${o.reason}: ${o.detail}" }
                }
            }
        }
    }

    private fun writeVarps(player: WorldPlayer, slot: Int, bar: Int) {
        AbilityActivation.varpFor(player, VARP_QUEUED_SLOT, slot)
        AbilityActivation.varpFor(player, VARP_QUEUED_BAR, bar)
    }
}

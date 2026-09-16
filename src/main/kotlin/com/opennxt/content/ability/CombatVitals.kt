package com.opennxt.content.ability

import com.opennxt.model.world.WorldPlayer
import mu.KotlinLogging

object CombatVitals {
    private val logger = KotlinLogging.logger { }

    const val DRAIN_FLAG = "opennxt.combat.adrenalineDrain"
    const val REGEN_FLAG = "opennxt.combat.regen"
    const val REGENERATE_FLAG = "opennxt.combat.regenerate"
    val drainEnabled: Boolean get() = System.getProperty(DRAIN_FLAG) != "off"
    val regenEnabled: Boolean get() = System.getProperty(REGEN_FLAG) != "off"
    val regenerateEnabled: Boolean get() = System.getProperty(REGENERATE_FLAG) != "off"

    const val STANCE_TICKS = 17
    const val DRAIN_TENTHS = 50
    const val PASSIVE_REGEN_EVERY_TICKS = 10
    const val PASSIVE_REGEN_PERMILLE = 1
    const val REGENERATE_PERCENT = 2
    const val REGENERATE_COST_TENTHS = 100
    const val REGENERATE_LOW_PERMILLE_PER_PERCENT = 2

    var drains = 0; private set
    var drainedTenths = 0; private set
    var passiveRegens = 0; private set
    var regenerateTicks = 0; private set
    var regenerateSpentTenths = 0; private set
    var healsApplied = 0; private set
    var lifepointsHealed = 0; private set

    private val now: Long get() = AbilityActivation.clock

    fun onDamageTaken(player: WorldPlayer, amount: Int, died: Boolean) {
        if (amount <= 0) return
        val st = AbilityActivation.stateFor(player)
        st.lastCombatTick = now
        st.lastDamageTick = now
        if (died) StatusEffects.clearPlayer(player, "died")
    }

    fun onDamageDealt(player: WorldPlayer) {
        val st = AbilityActivation.stateFor(player)
        st.lastCombatTick = now
        st.lastDamageTick = now
    }

    fun onCombatAction(player: WorldPlayer) {
        AbilityActivation.stateFor(player).lastCombatTick = now
    }

    fun heal(player: WorldPlayer, amount: Int, why: String): Int {
        if (amount <= 0) return 0
        val applied = player.heal(amount)
        if (applied > 0) {
            healsApplied++
            lifepointsHealed += applied
            logger.debug { "vitals: tick $now player ${player.name} +$applied lifepoints ($why) -> ${player.currentLifepoints}/${player.maxLifepoints}" }
        }
        return applied
    }

    fun ticksIdle(player: WorldPlayer): Long {
        val st = AbilityActivation.stateOrNull(player) ?: return Long.MAX_VALUE
        return if (st.lastCombatTick == Long.MIN_VALUE) Long.MAX_VALUE else now - st.lastCombatTick
    }

    fun inCombatStance(player: WorldPlayer): Boolean =
        com.opennxt.model.combat.PlayerCombat.targetOf(player)?.alive == true || ticksIdle(player) < STANCE_TICKS

    fun passiveRegenAmount(player: WorldPlayer): Int = (player.maxLifepoints * PASSIVE_REGEN_PERMILLE / 1000).coerceAtLeast(1)

    fun tick(players: List<WorldPlayer>, varp: (WorldPlayer, Int, Int) -> Unit) {
        val drain = drainEnabled
        val regen = regenEnabled
        val regenerate = regenerateEnabled
        if (!drain && !regen && !regenerate) return
        val t = now
        for (player in players) {
            val st = AbilityActivation.stateFor(player)
            val stanceOver = st.lastCombatTick != Long.MIN_VALUE && t - st.lastCombatTick >= STANCE_TICKS &&
                com.opennxt.model.combat.PlayerCombat.targetOf(player)?.alive != true
            var spentThisTick = false
            val alive = player.currentLifepoints > 0

            if (regenerate && alive && stanceOver && player.currentLifepoints < player.maxLifepoints && st.adrenaline.tenths > 0) {
                val before = st.adrenaline.tenths
                val max = player.maxLifepoints
                val amount: Int
                val spend: Int
                if (before >= REGENERATE_COST_TENTHS) {
                    amount = max * REGENERATE_PERCENT / 100
                    spend = REGENERATE_COST_TENTHS
                } else {
                    amount = max * REGENERATE_LOW_PERMILLE_PER_PERCENT * (before / 10) / 1000
                    spend = before
                }
                val removed = st.adrenaline.drain(spend)
                spentThisTick = removed > 0
                regenerateTicks++
                regenerateSpentTenths += removed
                if (removed > 0) {
                    varp(player, AbilityActivation.VARP_ADRENALINE_PREVIOUS, before)
                    varp(player, AbilityActivation.VARP_ADRENALINE, st.adrenaline.tenths)
                }
                heal(player, amount, "Regenerate (adrenaline $before -> ${st.adrenaline.tenths})")
            }

            if (drain && stanceOver && !spentThisTick && st.adrenaline.tenths > 0) {
                val before = st.adrenaline.tenths
                val removed = st.adrenaline.drain(DRAIN_TENTHS)
                if (removed > 0) {
                    drains++; drainedTenths += removed
                    varp(player, AbilityActivation.VARP_ADRENALINE_PREVIOUS, before)
                    varp(player, AbilityActivation.VARP_ADRENALINE, st.adrenaline.tenths)
                    logger.debug { "vitals: tick $t player ${player.name} adrenaline drained $before -> ${st.adrenaline.tenths} (idle ${t - st.lastCombatTick} ticks)" }
                }
            }

            if (regen) {
                st.slowRegenTicks++
                if (st.slowRegenTicks >= PASSIVE_REGEN_EVERY_TICKS) {
                    st.slowRegenTicks = 0
                    if (alive && player.currentLifepoints < player.maxLifepoints && heal(player, passiveRegenAmount(player), "passive regeneration") > 0) passiveRegens++
                }
            }
        }
    }
}

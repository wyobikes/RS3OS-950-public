package com.opennxt.content.combat

import com.opennxt.content.ability.AbilityActivation
import com.opennxt.model.world.WorldPlayer
import mu.KotlinLogging
import java.util.IdentityHashMap

object NecromancyHud {
    private val logger = KotlinLogging.logger { }

    const val VARP_RESIDUAL_SOULS = 11035

    var observer: ((WorldPlayer, Int, Int) -> Unit)? = null

    private val lastSouls = IdentityHashMap<WorldPlayer, Int>()
    private val lastNecrosis = IdentityHashMap<WorldPlayer, Int>()

    var soulsSent = 0; private set
    var flagsSent = 0; private set
    var necrosisChanges = 0; private set

    fun conjureFlag(player: WorldPlayer, kind: Conjures.Kind, active: Boolean) {
        flagsSent++
        varp(player, kind.varp, if (active) 1 else 0)
    }

    fun syncSouls(player: WorldPlayer): Boolean {
        val souls = AbilityActivation.stateOrNull(player)?.residualSouls ?: 0
        val last = lastSouls[player]
        if (last == souls) return false
        lastSouls[player] = souls
        soulsSent++
        varp(player, VARP_RESIDUAL_SOULS, souls)
        logger.info { "necromancy hud: ${player.name} residual souls ${last ?: "unsent"} -> $souls (varp $VARP_RESIDUAL_SOULS)" }
        return true
    }

    fun syncNecrosis(player: WorldPlayer) {
        val n = AbilityActivation.stateOrNull(player)?.necrosis ?: 0
        val last = lastNecrosis[player]
        if (last == n) return
        lastNecrosis[player] = n
        necrosisChanges++
        logger.info { "necromancy hud: ${player.name} necrosis ${last ?: "unseen"} -> $n (no varp found for the stacks - logged only)" }
    }

    fun tick(players: List<WorldPlayer>) {
        for (p in players) { syncSouls(p); syncNecrosis(p) }
        if (lastSouls.isNotEmpty() || lastNecrosis.isNotEmpty()) {
            val live = java.util.Collections.newSetFromMap(IdentityHashMap<WorldPlayer, Boolean>()).also { it.addAll(players) }
            lastSouls.keys.retainAll(live)
            lastNecrosis.keys.retainAll(live)
        }
    }

    private fun varp(player: WorldPlayer, id: Int, value: Int) {
        observer?.invoke(player, id, value)
        runCatching { player.setVarpOverride(id, value, store = false) }
            .onFailure { logger.warn { "necromancy hud: varp $id = $value not sent to ${player.name}: ${it.message}" } }
    }
}

package com.opennxt.content.combat

import com.opennxt.model.world.Projectiles
import com.opennxt.model.world.WorldNpc
import com.opennxt.model.world.WorldPlayer
import com.opennxt.net.game.serverprot.ZoneSubProtocol950
import mu.KotlinLogging

object CombatVisuals {
    private val logger = KotlinLogging.logger { }

    const val START_HEIGHT = 144
    const val END_HEIGHT = 50
    const val START_DELAY = 42
    const val END_DELAY = 60

    var launched = 0
        private set
    var refused = 0
        private set
    private val loggedSpells = HashSet<Int>()

    fun install() {
        RangedAmmo.projectileHook = { player, npc, spotanim -> arrow(player, npc, spotanim) }
        CombatSpells.projectileHook = { player, npc, spell -> spellBolt(player, npc, spell) }
    }

    fun arrow(player: WorldPlayer, npc: WorldNpc, spotanim: Int) {
        if (spotanim <= 0) { refused++; return }
        val from = player.entity.location
        val ok = runCatching {
            Projectiles.launch(
                fromX = from.x, fromY = from.y, toX = npc.location.x, toY = npc.location.y, plane = from.plane,
                spotanim = spotanim, startHeight = START_HEIGHT, endHeight = END_HEIGHT,
                startDelay = START_DELAY, endDelay = END_DELAY, angle = 0, startDistance = 0,
                source = ZoneSubProtocol950.playerTag(player.entity.index), target = ZoneSubProtocol950.npcTag(npc.index)
            )
        }.onFailure { logger.warn(it) { "visuals: projectile $spotanim for ${player.name} not queued" } }.getOrDefault(false)
        if (ok) launched++ else refused++
    }

    private fun spellBolt(player: WorldPlayer, npc: WorldNpc, spellStruct: Int) {
        if (loggedSpells.add(spellStruct)) {
            logger.info { "visuals: no projectile graphic for spell struct $spellStruct (${player.name} on npc ${npc.gameId})" }
        }
        refused++
    }
}

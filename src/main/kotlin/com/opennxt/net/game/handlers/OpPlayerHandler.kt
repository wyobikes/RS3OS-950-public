package com.opennxt.net.game.handlers

import com.opennxt.OpenNXT
import com.opennxt.model.world.WorldPlayer
import com.opennxt.net.game.clientprot.OpPlayer
import com.opennxt.net.game.pipeline.GamePacketHandler
import mu.KotlinLogging

object OpPlayerHandler : GamePacketHandler<WorldPlayer, OpPlayer> {
    private val logger = KotlinLogging.logger { }

    internal fun playerAtIndex(index: Int): WorldPlayer? {
        val world = runCatching { OpenNXT.world }.getOrNull() ?: return null
        return world.getPlayer(index)?.controllingPlayer
    }

    override fun handle(context: WorldPlayer, packet: OpPlayer) {
        val target = playerAtIndex(packet.index)

        if (target == null) {
            logger.warn {
                "OPPLAYER${packet.option} ${context.name} clicked player index ${packet.index}, which is not in use"
            }
            return
        }

        val loc = target.entity.location
        logger.info {
            "OPPLAYER${packet.option} ${context.name} clicked player '${target.name}' (slot ${packet.index}) " +
                "at (${loc.x},${loc.y},plane ${loc.plane}) option ${packet.option}" +
                (if (packet.ctrlHeld) " ctrl-held" else "")
        }

        if (target === context) {
            logger.info {
                "OPPLAYER${packet.option}: ${context.name} clicked themselves, ignoring"
            }
            return
        }

        MoveGameClickHandler.walk(context, loc.x, loc.y, "OPPLAYER${packet.option}")
    }
}

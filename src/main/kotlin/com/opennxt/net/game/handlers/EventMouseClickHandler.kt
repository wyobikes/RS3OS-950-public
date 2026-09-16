package com.opennxt.net.game.handlers

import com.opennxt.model.entity.BasePlayer
import com.opennxt.net.game.clientprot.EventMouseClick
import com.opennxt.net.game.pipeline.GamePacketHandler
import mu.KotlinLogging

object EventMouseClickHandler : GamePacketHandler<BasePlayer, EventMouseClick> {
    private val logger = KotlinLogging.logger { }

    private const val LOG_EVERY = 50L

    override fun handle(context: BasePlayer, packet: EventMouseClick) {
        val state = ClientEventState.of(context)
        state.mouseX = packet.x
        state.mouseY = packet.y
        state.mouseNotLeftButton = packet.notLeftButton
        state.mousePackets++

        val n = state.mousePackets
        if (n == 1L || n % LOG_EVERY == 0L) {
            logger.info {
                "EVENT_MOUSE_CLICK ${context.name}: (${packet.x},${packet.y}) client px, " +
                    "button=${if (packet.notLeftButton) "NOT-LEFT" else "left"}, " +
                    "dt=${packet.deltaMs}ms${if (packet.deltaSaturated) " (saturated)" else ""} " +
                    "(packet #$n; position only - nothing acts on it)"
            }
        }
    }
}

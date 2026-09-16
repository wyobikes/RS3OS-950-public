package com.opennxt.net.game.handlers

import com.opennxt.model.entity.BasePlayer
import com.opennxt.net.game.clientprot.EventAppletFocus
import com.opennxt.net.game.pipeline.GamePacketHandler
import mu.KotlinLogging

object EventAppletFocusHandler : GamePacketHandler<BasePlayer, EventAppletFocus> {
    private val logger = KotlinLogging.logger { }

    override fun handle(context: BasePlayer, packet: EventAppletFocus) {
        val state = ClientEventState.of(context)
        val changed = state.focused != packet.focused
        state.focused = packet.focused

        if (changed) {
            logger.info {
                "EVENT_APPLET_FOCUS ${context.name}: window ${if (packet.hasFocus) "GAINED" else "LOST"} " +
                    "keyboard focus (raw ${packet.focused})"
            }
        }
    }
}

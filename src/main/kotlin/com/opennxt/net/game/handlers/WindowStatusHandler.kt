package com.opennxt.net.game.handlers

import com.opennxt.model.entity.BasePlayer
import com.opennxt.net.game.clientprot.WindowStatus
import com.opennxt.net.game.pipeline.GamePacketHandler
import mu.KotlinLogging

object WindowStatusHandler : GamePacketHandler<BasePlayer, WindowStatus> {
    private val logger = KotlinLogging.logger { }

    override fun handle(context: BasePlayer, packet: WindowStatus) {
        val changed = context.windowMode != packet.mode ||
            context.windowWidth != packet.width ||
            context.windowHeight != packet.height

        context.windowMode = packet.mode
        context.windowWidth = packet.width
        context.windowHeight = packet.height

        if (changed) {
            logger.info {
                "WINDOW_STATUS ${context.name}: mode=${packet.mode} " +
                    "${packet.width}x${packet.height} (trailing=${packet.trailing})"
            }
        }
    }
}

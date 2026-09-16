package com.opennxt.net.game.handlers

import com.opennxt.model.entity.BasePlayer
import com.opennxt.net.game.clientprot.MessagePrivate
import com.opennxt.net.game.pipeline.GamePacketHandler
import mu.KotlinLogging

object MessagePrivateHandler : GamePacketHandler<BasePlayer, MessagePrivate> {
    private val logger = KotlinLogging.logger { }

    var attempts = 0
        private set

    fun resetCounters() {
        attempts = 0
    }

    override fun handle(context: BasePlayer, packet: MessagePrivate) {
        attempts++
        if (!PublicChat.enabled) {
            logger.debug { "chat is disabled (-Dopennxt.experiment.chat=false); dropping $packet" }
            return
        }

        logger.info {
            "${context.name} -> ${packet.target} (private): \"${packet.text}\" not delivered; private chat is not implemented"
        }
        context.console("Private messaging is not implemented on this server; '${packet.target}' was not notified.")
    }
}

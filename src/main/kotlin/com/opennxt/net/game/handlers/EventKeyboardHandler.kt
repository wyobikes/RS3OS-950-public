package com.opennxt.net.game.handlers

import com.opennxt.model.world.WorldPlayer
import com.opennxt.net.game.clientprot.EventKeyboard
import com.opennxt.net.game.pipeline.GamePacketHandler
import mu.KotlinLogging

object EventKeyboardHandler : GamePacketHandler<WorldPlayer, EventKeyboard> {
    private val logger = KotlinLogging.logger { }

    override fun handle(context: WorldPlayer, packet: EventKeyboard) {
        logger.debug { "EVENT_KEYBOARD ${context.name}: $packet" }

        if (packet.isEscape) {
            logger.info { "EVENT_KEYBOARD ${context.name}: ESC pressed, closing modals" }

            val closedAny = context.interfaces.closeModals()
            
            if (!closedAny) {
                logger.info { "EVENT_KEYBOARD ${context.name}: No modals were open, opening Settings (1433)" }
                context.interfaces.open(com.opennxt.content.interfaces.InterfaceSlot.CENTRAL_INTERFACE, 1433, walkable = false)
            }
        }
    }
}

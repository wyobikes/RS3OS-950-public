package com.opennxt.net.game.handlers

import com.opennxt.model.entity.BasePlayer
import com.opennxt.net.game.clientprot.EventCameraPosition
import com.opennxt.net.game.pipeline.GamePacketHandler
import mu.KotlinLogging

object EventCameraPositionHandler : GamePacketHandler<BasePlayer, EventCameraPosition> {
    private val logger = KotlinLogging.logger { }

    private const val LOG_EVERY = 512L

    override fun handle(context: BasePlayer, packet: EventCameraPosition) {
        val state = ClientEventState.of(context)
        state.cameraAngle1 = packet.angle1
        state.cameraAngle2 = packet.angle2
        state.cameraPackets++

        val n = state.cameraPackets
        if (n == 1L || n % LOG_EVERY == 0L) {
            logger.info {
                "EVENT_CAMERA_POSITION ${context.name}: angle1=${packet.angle1} angle2=${packet.angle2} " +
                    "(packet #$n; angles are 11-bit, yaw/pitch order unknown)"
            }
        }
    }
}

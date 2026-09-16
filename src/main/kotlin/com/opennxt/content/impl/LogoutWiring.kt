package com.opennxt.content.impl

import com.opennxt.model.world.WorldPlayer
import com.opennxt.net.game.clientprot.IfButtonN
import com.opennxt.net.game.serverprot.generated.LogoutFull
import io.netty.channel.ChannelFutureListener
import mu.KotlinLogging

object LogoutWiring {
    private val logger = KotlinLogging.logger { }

    const val OPTIONS_MENU = 1433

    const val LOGOUT_CONFIRM = 86

    const val REASON_DEFAULT = 1

    fun handleButton(player: WorldPlayer, packet: IfButtonN): Boolean {
        if (packet.interfaceId != OPTIONS_MENU || packet.component != LOGOUT_CONFIRM) return false
        val channel = player.client.channel
        logger.info { "logout: ${player.name} confirmed logout on $OPTIONS_MENU:$LOGOUT_CONFIRM - sending LOGOUT_FULL($REASON_DEFAULT) and closing after the flush" }
        player.client.write(LogoutFull(REASON_DEFAULT))
        channel.writeAndFlush(io.netty.buffer.Unpooled.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE)
        return true
    }
}

package com.opennxt.net.game.pipeline

import com.opennxt.net.RSChannelAttributes
import com.opennxt.net.Side
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.SimpleChannelInboundHandler
import mu.KotlinLogging
import java.util.*

class DynamicPacketHandler : SimpleChannelInboundHandler<OpcodeWithBuffer>() {
    private val logger = KotlinLogging.logger { }

    override fun handlerAdded(ctx: ChannelHandlerContext) {
        InboundCensus.begin(ctx.channel())
        super.handlerAdded(ctx)
    }

    override fun channelRead0(ctx: ChannelHandlerContext, msg: OpcodeWithBuffer) {
        try {
            InboundCensus.frame(
                ctx.channel(), ctx.channel().attr(RSChannelAttributes.SIDE).get(), msg.opcode, msg.buf
            )
            ctx.channel().attr(RSChannelAttributes.CONNECTED_CLIENT).get().receive(msg)
        } catch (e: Exception) {
            logger.error(e) {
                "Failed to hand opcode ${msg.opcode} to the connected client on " +
                    "${ctx.channel().remoteAddress()} (side=${ctx.channel().attr(RSChannelAttributes.SIDE).get()}, " +
                    "connectedClient present=${ctx.channel().attr(RSChannelAttributes.CONNECTED_CLIENT).get() != null})"
            }
        }
    }

    override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
        logger.error(cause) { "Exception caught in packet handler" }
    }

    override fun channelInactive(ctx: ChannelHandlerContext) {
        InboundCensus.summarise(ctx.channel(), ctx.channel().attr(RSChannelAttributes.SIDE).get())

        logger.info { "Channel on side ${ctx.channel().attr(RSChannelAttributes.SIDE).get()} went inactive" }

        val passthrough = ctx.channel().attr(RSChannelAttributes.PASSTHROUGH_CHANNEL).get()
        if (passthrough != null && passthrough.isOpen) {
            passthrough.close()
        }
    }
}

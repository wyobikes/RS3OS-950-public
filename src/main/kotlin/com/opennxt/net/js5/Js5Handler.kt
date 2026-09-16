package com.opennxt.net.js5

import com.opennxt.Js5Thread
import com.opennxt.OpenNXT
import com.opennxt.net.DiagnosticLog
import com.opennxt.net.js5.packet.Js5Packet
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.SimpleChannelInboundHandler
import mu.KotlinLogging

class Js5Handler(val session: Js5Session): SimpleChannelInboundHandler<Js5Packet>() {
    private val logger = KotlinLogging.logger {  }

    var handledHandshake = false

    override fun channelRead0(ctx: ChannelHandlerContext, msg: Js5Packet) {
        when(msg) {
            is Js5Packet.Handshake -> {
                if (handledHandshake)
                    throw IllegalStateException("Already handled handshake")
                handledHandshake = true

                logger.warn { "Accepting js5 handshake without checking build or token" }

                if (DiagnosticLog.enabled) {
                    DiagnosticLog.note(
                        ctx.channel(), DiagnosticLog.Stage.JS5,
                        "HANDSHAKE major=${msg.major} minor=${msg.minor} token='${msg.token}' language=${msg.language}"
                    )
                    DiagnosticLog.buildAnnounced(
                        ctx.channel(), DiagnosticLog.Stage.JS5, msg.major,
                        "js5 handshake, minor=${msg.minor}, token='${msg.token}'"
                    )
                }

                ctx.channel().write(Js5Packet.HandshakeResponse(0))

                val prefetchCount = System.getProperty("opennxt.js5.prefetches")?.toIntOrNull() ?: 0
                if (prefetchCount > 0) {
                    val available = OpenNXT.prefetches.entries
                    ctx.channel().write(Js5Packet.Prefetches(available.copyOf(minOf(prefetchCount, available.size))))
                }
                DiagnosticLog.note(
                    ctx.channel(), DiagnosticLog.Stage.JS5,
                    "sent handshake response 0; prefetches=$prefetchCount"
                )
                ctx.channel().flush()
            }
            else -> {
                DiagnosticLog.note(
                    ctx.channel(), DiagnosticLog.Stage.JS5,
                    "unhandled js5 packet: $msg"
                )
                TODO("Encode $msg")
            }
        }
    }

    override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
        logger.warn(cause) { "Caught exception, closing js5 connection from ${ctx.channel().remoteAddress()}" }
        DiagnosticLog.reason(ctx.channel(), "js5 handler exception: ${cause.javaClass.name}: ${cause.message}")

        ctx.close()
    }

    override fun channelInactive(ctx: ChannelHandlerContext) {
        Js5Thread.removeSession(session)

        ctx.fireChannelInactive()
    }
}

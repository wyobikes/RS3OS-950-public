package com.opennxt.net.js5

import com.opennxt.filesystem.Container
import com.opennxt.net.DiagnosticLog
import com.opennxt.net.js5.packet.Js5Packet
import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import java.nio.ByteBuffer
import io.netty.channel.ChannelHandlerContext
import io.netty.handler.codec.MessageToByteEncoder
import mu.KotlinLogging
import kotlin.math.min

class Js5Encoder(val session: Js5Session) : MessageToByteEncoder<Js5Packet>() {
    private val logger = KotlinLogging.logger { }

    override fun encode(ctx: ChannelHandlerContext, msg: Js5Packet, out: ByteBuf) {
        when (msg) {
            is Js5Packet.HandshakeResponse -> out.writeByte(msg.code)
            is Js5Packet.Prefetches -> for (value in msg.prefetches) out.writeInt(value)
            is Js5Packet.RequestFileResponse -> {
                val xor = session.channel.attr(Js5Session.XOR_KEY).get()

                val data = reframeForClient(msg.data)

                if (data.readableBytes() < 5) {
                    logger.error {
                        "js5 container [${msg.index}, ${msg.archive}] is ${data.readableBytes()} " +
                            "byte(s), shorter than its header; not served"
                    }
                    DiagnosticLog.note(
                        session.channel, DiagnosticLog.Stage.JS5,
                        "malformed container index=${msg.index} archive=${msg.archive} " +
                            "size=${data.readableBytes()} (< 5-byte header); not served"
                    )
                    return
                }

                var length: Int
                if (data.getByte(0).toInt() == 0x5A && data.getByte(1).toInt() == 0x4C &&
                    data.getByte(2).toInt() == 0x42 && data.getByte(3).toInt() == 0x01
                ) {
                    length = data.readableBytes()
                } else {
                    length = ((data.getByte(1).toInt() and 0xff) shl 24) + ((data.getByte(2)
                        .toInt() and 0xff) shl 16) + ((data.getByte(3).toInt() and 0xff) shl 8) + (data.getByte(4)
                        .toInt() and 0xff) + 5
                    if (data.getByte(0).toInt() != 0) length += 4
                }

                if (length < 0 || length > data.readableBytes()) {
                    logger.error {
                        "js5 container [${msg.index}, ${msg.archive}] declares $length byte(s) but has " +
                            "${data.readableBytes()} (compression ${data.getByte(0).toInt() and 0xff}); not served"
                    }
                    DiagnosticLog.note(
                        session.channel, DiagnosticLog.Stage.JS5,
                        "malformed container index=${msg.index} archive=${msg.archive} declaredLength=$length " +
                            "stored=${data.readableBytes()} compression=${data.getByte(0).toInt() and 0xff}; not served"
                    )
                    return
                }

                var remaining = length
                while (data.isReadable && remaining > 0) {
                    out.writeByte(msg.index xor xor)

                    val size = if (msg.priority) msg.archive else (msg.archive or -0x80000000)
                    out.writeByte((size shr 24) xor xor)
                    out.writeByte((size shr 16) xor xor)
                    out.writeByte((size shr 8) xor xor)
                    out.writeByte((size) xor xor)

                    for (i in 0 until min(102400 - 5, remaining)) {
                        out.writeByte(data.readByte().toInt() xor xor)
                        remaining--
                    }
                }

                if (remaining != 0) {
                    logger.error { "remaining != 0! ${remaining}, ${data.readableBytes()} in [${msg.index}, ${msg.archive}]" }
                }
            }
            else -> logger.warn { "I don't know how to encode $msg!" }
        }
    }

    private fun reframeForClient(data: ByteBuf): ByteBuf {
        if (data.readableBytes() < 4) return data
        if (!(data.getByte(0).toInt() == 0x5A && data.getByte(1).toInt() == 0x4C &&
                data.getByte(2).toInt() == 0x42 && data.getByte(3).toInt() == 0x01)
        ) return data

        val raw = ByteArray(data.readableBytes())
        data.getBytes(data.readerIndex(), raw)

        val wrapped = ByteBuffer.wrap(raw)
        val reframed = Container.reframeForClient(wrapped)
        if (reframed === wrapped) return data

        bump()
        val body = ByteArray(reframed.remaining())
        reframed.get(body)
        val out = Unpooled.buffer(body.size)
        out.writeBytes(body)
        return out
    }

    companion object {
        private val zlbReframedCount = java.util.concurrent.atomic.AtomicInteger(0)

        @JvmStatic
        val zlbReframed: Int
            get() = zlbReframedCount.get()

        private fun bump() { zlbReframedCount.incrementAndGet() }
    }
}

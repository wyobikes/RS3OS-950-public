package com.opennxt.net.game.pipeline

import com.opennxt.OpenNXT
import com.opennxt.ext.isBigOpcode
import com.opennxt.ext.readOpcode
import com.opennxt.net.DiagnosticLog
import com.opennxt.net.RSChannelAttributes
import com.opennxt.net.Side
import com.opennxt.util.ISAACCipher
import io.netty.buffer.ByteBuf
import io.netty.channel.Channel
import io.netty.channel.ChannelHandlerContext
import io.netty.handler.codec.ByteToMessageDecoder
import it.unimi.dsi.fastutil.ints.Int2IntMap
import mu.KotlinLogging

class GamePacketFraming : ByteToMessageDecoder() {
    private val logger = KotlinLogging.logger { }

    private val protocol = OpenNXT.protocol

    private var inited = false
    private lateinit var isaac: ISAACCipher
    private lateinit var mapping: Int2IntMap
    private lateinit var side: Side

    private var state = State.READ_OPCODE
    private var opcode = -1
    private var size = -1

    private fun init(channel: Channel) {
        val incomingIsaac = channel.attr(RSChannelAttributes.INCOMING_ISAAC).get()
            ?: throw IllegalStateException(
                "INCOMING_ISAAC is not set on ${channel.remoteAddress()}: the game framer was installed before " +
                    "the login handler seeded the cipher"
            )
        val channelSide = channel.attr(RSChannelAttributes.SIDE).get()
            ?: throw IllegalStateException("SIDE is not set on ${channel.remoteAddress()}")

        isaac = incomingIsaac
        side = channelSide
        mapping = if (side == Side.CLIENT) protocol.clientProtSizes.values else protocol.serverProtSizes.values

        inited = true
    }

    override fun decode(ctx: ChannelHandlerContext, buf: ByteBuf, out: MutableList<Any>) {
        try {
            if (!inited) init(ctx.channel())

            while (buf.isReadable) {
                if (state == State.READ_OPCODE) {
                    if (!buf.isReadable) return

                    val useEscape = side != Side.CLIENT ||
                        System.getProperty("opennxt.prot.clientBigOpcode") == "true"

                    if (useEscape) {
                        if (buf.readableBytes() < 2 && buf.isBigOpcode(isaac)) {
                            logger.info { "is big opcode:  true, readable is 1, need to wait!" }
                            return
                        }
                        opcode = buf.readOpcode(isaac)
                    } else {
                        opcode = buf.readByte().toInt() - isaac.nextValue and 0xff
                    }
                    if (!mapping.containsKey(opcode)) {
                        InboundCensus.unframeable(ctx.channel(), side, opcode)
                        logger.error { "No opcode->size mapping for opcode $opcode (side=$side)" }
                        buf.skipBytes(buf.readableBytes())
                        ctx.channel().close()
                        return
                    }

                    size = mapping[opcode]
                    state = if (size >= 0) State.READ_BODY else State.READ_SIZE
                }

                if (state == State.READ_SIZE && size < 0) {
                    if (buf.readableBytes() < -size) return

                    size = if (size == -1) buf.readUnsignedByte().toInt() else buf.readUnsignedShort()

                    state = State.READ_BODY
                }

                if (state == State.READ_BODY) {
                    if (buf.readableBytes() < size) return

                    val payload = buf.readBytes(size)

                    out.add(OpcodeWithBuffer(opcode, payload))

                    state = State.READ_OPCODE
                }
            }
        } catch (e: Exception) {
            logger.error(e) {
                "Game framing failed on ${ctx.channel().remoteAddress()} " +
                    "(state=$state opcode=$opcode size=$size side=${if (inited) side.toString() else "?"}) " +
                    "- closing; the frame stream cannot be resynchronised"
            }
            DiagnosticLog.reason(
                ctx.channel(),
                "game framing exception in state=$state opcode=$opcode size=$size: " +
                    "${e.javaClass.name}: ${e.message}"
            )
            InboundCensus.framingFault(
                ctx.channel(),
                "framing exception in state=$state opcode=$opcode size=$size: ${e.javaClass.simpleName}"
            )
            buf.skipBytes(buf.readableBytes())
            ctx.close()
        }
    }

    private enum class State {
        READ_OPCODE,
        READ_SIZE,
        READ_BODY
    }
}

package com.opennxt.net.game.pipeline

import com.opennxt.OpenNXT
import com.opennxt.ext.writeOpcode
import com.opennxt.net.DiagnosticLog
import com.opennxt.net.RSChannelAttributes
import com.opennxt.net.Side
import com.opennxt.util.ISAACCipher
import io.netty.buffer.ByteBuf
import io.netty.channel.Channel
import io.netty.channel.ChannelHandlerContext
import io.netty.handler.codec.MessageToByteEncoder
import it.unimi.dsi.fastutil.ints.Int2IntMap
import mu.KotlinLogging

class GamePacketEncoder : MessageToByteEncoder<OpcodeWithBuffer>() {
    private val logger = KotlinLogging.logger { }

    private val protocol = OpenNXT.protocol

    private var inited = false

    private lateinit var isaac: ISAACCipher
    private lateinit var mapping: Int2IntMap
    private lateinit var side: Side
    private lateinit var names: it.unimi.dsi.fastutil.ints.Int2ObjectMap<String>

    private fun init(channel: Channel) {
        inited = true

        isaac = channel.attr(RSChannelAttributes.OUTGOING_ISAAC).get()
        side = channel.attr(RSChannelAttributes.SIDE).get()
        mapping = if (side == Side.CLIENT) protocol.serverProtSizes.values else protocol.clientProtSizes.values
        names = if (side == Side.CLIENT) protocol.serverProtNames.reversedValues()
                else protocol.clientProtNames.reversedValues()

        try {
            if (suppressed.isNotEmpty() && side == Side.CLIENT) {
                val table = protocol.serverProtNames.values
                val resolved = suppressed.filter { table.containsKey(it) }.map { "$it=${table.getInt(it)}" }
                val unresolved = suppressed.filter { !table.containsKey(it) }
                logger.warn {
                    "Packet suppression enabled for ${resolved.size} packet type(s): " +
                        resolved.joinToString(", ")
                }
                if (unresolved.isNotEmpty())
                    logger.error {
                        "Suppressed packet names not in build ${protocol.effectiveBuild}'s table: " +
                            unresolved.joinToString(", ")
                    }
            }
        } catch (t: Throwable) {
            logger.warn(t) { "Could not report the packet suppression set" }
        }
    }

    private val suppressed: Set<String> =
        (System.getProperty("opennxt.experiment.suppress") ?: "")
            .split(',').map { it.trim().uppercase() }.filter { it.isNotEmpty() }.toSet()

    private var suppressedCount = 0

    override fun encode(ctx: ChannelHandlerContext, msg: OpcodeWithBuffer, out: ByteBuf) {
        val buffer = msg.buf
        try {
            if (!inited) init(ctx.channel())

            if (suppressed.isNotEmpty()) {
                val name = names[msg.opcode]
                if (name != null && name.uppercase() in suppressed) {
                    suppressedCount++
                    if (suppressedCount <= 3 || suppressedCount % 250 == 0)
                        logger.warn { "Suppressed $name (opcode ${msg.opcode}), $suppressedCount so far" }
                    return
                }
            }

            if (!mapping.containsKey(msg.opcode)) {
                logger.error { "No opcode->size mapping for opcode ${msg.opcode} (side=$side)" }
                ctx.channel().close()
                return
            }

            val length = buffer.readableBytes()
            val size = mapping[msg.opcode]

            if (size == -1 && length > 255)
                throw IllegalStateException("Var byte packet exceeds 255 bytes: ${msg.opcode} is $length bytes")
            else if (size == -2 && length > 65535)
                throw IllegalStateException("Var short packet exceeds 65535 bytes: ${msg.opcode} is $length bytes")
            else if (size >= 0 && size != length)
                throw IllegalStateException("Encoded buffer size does not match expected size (expected: ${size}, got $length) opcode ${msg.opcode}")

            val name = names[msg.opcode]
            val head = if (length >= 4 && DiagnosticLog.wantsHead(ctx.channel(), name))
                ByteArray(4).also { buffer.getBytes(buffer.readerIndex(), it) } else null
            DiagnosticLog.sendingPacket(ctx.channel(), side, msg.opcode, length, size, name, head)

            synchronized(isaac) {
                out.writeOpcode(isaac, msg.opcode)
                if (size == -1) out.writeByte(length)
                else if (size == -2) out.writeShort(length)
                out.writeBytes(buffer)
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to encode opcode ${msg.opcode} on side $side to ${ctx.channel().remoteAddress()}" }
            ctx.channel().close()
        } finally {
            if (buffer.refCnt() > 0) buffer.release()
        }
    }
}

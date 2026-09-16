package com.opennxt.net.game.clientprot

import com.opennxt.net.buf.GamePacketBuilder
import com.opennxt.net.buf.GamePacketReader
import com.opennxt.net.game.GamePacket
import com.opennxt.net.game.pipeline.GamePacketCodec

class UndecodedClientPacket(val opcode: Int, val payload: ByteArray) : GamePacket {
    fun hex(limit: Int = 32): String {
        val shown = minOf(limit, payload.size)
        val sb = StringBuilder(shown * 3 + 16)
        for (i in 0 until shown) {
            if (i > 0) sb.append(' ')
            sb.append(((payload[i].toInt() and 0xff) + 0x100).toString(16).substring(1))
        }
        if (payload.size > shown) sb.append(" ... (${payload.size - shown} more)")
        return sb.toString()
    }

    fun u16(index: Int): Int =
        if (index + 1 >= payload.size) -1
        else ((payload[index].toInt() and 0xff) shl 8) or (payload[index + 1].toInt() and 0xff)

    fun u8(index: Int): Int = if (index >= payload.size) -1 else payload[index].toInt() and 0xff

    override fun toString(): String = "UndecodedClientPacket(op=$opcode, ${payload.size} bytes: ${hex()})"

    class Codec(private val opcode: Int) : GamePacketCodec<UndecodedClientPacket> {
        override fun decode(buf: GamePacketReader): UndecodedClientPacket {
            val bytes = ByteArray(buf.buffer.readableBytes())
            buf.getBytes(bytes)
            return UndecodedClientPacket(opcode, bytes)
        }

        override fun encode(packet: UndecodedClientPacket, buf: GamePacketBuilder) {
            buf.putBytes(packet.payload)
        }
    }

    companion object {
        val OPCODES = intArrayOf(4, 67, 98, 113, 146)
    }
}

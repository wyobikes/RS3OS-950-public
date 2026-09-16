package com.opennxt.net.game.clientprot

import com.opennxt.net.buf.GamePacketBuilder
import com.opennxt.net.buf.GamePacketReader
import com.opennxt.net.game.GamePacket
import com.opennxt.net.game.pipeline.GamePacketCodec

class EventKeyboard(val payload: ByteArray) : GamePacket {
    val keyCode: Int get() = if (payload.isNotEmpty()) payload[0].toInt() and 0xff else -1

    val isEscape: Boolean get() = keyCode == 0x1B

    fun hex(limit: Int = 32): String {
        val shown = minOf(limit, payload.size)
        val sb = StringBuilder(shown * 3)
        for (i in 0 until shown) {
            if (i > 0) sb.append(' ')
            sb.append(((payload[i].toInt() and 0xff) + 0x100).toString(16).substring(1))
        }
        if (payload.size > shown) sb.append(" ... (${payload.size - shown} more)")
        return sb.toString()
    }

    override fun toString(): String =
        "EVENT_KEYBOARD(keyCode=$keyCode${if (isEscape) " ESC" else ""}, ${payload.size} bytes: ${hex()})"

    class Codec : GamePacketCodec<EventKeyboard> {
        override fun decode(buf: GamePacketReader): EventKeyboard {
            val bytes = ByteArray(buf.buffer.readableBytes())
            buf.getBytes(bytes)
            return EventKeyboard(bytes)
        }

        override fun encode(packet: EventKeyboard, buf: GamePacketBuilder) {
            buf.putBytes(packet.payload)
        }
    }
}

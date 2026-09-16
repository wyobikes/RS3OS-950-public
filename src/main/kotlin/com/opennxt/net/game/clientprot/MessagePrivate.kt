package com.opennxt.net.game.clientprot

import com.opennxt.net.buf.GamePacketBuilder
import com.opennxt.net.buf.GamePacketReader
import com.opennxt.net.game.GamePacket
import com.opennxt.net.game.pipeline.GamePacketCodec
import com.opennxt.util.HuffmanCodec

data class MessagePrivate(
    val target: String,
    val text: String
) : GamePacket {
    override fun toString(): String = "MESSAGE_PRIVATE(target=\"$target\", text=\"$text\")"

    object Codec : GamePacketCodec<MessagePrivate> {
        override fun encode(packet: MessagePrivate, buf: GamePacketBuilder) {
            val huffman = HuffmanCodec.instance()
                ?: throw IllegalStateException(
                    "cannot encode MESSAGE_PRIVATE without the huffman table (js5[10, 'huffman'])"
                )
            buf.putString(packet.target)
            huffman.write(buf, packet.text)
        }

        override fun decode(buf: GamePacketReader): MessagePrivate {
            val target = buf.getString()
            val huffman = HuffmanCodec.instance()
                ?: throw IllegalStateException(
                    "MESSAGE_PRIVATE arrived but this server has no huffman table (js5[10, 'huffman'])"
                )
            return MessagePrivate(target, huffman.readFrom(buf))
        }
    }
}

package com.opennxt.net.game.clientprot.generated

import com.opennxt.net.game.GamePacket
import com.opennxt.net.game.pipeline.DynamicGamePacketCodec
import com.opennxt.net.game.protocol.PacketFieldDeclaration

data class SendPingReply(val a: Int, val value: Int, val b: Int) : GamePacket {
    class Codec(fields: Array<PacketFieldDeclaration>) : DynamicGamePacketCodec<SendPingReply>(fields) {
        override fun fromMap(packet: Map<String, Any>): SendPingReply = SendPingReply(packet["a"] as Int, packet["value"] as Int, packet["b"] as Int)
        override fun toMap(packet: SendPingReply): Map<String, Any> = mapOf("a" to packet.a, "value" to packet.value, "b" to packet.b)
    }
}

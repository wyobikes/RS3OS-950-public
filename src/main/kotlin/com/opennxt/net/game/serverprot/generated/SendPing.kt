package com.opennxt.net.game.serverprot.generated

import com.opennxt.net.game.GamePacket
import com.opennxt.net.game.pipeline.DynamicGamePacketCodec
import com.opennxt.net.game.protocol.PacketFieldDeclaration

data class SendPing(val a: Int, val b: Int) : GamePacket {
    class Codec(fields: Array<PacketFieldDeclaration>) : DynamicGamePacketCodec<SendPing>(fields) {
        override fun fromMap(packet: Map<String, Any>): SendPing = SendPing(packet["a"] as Int, packet["b"] as Int)
        override fun toMap(packet: SendPing): Map<String, Any> = mapOf("a" to packet.a, "b" to packet.b)
    }
}

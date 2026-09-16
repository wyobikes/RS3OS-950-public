package com.opennxt.net.game.serverprot.generated

import com.opennxt.net.game.GamePacket
import com.opennxt.net.game.pipeline.DynamicGamePacketCodec
import com.opennxt.net.game.protocol.PacketFieldDeclaration

data class CreateCheckNameReply(val code: Int) : GamePacket {
    class Codec(fields: Array<PacketFieldDeclaration>) : DynamicGamePacketCodec<CreateCheckNameReply>(fields) {
        override fun fromMap(packet: Map<String, Any>): CreateCheckNameReply = CreateCheckNameReply(packet["code"] as Int)
        override fun toMap(packet: CreateCheckNameReply): Map<String, Any> = mapOf("code" to packet.code)
    }
}

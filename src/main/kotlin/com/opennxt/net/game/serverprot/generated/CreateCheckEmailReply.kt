package com.opennxt.net.game.serverprot.generated

import com.opennxt.net.game.GamePacket
import com.opennxt.net.game.pipeline.DynamicGamePacketCodec
import com.opennxt.net.game.protocol.PacketFieldDeclaration

data class CreateCheckEmailReply(val code: Int) : GamePacket {
    class Codec(fields: Array<PacketFieldDeclaration>) : DynamicGamePacketCodec<CreateCheckEmailReply>(fields) {
        override fun fromMap(packet: Map<String, Any>): CreateCheckEmailReply = CreateCheckEmailReply(packet["code"] as Int)
        override fun toMap(packet: CreateCheckEmailReply): Map<String, Any> = mapOf("code" to packet.code)
    }
}

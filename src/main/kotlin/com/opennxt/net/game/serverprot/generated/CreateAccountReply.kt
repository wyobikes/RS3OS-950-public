package com.opennxt.net.game.serverprot.generated

import com.opennxt.net.game.GamePacket
import com.opennxt.net.game.pipeline.DynamicGamePacketCodec
import com.opennxt.net.game.protocol.PacketFieldDeclaration

data class CreateAccountReply(val code: Int) : GamePacket {
    class Codec(fields: Array<PacketFieldDeclaration>) : DynamicGamePacketCodec<CreateAccountReply>(fields) {
        override fun fromMap(packet: Map<String, Any>): CreateAccountReply = CreateAccountReply(packet["code"] as Int)
        override fun toMap(packet: CreateAccountReply): Map<String, Any> = mapOf("code" to packet.code)
    }
}

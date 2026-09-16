package com.opennxt.net.game.serverprot.generated

import com.opennxt.net.game.GamePacket
import com.opennxt.net.game.pipeline.DynamicGamePacketCodec
import com.opennxt.net.game.protocol.PacketFieldDeclaration

data class CreateSuggestNameReply(val name: String) : GamePacket {
    class Codec(fields: Array<PacketFieldDeclaration>) : DynamicGamePacketCodec<CreateSuggestNameReply>(fields) {
        override fun fromMap(packet: Map<String, Any>): CreateSuggestNameReply = CreateSuggestNameReply(packet["name"] as String)
        override fun toMap(packet: CreateSuggestNameReply): Map<String, Any> = mapOf("name" to packet.name)
    }
}

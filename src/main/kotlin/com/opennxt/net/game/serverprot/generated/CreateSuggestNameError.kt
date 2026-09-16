package com.opennxt.net.game.serverprot.generated

import com.opennxt.net.game.GamePacket
import com.opennxt.net.game.pipeline.DynamicGamePacketCodec
import com.opennxt.net.game.protocol.PacketFieldDeclaration

data class CreateSuggestNameError(val code: Int) : GamePacket {
    class Codec(fields: Array<PacketFieldDeclaration>) : DynamicGamePacketCodec<CreateSuggestNameError>(fields) {
        override fun fromMap(packet: Map<String, Any>): CreateSuggestNameError = CreateSuggestNameError(packet["code"] as Int)
        override fun toMap(packet: CreateSuggestNameError): Map<String, Any> = mapOf("code" to packet.code)
    }
}

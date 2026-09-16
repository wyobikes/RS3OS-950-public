package com.opennxt.net.game.serverprot.generated

import com.opennxt.net.game.GamePacket
import com.opennxt.net.game.pipeline.DynamicGamePacketCodec
import com.opennxt.net.game.protocol.PacketFieldDeclaration

data class ShowFaceHere(val enabled: Int) : GamePacket {
    class Codec(fields: Array<PacketFieldDeclaration>) : DynamicGamePacketCodec<ShowFaceHere>(fields) {
        override fun fromMap(packet: Map<String, Any>): ShowFaceHere = ShowFaceHere(packet["enabled"] as Int)
        override fun toMap(packet: ShowFaceHere): Map<String, Any> = mapOf("enabled" to packet.enabled)
    }
}

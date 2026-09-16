package com.opennxt.net.game.serverprot.generated

import com.opennxt.net.game.GamePacket
import com.opennxt.net.game.pipeline.DynamicGamePacketCodec
import com.opennxt.net.game.protocol.PacketFieldDeclaration

data class SetTarget(val target: Int) : GamePacket {
    class Codec(fields: Array<PacketFieldDeclaration>) : DynamicGamePacketCodec<SetTarget>(fields) {
        override fun fromMap(packet: Map<String, Any>): SetTarget = SetTarget(packet["target"] as Int)
        override fun toMap(packet: SetTarget): Map<String, Any> = mapOf("target" to packet.target)
    }
}

package com.opennxt.net.game.serverprot.generated

import com.opennxt.net.game.GamePacket
import com.opennxt.net.game.pipeline.DynamicGamePacketCodec
import com.opennxt.net.game.protocol.PacketFieldDeclaration

data class UpdateRunweight(val weight: Int) : GamePacket {
    class Codec(fields: Array<PacketFieldDeclaration>) : DynamicGamePacketCodec<UpdateRunweight>(fields) {
        override fun fromMap(packet: Map<String, Any>): UpdateRunweight = UpdateRunweight(packet["weight"] as Int)
        override fun toMap(packet: UpdateRunweight): Map<String, Any> = mapOf("weight" to packet.weight)
    }
}

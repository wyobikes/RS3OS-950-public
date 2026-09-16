package com.opennxt.net.game.serverprot.generated

import com.opennxt.net.game.GamePacket
import com.opennxt.net.game.pipeline.DynamicGamePacketCodec
import com.opennxt.net.game.protocol.PacketFieldDeclaration

data class UpdateRunenergy(val energy: Int) : GamePacket {
    class Codec(fields: Array<PacketFieldDeclaration>) : DynamicGamePacketCodec<UpdateRunenergy>(fields) {
        override fun fromMap(packet: Map<String, Any>): UpdateRunenergy = UpdateRunenergy(packet["energy"] as Int)
        override fun toMap(packet: UpdateRunenergy): Map<String, Any> = mapOf("energy" to packet.energy)
    }
}

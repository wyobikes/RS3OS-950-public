package com.opennxt.net.game.serverprot.generated

import com.opennxt.net.game.GamePacket
import com.opennxt.net.game.pipeline.DynamicGamePacketCodec
import com.opennxt.net.game.protocol.PacketFieldDeclaration

data class UpdateDob(val date: Int, val restricted: Int) : GamePacket {
    class Codec(fields: Array<PacketFieldDeclaration>) : DynamicGamePacketCodec<UpdateDob>(fields) {
        override fun fromMap(packet: Map<String, Any>): UpdateDob = UpdateDob(packet["date"] as Int, packet["restricted"] as Int)
        override fun toMap(packet: UpdateDob): Map<String, Any> = mapOf("date" to packet.date, "restricted" to packet.restricted)
    }
}

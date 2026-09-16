package com.opennxt.net.game.serverprot.generated

import com.opennxt.net.game.GamePacket
import com.opennxt.net.game.pipeline.DynamicGamePacketCodec
import com.opennxt.net.game.protocol.PacketFieldDeclaration

data class UpdateRebootTimer(val ticks: Int) : GamePacket {
    class Codec(fields: Array<PacketFieldDeclaration>) : DynamicGamePacketCodec<UpdateRebootTimer>(fields) {
        override fun fromMap(packet: Map<String, Any>): UpdateRebootTimer = UpdateRebootTimer(packet["ticks"] as Int)
        override fun toMap(packet: UpdateRebootTimer): Map<String, Any> = mapOf("ticks" to packet.ticks)
    }
}

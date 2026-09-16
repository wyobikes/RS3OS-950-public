package com.opennxt.net.game.serverprot.generated

import com.opennxt.net.game.GamePacket
import com.opennxt.net.game.pipeline.DynamicGamePacketCodec
import com.opennxt.net.game.protocol.PacketFieldDeclaration

data class TelemetryGridRemoveGroup(val table: Int) : GamePacket {
    class Codec(fields: Array<PacketFieldDeclaration>) : DynamicGamePacketCodec<TelemetryGridRemoveGroup>(fields) {
        override fun fromMap(packet: Map<String, Any>): TelemetryGridRemoveGroup = TelemetryGridRemoveGroup(packet["table"] as Int)
        override fun toMap(packet: TelemetryGridRemoveGroup): Map<String, Any> = mapOf("table" to packet.table)
    }
}

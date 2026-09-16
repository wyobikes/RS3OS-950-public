package com.opennxt.net.game.serverprot.generated

import com.opennxt.net.game.GamePacket
import com.opennxt.net.game.pipeline.DynamicGamePacketCodec
import com.opennxt.net.game.protocol.PacketFieldDeclaration

data class TelemetryGridRemoveColumn(val table: Int, val column: Int) : GamePacket {
    class Codec(fields: Array<PacketFieldDeclaration>) : DynamicGamePacketCodec<TelemetryGridRemoveColumn>(fields) {
        override fun fromMap(packet: Map<String, Any>): TelemetryGridRemoveColumn = TelemetryGridRemoveColumn(packet["table"] as Int, packet["column"] as Int)
        override fun toMap(packet: TelemetryGridRemoveColumn): Map<String, Any> = mapOf("table" to packet.table, "column" to packet.column)
    }
}

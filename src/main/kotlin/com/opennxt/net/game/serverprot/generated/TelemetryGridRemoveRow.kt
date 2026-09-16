package com.opennxt.net.game.serverprot.generated

import com.opennxt.net.game.GamePacket
import com.opennxt.net.game.pipeline.DynamicGamePacketCodec
import com.opennxt.net.game.protocol.PacketFieldDeclaration

data class TelemetryGridRemoveRow(val row: Int, val table: Int) : GamePacket {
    class Codec(fields: Array<PacketFieldDeclaration>) : DynamicGamePacketCodec<TelemetryGridRemoveRow>(fields) {
        override fun fromMap(packet: Map<String, Any>): TelemetryGridRemoveRow = TelemetryGridRemoveRow(packet["row"] as Int, packet["table"] as Int)
        override fun toMap(packet: TelemetryGridRemoveRow): Map<String, Any> = mapOf("row" to packet.row, "table" to packet.table)
    }
}

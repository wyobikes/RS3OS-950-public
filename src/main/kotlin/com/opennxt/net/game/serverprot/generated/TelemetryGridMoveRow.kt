package com.opennxt.net.game.serverprot.generated

import com.opennxt.net.game.GamePacket
import com.opennxt.net.game.pipeline.DynamicGamePacketCodec
import com.opennxt.net.game.protocol.PacketFieldDeclaration

data class TelemetryGridMoveRow(val from: Int, val table: Int, val to: Int) : GamePacket {
    class Codec(fields: Array<PacketFieldDeclaration>) : DynamicGamePacketCodec<TelemetryGridMoveRow>(fields) {
        override fun fromMap(packet: Map<String, Any>): TelemetryGridMoveRow = TelemetryGridMoveRow(packet["from"] as Int, packet["table"] as Int, packet["to"] as Int)
        override fun toMap(packet: TelemetryGridMoveRow): Map<String, Any> = mapOf("from" to packet.from, "table" to packet.table, "to" to packet.to)
    }
}

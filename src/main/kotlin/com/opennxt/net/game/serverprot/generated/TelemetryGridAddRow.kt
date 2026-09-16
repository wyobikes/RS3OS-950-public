package com.opennxt.net.game.serverprot.generated

import com.opennxt.net.game.GamePacket
import com.opennxt.net.game.pipeline.DynamicGamePacketCodec
import com.opennxt.net.game.protocol.PacketFieldDeclaration

data class TelemetryGridAddRow(val pos: Int, val table: Int, val rowId: Int) : GamePacket {
    class Codec(fields: Array<PacketFieldDeclaration>) : DynamicGamePacketCodec<TelemetryGridAddRow>(fields) {
        override fun fromMap(packet: Map<String, Any>): TelemetryGridAddRow = TelemetryGridAddRow(packet["pos"] as Int, packet["table"] as Int, packet["rowId"] as Int)
        override fun toMap(packet: TelemetryGridAddRow): Map<String, Any> = mapOf("pos" to packet.pos, "table" to packet.table, "rowId" to packet.rowId)
    }
}

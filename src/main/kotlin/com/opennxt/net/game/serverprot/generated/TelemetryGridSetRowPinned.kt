package com.opennxt.net.game.serverprot.generated

import com.opennxt.net.game.GamePacket
import com.opennxt.net.game.pipeline.DynamicGamePacketCodec
import com.opennxt.net.game.protocol.PacketFieldDeclaration

data class TelemetryGridSetRowPinned(val row: Int, val pinned: Int, val table: Int) : GamePacket {
    class Codec(fields: Array<PacketFieldDeclaration>) : DynamicGamePacketCodec<TelemetryGridSetRowPinned>(fields) {
        override fun fromMap(packet: Map<String, Any>): TelemetryGridSetRowPinned = TelemetryGridSetRowPinned(packet["row"] as Int, packet["pinned"] as Int, packet["table"] as Int)
        override fun toMap(packet: TelemetryGridSetRowPinned): Map<String, Any> = mapOf("row" to packet.row, "pinned" to packet.pinned, "table" to packet.table)
    }
}

package com.opennxt.net.game.serverprot.generated

import com.opennxt.net.game.GamePacket
import com.opennxt.net.game.pipeline.DynamicGamePacketCodec
import com.opennxt.net.game.protocol.PacketFieldDeclaration

data class TelemetryGridMoveColumn(val to: Int, val from: Int, val table: Int) : GamePacket {
    class Codec(fields: Array<PacketFieldDeclaration>) : DynamicGamePacketCodec<TelemetryGridMoveColumn>(fields) {
        override fun fromMap(packet: Map<String, Any>): TelemetryGridMoveColumn = TelemetryGridMoveColumn(packet["to"] as Int, packet["from"] as Int, packet["table"] as Int)
        override fun toMap(packet: TelemetryGridMoveColumn): Map<String, Any> = mapOf("to" to packet.to, "from" to packet.from, "table" to packet.table)
    }
}

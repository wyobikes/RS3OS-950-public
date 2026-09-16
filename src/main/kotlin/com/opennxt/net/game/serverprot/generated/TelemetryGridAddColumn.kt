package com.opennxt.net.game.serverprot.generated

import com.opennxt.net.game.GamePacket
import com.opennxt.net.game.pipeline.DynamicGamePacketCodec
import com.opennxt.net.game.protocol.PacketFieldDeclaration

data class TelemetryGridAddColumn(val table: Int, val pos: Int, val columnId: Int) : GamePacket {
    class Codec(fields: Array<PacketFieldDeclaration>) : DynamicGamePacketCodec<TelemetryGridAddColumn>(fields) {
        override fun fromMap(packet: Map<String, Any>): TelemetryGridAddColumn = TelemetryGridAddColumn(packet["table"] as Int, packet["pos"] as Int, packet["columnId"] as Int)
        override fun toMap(packet: TelemetryGridAddColumn): Map<String, Any> = mapOf("table" to packet.table, "pos" to packet.pos, "columnId" to packet.columnId)
    }
}

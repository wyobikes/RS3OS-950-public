package com.opennxt.net.game.serverprot.generated

import com.opennxt.net.game.GamePacket
import com.opennxt.net.game.pipeline.DynamicGamePacketCodec
import com.opennxt.net.game.protocol.PacketFieldDeclaration

data class TelemetryClearGridValue(val row: Int, val column: Int, val table: Int) : GamePacket {
    class Codec(fields: Array<PacketFieldDeclaration>) : DynamicGamePacketCodec<TelemetryClearGridValue>(fields) {
        override fun fromMap(packet: Map<String, Any>): TelemetryClearGridValue = TelemetryClearGridValue(packet["row"] as Int, packet["column"] as Int, packet["table"] as Int)
        override fun toMap(packet: TelemetryClearGridValue): Map<String, Any> = mapOf("row" to packet.row, "column" to packet.column, "table" to packet.table)
    }
}

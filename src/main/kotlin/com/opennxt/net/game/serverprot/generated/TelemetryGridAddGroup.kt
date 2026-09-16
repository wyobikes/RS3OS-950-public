package com.opennxt.net.game.serverprot.generated

import com.opennxt.net.game.GamePacket
import com.opennxt.net.game.pipeline.DynamicGamePacketCodec
import com.opennxt.net.game.protocol.PacketFieldDeclaration

data class TelemetryGridAddGroup(val tableId: Int, val pos: Int) : GamePacket {
    class Codec(fields: Array<PacketFieldDeclaration>) : DynamicGamePacketCodec<TelemetryGridAddGroup>(fields) {
        override fun fromMap(packet: Map<String, Any>): TelemetryGridAddGroup = TelemetryGridAddGroup(packet["tableId"] as Int, packet["pos"] as Int)
        override fun toMap(packet: TelemetryGridAddGroup): Map<String, Any> = mapOf("tableId" to packet.tableId, "pos" to packet.pos)
    }
}

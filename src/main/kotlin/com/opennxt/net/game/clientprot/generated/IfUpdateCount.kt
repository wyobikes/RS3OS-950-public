package com.opennxt.net.game.clientprot.generated

import com.opennxt.net.game.GamePacket
import com.opennxt.net.game.pipeline.DynamicGamePacketCodec
import com.opennxt.net.game.protocol.PacketFieldDeclaration

data class IfUpdateCount(val count: Int) : GamePacket {
    class Codec(fields: Array<PacketFieldDeclaration>) : DynamicGamePacketCodec<IfUpdateCount>(fields) {
        override fun fromMap(packet: Map<String, Any>): IfUpdateCount = IfUpdateCount(packet["count"] as Int)
        override fun toMap(packet: IfUpdateCount): Map<String, Any> = mapOf("count" to packet.count)
    }
}

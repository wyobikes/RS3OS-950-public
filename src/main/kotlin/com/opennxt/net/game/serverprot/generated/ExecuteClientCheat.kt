package com.opennxt.net.game.serverprot.generated

import com.opennxt.net.game.GamePacket
import com.opennxt.net.game.pipeline.DynamicGamePacketCodec
import com.opennxt.net.game.protocol.PacketFieldDeclaration

data class ExecuteClientCheat(val id: Int) : GamePacket {
    class Codec(fields: Array<PacketFieldDeclaration>) : DynamicGamePacketCodec<ExecuteClientCheat>(fields) {
        override fun fromMap(packet: Map<String, Any>): ExecuteClientCheat = ExecuteClientCheat(packet["id"] as Int)
        override fun toMap(packet: ExecuteClientCheat): Map<String, Any> = mapOf("id" to packet.id)
    }
}

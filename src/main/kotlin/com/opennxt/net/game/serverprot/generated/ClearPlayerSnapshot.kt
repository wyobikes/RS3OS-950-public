package com.opennxt.net.game.serverprot.generated

import com.opennxt.net.game.GamePacket
import com.opennxt.net.game.pipeline.DynamicGamePacketCodec
import com.opennxt.net.game.protocol.PacketFieldDeclaration

data class ClearPlayerSnapshot(val slot: Int) : GamePacket {
    class Codec(fields: Array<PacketFieldDeclaration>) : DynamicGamePacketCodec<ClearPlayerSnapshot>(fields) {
        override fun fromMap(packet: Map<String, Any>): ClearPlayerSnapshot = ClearPlayerSnapshot(packet["slot"] as Int)
        override fun toMap(packet: ClearPlayerSnapshot): Map<String, Any> = mapOf("slot" to packet.slot)
    }
}

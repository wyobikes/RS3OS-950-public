package com.opennxt.net.game.clientprot.generated

import com.opennxt.net.game.GamePacket
import com.opennxt.net.game.pipeline.DynamicGamePacketCodec
import com.opennxt.net.game.protocol.PacketFieldDeclaration

data class LocPlacementConfirm(val rotation: Int, val coord: Int) : GamePacket {
    class Codec(fields: Array<PacketFieldDeclaration>) : DynamicGamePacketCodec<LocPlacementConfirm>(fields) {
        override fun fromMap(packet: Map<String, Any>): LocPlacementConfirm = LocPlacementConfirm(packet["rotation"] as Int, packet["coord"] as Int)
        override fun toMap(packet: LocPlacementConfirm): Map<String, Any> = mapOf("rotation" to packet.rotation, "coord" to packet.coord)
    }
}

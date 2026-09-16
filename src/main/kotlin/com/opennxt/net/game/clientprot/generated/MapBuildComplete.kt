package com.opennxt.net.game.clientprot.generated

import com.opennxt.net.game.GamePacket
import com.opennxt.net.game.pipeline.DynamicGamePacketCodec
import com.opennxt.net.game.protocol.PacketFieldDeclaration

data class MapBuildComplete(val elapsed: Int) : GamePacket {
    class Codec(fields: Array<PacketFieldDeclaration>) : DynamicGamePacketCodec<MapBuildComplete>(fields) {
        override fun fromMap(packet: Map<String, Any>): MapBuildComplete = MapBuildComplete(packet["elapsed"] as Int)
        override fun toMap(packet: MapBuildComplete): Map<String, Any> = mapOf("elapsed" to packet.elapsed)
    }
}

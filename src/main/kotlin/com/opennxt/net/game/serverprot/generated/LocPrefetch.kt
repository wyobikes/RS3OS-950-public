package com.opennxt.net.game.serverprot.generated

import com.opennxt.net.game.GamePacket
import com.opennxt.net.game.pipeline.DynamicGamePacketCodec
import com.opennxt.net.game.protocol.PacketFieldDeclaration

data class LocPrefetch(val loc: Int, val shape: Int) : GamePacket {
    class Codec(fields: Array<PacketFieldDeclaration>) : DynamicGamePacketCodec<LocPrefetch>(fields) {
        override fun fromMap(packet: Map<String, Any>): LocPrefetch = LocPrefetch(packet["loc"] as Int, packet["shape"] as Int)
        override fun toMap(packet: LocPrefetch): Map<String, Any> = mapOf("loc" to packet.loc, "shape" to packet.shape)
    }
}

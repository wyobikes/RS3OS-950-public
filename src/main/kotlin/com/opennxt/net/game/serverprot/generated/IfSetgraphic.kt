package com.opennxt.net.game.serverprot.generated

import com.opennxt.net.game.GamePacket
import com.opennxt.net.game.pipeline.DynamicGamePacketCodec
import com.opennxt.net.game.protocol.PacketFieldDeclaration

data class IfSetgraphic(val graphic: Int, val component: Int) : GamePacket {
    class Codec(fields: Array<PacketFieldDeclaration>) : DynamicGamePacketCodec<IfSetgraphic>(fields) {
        override fun fromMap(packet: Map<String, Any>): IfSetgraphic = IfSetgraphic(packet["graphic"] as Int, packet["component"] as Int)
        override fun toMap(packet: IfSetgraphic): Map<String, Any> = mapOf("graphic" to packet.graphic, "component" to packet.component)
    }
}

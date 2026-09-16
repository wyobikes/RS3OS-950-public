package com.opennxt.net.game.serverprot.generated

import com.opennxt.net.game.GamePacket
import com.opennxt.net.game.pipeline.DynamicGamePacketCodec
import com.opennxt.net.game.protocol.PacketFieldDeclaration

data class IfSetscrollpos(val scrollpos: Int, val component: Int) : GamePacket {
    class Codec(fields: Array<PacketFieldDeclaration>) : DynamicGamePacketCodec<IfSetscrollpos>(fields) {
        override fun fromMap(packet: Map<String, Any>): IfSetscrollpos = IfSetscrollpos(packet["scrollpos"] as Int, packet["component"] as Int)
        override fun toMap(packet: IfSetscrollpos): Map<String, Any> = mapOf("scrollpos" to packet.scrollpos, "component" to packet.component)
    }
}

package com.opennxt.net.game.serverprot.generated

import com.opennxt.net.game.GamePacket
import com.opennxt.net.game.pipeline.DynamicGamePacketCodec
import com.opennxt.net.game.protocol.PacketFieldDeclaration

data class IfSettextfont(val font: Int, val component: Int) : GamePacket {
    class Codec(fields: Array<PacketFieldDeclaration>) : DynamicGamePacketCodec<IfSettextfont>(fields) {
        override fun fromMap(packet: Map<String, Any>): IfSettextfont = IfSettextfont(packet["font"] as Int, packet["component"] as Int)
        override fun toMap(packet: IfSettextfont): Map<String, Any> = mapOf("font" to packet.font, "component" to packet.component)
    }
}

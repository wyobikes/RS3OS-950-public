package com.opennxt.net.game.serverprot.generated

import com.opennxt.net.game.GamePacket
import com.opennxt.net.game.pipeline.DynamicGamePacketCodec
import com.opennxt.net.game.protocol.PacketFieldDeclaration

data class IfSetanim(val component: Int, val anim: Int) : GamePacket {
    class Codec(fields: Array<PacketFieldDeclaration>) : DynamicGamePacketCodec<IfSetanim>(fields) {
        override fun fromMap(packet: Map<String, Any>): IfSetanim = IfSetanim(packet["component"] as Int, packet["anim"] as Int)
        override fun toMap(packet: IfSetanim): Map<String, Any> = mapOf("component" to packet.component, "anim" to packet.anim)
    }
}

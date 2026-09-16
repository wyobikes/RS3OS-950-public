package com.opennxt.net.game.clientprot.generated

import com.opennxt.net.game.GamePacket
import com.opennxt.net.game.pipeline.DynamicGamePacketCodec
import com.opennxt.net.game.protocol.PacketFieldDeclaration

data class ResumePausebutton(val component: Int, val sub: Int) : GamePacket {
    class Codec(fields: Array<PacketFieldDeclaration>) : DynamicGamePacketCodec<ResumePausebutton>(fields) {
        override fun fromMap(packet: Map<String, Any>): ResumePausebutton = ResumePausebutton(packet["component"] as Int, packet["sub"] as Int)
        override fun toMap(packet: ResumePausebutton): Map<String, Any> = mapOf("component" to packet.component, "sub" to packet.sub)
    }
}

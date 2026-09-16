package com.opennxt.net.game.serverprot.generated

import com.opennxt.net.game.GamePacket
import com.opennxt.net.game.pipeline.DynamicGamePacketCodec
import com.opennxt.net.game.protocol.PacketFieldDeclaration

data class IfSetobject64(val component: Int, val counthi: Int, val countlo: Int, val obj: Int) : GamePacket {
    class Codec(fields: Array<PacketFieldDeclaration>) : DynamicGamePacketCodec<IfSetobject64>(fields) {
        override fun fromMap(packet: Map<String, Any>): IfSetobject64 = IfSetobject64(packet["component"] as Int, packet["counthi"] as Int, packet["countlo"] as Int, packet["obj"] as Int)
        override fun toMap(packet: IfSetobject64): Map<String, Any> = mapOf("component" to packet.component, "counthi" to packet.counthi, "countlo" to packet.countlo, "obj" to packet.obj)
    }
}

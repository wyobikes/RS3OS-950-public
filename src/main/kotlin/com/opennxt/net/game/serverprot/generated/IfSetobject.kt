package com.opennxt.net.game.serverprot.generated

import com.opennxt.net.game.GamePacket
import com.opennxt.net.game.pipeline.DynamicGamePacketCodec
import com.opennxt.net.game.protocol.PacketFieldDeclaration

data class IfSetobject(val obj: Int, val count: Int, val component: Int) : GamePacket {
    class Codec(fields: Array<PacketFieldDeclaration>) : DynamicGamePacketCodec<IfSetobject>(fields) {
        override fun fromMap(packet: Map<String, Any>): IfSetobject = IfSetobject(packet["obj"] as Int, packet["count"] as Int, packet["component"] as Int)
        override fun toMap(packet: IfSetobject): Map<String, Any> = mapOf("obj" to packet.obj, "count" to packet.count, "component" to packet.component)
    }
}

package com.opennxt.net.game.serverprot.generated

import com.opennxt.net.game.GamePacket
import com.opennxt.net.game.pipeline.DynamicGamePacketCodec
import com.opennxt.net.game.protocol.PacketFieldDeclaration

data class IfSetrecol(val a: Int, val component: Int, val index: Int, val b: Int) : GamePacket {
    class Codec(fields: Array<PacketFieldDeclaration>) : DynamicGamePacketCodec<IfSetrecol>(fields) {
        override fun fromMap(packet: Map<String, Any>): IfSetrecol = IfSetrecol(packet["a"] as Int, packet["component"] as Int, packet["index"] as Int, packet["b"] as Int)
        override fun toMap(packet: IfSetrecol): Map<String, Any> = mapOf("a" to packet.a, "component" to packet.component, "index" to packet.index, "b" to packet.b)
    }
}

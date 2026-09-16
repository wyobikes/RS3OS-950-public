package com.opennxt.net.game.serverprot.generated

import com.opennxt.net.game.GamePacket
import com.opennxt.net.game.pipeline.DynamicGamePacketCodec
import com.opennxt.net.game.protocol.PacketFieldDeclaration

data class IfSetretex(val a: Int, val b: Int, val component: Int, val index: Int) : GamePacket {
    class Codec(fields: Array<PacketFieldDeclaration>) : DynamicGamePacketCodec<IfSetretex>(fields) {
        override fun fromMap(packet: Map<String, Any>): IfSetretex = IfSetretex(packet["a"] as Int, packet["b"] as Int, packet["component"] as Int, packet["index"] as Int)
        override fun toMap(packet: IfSetretex): Map<String, Any> = mapOf("a" to packet.a, "b" to packet.b, "component" to packet.component, "index" to packet.index)
    }
}

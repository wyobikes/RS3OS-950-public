package com.opennxt.net.game.serverprot.generated

import com.opennxt.net.game.GamePacket
import com.opennxt.net.game.pipeline.DynamicGamePacketCodec
import com.opennxt.net.game.protocol.PacketFieldDeclaration

data class IfSetobject64949(val obj: Int, val counthi: Int, val countlo: Int, val component: Int) : GamePacket {
    class Codec(fields: Array<PacketFieldDeclaration>) : DynamicGamePacketCodec<IfSetobject64949>(fields) {
        override fun fromMap(packet: Map<String, Any>): IfSetobject64949 = IfSetobject64949(packet["obj"] as Int, packet["counthi"] as Int, packet["countlo"] as Int, packet["component"] as Int)
        override fun toMap(packet: IfSetobject64949): Map<String, Any> = mapOf("obj" to packet.obj, "counthi" to packet.counthi, "countlo" to packet.countlo, "component" to packet.component)
    }
}

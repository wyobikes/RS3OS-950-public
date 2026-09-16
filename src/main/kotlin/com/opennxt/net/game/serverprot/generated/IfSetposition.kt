package com.opennxt.net.game.serverprot.generated

import com.opennxt.net.game.GamePacket
import com.opennxt.net.game.pipeline.DynamicGamePacketCodec
import com.opennxt.net.game.protocol.PacketFieldDeclaration

data class IfSetposition(val slot1: Int, val component: Int, val slot0: Int) : GamePacket {
    class Codec(fields: Array<PacketFieldDeclaration>) : DynamicGamePacketCodec<IfSetposition>(fields) {
        override fun fromMap(packet: Map<String, Any>): IfSetposition = IfSetposition(packet["slot1"] as Int, packet["component"] as Int, packet["slot0"] as Int)
        override fun toMap(packet: IfSetposition): Map<String, Any> = mapOf("slot1" to packet.slot1, "component" to packet.component, "slot0" to packet.slot0)
    }
}

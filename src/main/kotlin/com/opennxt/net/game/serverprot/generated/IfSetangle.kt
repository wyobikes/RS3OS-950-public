package com.opennxt.net.game.serverprot.generated

import com.opennxt.net.game.GamePacket
import com.opennxt.net.game.pipeline.DynamicGamePacketCodec
import com.opennxt.net.game.protocol.PacketFieldDeclaration

data class IfSetangle(val slot0: Int, val slot1: Int, val component: Int, val slot2: Int) : GamePacket {
    class Codec(fields: Array<PacketFieldDeclaration>) : DynamicGamePacketCodec<IfSetangle>(fields) {
        override fun fromMap(packet: Map<String, Any>): IfSetangle = IfSetangle(packet["slot0"] as Int, packet["slot1"] as Int, packet["component"] as Int, packet["slot2"] as Int)
        override fun toMap(packet: IfSetangle): Map<String, Any> = mapOf("slot0" to packet.slot0, "slot1" to packet.slot1, "component" to packet.component, "slot2" to packet.slot2)
    }
}

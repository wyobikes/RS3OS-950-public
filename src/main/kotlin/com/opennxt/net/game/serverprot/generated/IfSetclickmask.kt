package com.opennxt.net.game.serverprot.generated

import com.opennxt.net.game.GamePacket
import com.opennxt.net.game.pipeline.DynamicGamePacketCodec
import com.opennxt.net.game.protocol.PacketFieldDeclaration

data class IfSetclickmask(val component: Int, val enabled: Int) : GamePacket {
    class Codec(fields: Array<PacketFieldDeclaration>) : DynamicGamePacketCodec<IfSetclickmask>(fields) {
        override fun fromMap(packet: Map<String, Any>): IfSetclickmask = IfSetclickmask(packet["component"] as Int, packet["enabled"] as Int)
        override fun toMap(packet: IfSetclickmask): Map<String, Any> = mapOf("component" to packet.component, "enabled" to packet.enabled)
    }
}

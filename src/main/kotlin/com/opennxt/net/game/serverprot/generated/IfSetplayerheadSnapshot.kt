package com.opennxt.net.game.serverprot.generated

import com.opennxt.net.game.GamePacket
import com.opennxt.net.game.pipeline.DynamicGamePacketCodec
import com.opennxt.net.game.protocol.PacketFieldDeclaration

data class IfSetplayerheadSnapshot(val component: Int, val snapshot: Int) : GamePacket {
    class Codec(fields: Array<PacketFieldDeclaration>) : DynamicGamePacketCodec<IfSetplayerheadSnapshot>(fields) {
        override fun fromMap(packet: Map<String, Any>): IfSetplayerheadSnapshot = IfSetplayerheadSnapshot(packet["component"] as Int, packet["snapshot"] as Int)
        override fun toMap(packet: IfSetplayerheadSnapshot): Map<String, Any> = mapOf("component" to packet.component, "snapshot" to packet.snapshot)
    }
}

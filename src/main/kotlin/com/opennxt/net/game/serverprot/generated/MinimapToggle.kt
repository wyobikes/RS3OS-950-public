package com.opennxt.net.game.serverprot.generated

import com.opennxt.net.game.GamePacket
import com.opennxt.net.game.pipeline.DynamicGamePacketCodec
import com.opennxt.net.game.protocol.PacketFieldDeclaration

data class MinimapToggle(val mode: Int) : GamePacket {
    class Codec(fields: Array<PacketFieldDeclaration>) : DynamicGamePacketCodec<MinimapToggle>(fields) {
        override fun fromMap(packet: Map<String, Any>): MinimapToggle = MinimapToggle(packet["mode"] as Int)
        override fun toMap(packet: MinimapToggle): Map<String, Any> = mapOf("mode" to packet.mode)
    }
}

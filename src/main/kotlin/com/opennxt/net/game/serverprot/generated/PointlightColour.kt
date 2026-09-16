package com.opennxt.net.game.serverprot.generated

import com.opennxt.net.game.GamePacket
import com.opennxt.net.game.pipeline.DynamicGamePacketCodec
import com.opennxt.net.game.protocol.PacketFieldDeclaration

data class PointlightColour(val colour: Int, val fade: Int, val light: Int) : GamePacket {
    class Codec(fields: Array<PacketFieldDeclaration>) : DynamicGamePacketCodec<PointlightColour>(fields) {
        override fun fromMap(packet: Map<String, Any>): PointlightColour = PointlightColour(packet["colour"] as Int, packet["fade"] as Int, packet["light"] as Int)
        override fun toMap(packet: PointlightColour): Map<String, Any> = mapOf("colour" to packet.colour, "fade" to packet.fade, "light" to packet.light)
    }
}

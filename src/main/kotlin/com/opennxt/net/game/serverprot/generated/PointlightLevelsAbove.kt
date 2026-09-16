package com.opennxt.net.game.serverprot.generated

import com.opennxt.net.game.GamePacket
import com.opennxt.net.game.pipeline.DynamicGamePacketCodec
import com.opennxt.net.game.protocol.PacketFieldDeclaration

data class PointlightLevelsAbove(val flag: Int, val light: Int) : GamePacket {
    class Codec(fields: Array<PacketFieldDeclaration>) : DynamicGamePacketCodec<PointlightLevelsAbove>(fields) {
        override fun fromMap(packet: Map<String, Any>): PointlightLevelsAbove = PointlightLevelsAbove(packet["flag"] as Int, packet["light"] as Int)
        override fun toMap(packet: PointlightLevelsAbove): Map<String, Any> = mapOf("flag" to packet.flag, "light" to packet.light)
    }
}

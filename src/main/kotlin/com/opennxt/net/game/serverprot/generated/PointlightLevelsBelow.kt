package com.opennxt.net.game.serverprot.generated

import com.opennxt.net.game.GamePacket
import com.opennxt.net.game.pipeline.DynamicGamePacketCodec
import com.opennxt.net.game.protocol.PacketFieldDeclaration

data class PointlightLevelsBelow(val flag: Int, val light: Int) : GamePacket {
    class Codec(fields: Array<PacketFieldDeclaration>) : DynamicGamePacketCodec<PointlightLevelsBelow>(fields) {
        override fun fromMap(packet: Map<String, Any>): PointlightLevelsBelow = PointlightLevelsBelow(packet["flag"] as Int, packet["light"] as Int)
        override fun toMap(packet: PointlightLevelsBelow): Map<String, Any> = mapOf("flag" to packet.flag, "light" to packet.light)
    }
}

package com.opennxt.net.game.serverprot.generated

import com.opennxt.net.game.GamePacket
import com.opennxt.net.game.pipeline.DynamicGamePacketCodec
import com.opennxt.net.game.protocol.PacketFieldDeclaration

data class PointlightIntensity(val fade: Int, val percent: Int, val light: Int) : GamePacket {
    class Codec(fields: Array<PacketFieldDeclaration>) : DynamicGamePacketCodec<PointlightIntensity>(fields) {
        override fun fromMap(packet: Map<String, Any>): PointlightIntensity = PointlightIntensity(packet["fade"] as Int, packet["percent"] as Int, packet["light"] as Int)
        override fun toMap(packet: PointlightIntensity): Map<String, Any> = mapOf("fade" to packet.fade, "percent" to packet.percent, "light" to packet.light)
    }
}

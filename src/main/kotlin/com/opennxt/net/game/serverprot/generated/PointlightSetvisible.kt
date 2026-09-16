package com.opennxt.net.game.serverprot.generated

import com.opennxt.net.game.GamePacket
import com.opennxt.net.game.pipeline.DynamicGamePacketCodec
import com.opennxt.net.game.protocol.PacketFieldDeclaration

data class PointlightSetvisible(val light: Int, val mode: Int) : GamePacket {
    class Codec(fields: Array<PacketFieldDeclaration>) : DynamicGamePacketCodec<PointlightSetvisible>(fields) {
        override fun fromMap(packet: Map<String, Any>): PointlightSetvisible = PointlightSetvisible(packet["light"] as Int, packet["mode"] as Int)
        override fun toMap(packet: PointlightSetvisible): Map<String, Any> = mapOf("light" to packet.light, "mode" to packet.mode)
    }
}

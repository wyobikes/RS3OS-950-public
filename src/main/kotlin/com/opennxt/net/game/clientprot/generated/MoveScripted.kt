package com.opennxt.net.game.clientprot.generated

import com.opennxt.net.game.GamePacket
import com.opennxt.net.game.pipeline.DynamicGamePacketCodec
import com.opennxt.net.game.protocol.PacketFieldDeclaration

data class MoveScripted(val speed: Int, val x: Int, val y: Int) : GamePacket {
    class Codec(fields: Array<PacketFieldDeclaration>) : DynamicGamePacketCodec<MoveScripted>(fields) {
        override fun fromMap(packet: Map<String, Any>): MoveScripted = MoveScripted(packet["speed"] as Int, packet["x"] as Int, packet["y"] as Int)
        override fun toMap(packet: MoveScripted): Map<String, Any> = mapOf("speed" to packet.speed, "x" to packet.x, "y" to packet.y)
    }
}

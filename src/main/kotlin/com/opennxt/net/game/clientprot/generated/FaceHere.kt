package com.opennxt.net.game.clientprot.generated

import com.opennxt.net.game.GamePacket
import com.opennxt.net.game.pipeline.DynamicGamePacketCodec
import com.opennxt.net.game.protocol.PacketFieldDeclaration

data class FaceHere(val y: Int, val x: Int) : GamePacket {
    class Codec(fields: Array<PacketFieldDeclaration>) : DynamicGamePacketCodec<FaceHere>(fields) {
        override fun fromMap(packet: Map<String, Any>): FaceHere = FaceHere(packet["y"] as Int, packet["x"] as Int)
        override fun toMap(packet: FaceHere): Map<String, Any> = mapOf("y" to packet.y, "x" to packet.x)
    }
}

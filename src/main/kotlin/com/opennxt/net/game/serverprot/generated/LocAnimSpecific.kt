package com.opennxt.net.game.serverprot.generated

import com.opennxt.net.game.GamePacket
import com.opennxt.net.game.pipeline.DynamicGamePacketCodec
import com.opennxt.net.game.protocol.PacketFieldDeclaration

data class LocAnimSpecific(val coord: Int, val anim: Int, val arg: Int, val shaperot: Int) : GamePacket {
    class Codec(fields: Array<PacketFieldDeclaration>) : DynamicGamePacketCodec<LocAnimSpecific>(fields) {
        override fun fromMap(packet: Map<String, Any>): LocAnimSpecific = LocAnimSpecific(packet["coord"] as Int, packet["anim"] as Int, packet["arg"] as Int, packet["shaperot"] as Int)
        override fun toMap(packet: LocAnimSpecific): Map<String, Any> = mapOf("coord" to packet.coord, "anim" to packet.anim, "arg" to packet.arg, "shaperot" to packet.shaperot)
    }
}

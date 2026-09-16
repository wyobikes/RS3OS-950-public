package com.opennxt.net.game.serverprot.generated

import com.opennxt.net.game.GamePacket
import com.opennxt.net.game.pipeline.DynamicGamePacketCodec
import com.opennxt.net.game.protocol.PacketFieldDeclaration

data class IfSetHttpImage(val image: Int, val component: Int) : GamePacket {
    class Codec(fields: Array<PacketFieldDeclaration>) : DynamicGamePacketCodec<IfSetHttpImage>(fields) {
        override fun fromMap(packet: Map<String, Any>): IfSetHttpImage = IfSetHttpImage(packet["image"] as Int, packet["component"] as Int)
        override fun toMap(packet: IfSetHttpImage): Map<String, Any> = mapOf("image" to packet.image, "component" to packet.component)
    }
}

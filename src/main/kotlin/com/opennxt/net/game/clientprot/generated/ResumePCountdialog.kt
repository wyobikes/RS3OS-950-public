package com.opennxt.net.game.clientprot.generated

import com.opennxt.net.game.GamePacket
import com.opennxt.net.game.pipeline.DynamicGamePacketCodec
import com.opennxt.net.game.protocol.PacketFieldDeclaration

data class ResumePCountdialog(val count: Int) : GamePacket {
    class Codec(fields: Array<PacketFieldDeclaration>) : DynamicGamePacketCodec<ResumePCountdialog>(fields) {
        override fun fromMap(packet: Map<String, Any>): ResumePCountdialog = ResumePCountdialog(packet["count"] as Int)
        override fun toMap(packet: ResumePCountdialog): Map<String, Any> = mapOf("count" to packet.count)
    }
}

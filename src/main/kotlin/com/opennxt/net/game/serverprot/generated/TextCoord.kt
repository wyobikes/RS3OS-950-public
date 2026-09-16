package com.opennxt.net.game.serverprot.generated

import com.opennxt.net.game.GamePacket
import com.opennxt.net.game.pipeline.DynamicGamePacketCodec
import com.opennxt.net.game.protocol.PacketFieldDeclaration

data class TextCoord(val unused: Int, val xz: Int, val expiry: Int, val height: Int, val rgb: Int, val text: String) : GamePacket {
    class Codec(fields: Array<PacketFieldDeclaration>) : DynamicGamePacketCodec<TextCoord>(fields) {
        override fun fromMap(packet: Map<String, Any>): TextCoord = TextCoord(packet["unused"] as Int, packet["xz"] as Int, packet["expiry"] as Int, packet["height"] as Int, packet["rgb"] as Int, packet["text"] as String)
        override fun toMap(packet: TextCoord): Map<String, Any> = mapOf("unused" to packet.unused, "xz" to packet.xz, "expiry" to packet.expiry, "height" to packet.height, "rgb" to packet.rgb, "text" to packet.text)
    }
}

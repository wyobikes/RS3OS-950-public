package com.opennxt.net.game.clientprot.generated

import com.opennxt.net.game.GamePacket
import com.opennxt.net.game.pipeline.DynamicGamePacketCodec
import com.opennxt.net.game.protocol.PacketFieldDeclaration

data class Optilet(val selhash: Int, val selobj: Int, val y: Int, val x: Int, val selsub: Int) : GamePacket {
    class Codec(fields: Array<PacketFieldDeclaration>) : DynamicGamePacketCodec<Optilet>(fields) {
        override fun fromMap(packet: Map<String, Any>): Optilet = Optilet(packet["selhash"] as Int, packet["selobj"] as Int, packet["y"] as Int, packet["x"] as Int, packet["selsub"] as Int)
        override fun toMap(packet: Optilet): Map<String, Any> = mapOf("selhash" to packet.selhash, "selobj" to packet.selobj, "y" to packet.y, "x" to packet.x, "selsub" to packet.selsub)
    }
}

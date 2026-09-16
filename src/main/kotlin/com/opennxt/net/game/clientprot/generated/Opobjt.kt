package com.opennxt.net.game.clientprot.generated

import com.opennxt.net.game.GamePacket
import com.opennxt.net.game.pipeline.DynamicGamePacketCodec
import com.opennxt.net.game.protocol.PacketFieldDeclaration

data class Opobjt(val y: Int, val selhash: Int, val selsub: Int, val flags: Int, val obj: Int, val x: Int, val selobj: Int) : GamePacket {
    class Codec(fields: Array<PacketFieldDeclaration>) : DynamicGamePacketCodec<Opobjt>(fields) {
        override fun fromMap(packet: Map<String, Any>): Opobjt = Opobjt(packet["y"] as Int, packet["selhash"] as Int, packet["selsub"] as Int, packet["flags"] as Int, packet["obj"] as Int, packet["x"] as Int, packet["selobj"] as Int)
        override fun toMap(packet: Opobjt): Map<String, Any> = mapOf("y" to packet.y, "selhash" to packet.selhash, "selsub" to packet.selsub, "flags" to packet.flags, "obj" to packet.obj, "x" to packet.x, "selobj" to packet.selobj)
    }
}

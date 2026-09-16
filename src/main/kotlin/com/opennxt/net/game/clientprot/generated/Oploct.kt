package com.opennxt.net.game.clientprot.generated

import com.opennxt.net.game.GamePacket
import com.opennxt.net.game.pipeline.DynamicGamePacketCodec
import com.opennxt.net.game.protocol.PacketFieldDeclaration

data class Oploct(val y: Int, val x: Int, val selobj: Int, val ctrl: Int, val selhash: Int, val loc: Int, val selsub: Int) : GamePacket {
    class Codec(fields: Array<PacketFieldDeclaration>) : DynamicGamePacketCodec<Oploct>(fields) {
        override fun fromMap(packet: Map<String, Any>): Oploct = Oploct(packet["y"] as Int, packet["x"] as Int, packet["selobj"] as Int, packet["ctrl"] as Int, packet["selhash"] as Int, packet["loc"] as Int, packet["selsub"] as Int)
        override fun toMap(packet: Oploct): Map<String, Any> = mapOf("y" to packet.y, "x" to packet.x, "selobj" to packet.selobj, "ctrl" to packet.ctrl, "selhash" to packet.selhash, "loc" to packet.loc, "selsub" to packet.selsub)
    }
}

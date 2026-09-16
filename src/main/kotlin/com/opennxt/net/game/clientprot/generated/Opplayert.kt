package com.opennxt.net.game.clientprot.generated

import com.opennxt.net.game.GamePacket
import com.opennxt.net.game.pipeline.DynamicGamePacketCodec
import com.opennxt.net.game.protocol.PacketFieldDeclaration

data class Opplayert(val player: Int, val selsub: Int, val selhash: Int, val selobj: Int, val ctrl: Int) : GamePacket {
    class Codec(fields: Array<PacketFieldDeclaration>) : DynamicGamePacketCodec<Opplayert>(fields) {
        override fun fromMap(packet: Map<String, Any>): Opplayert = Opplayert(packet["player"] as Int, packet["selsub"] as Int, packet["selhash"] as Int, packet["selobj"] as Int, packet["ctrl"] as Int)
        override fun toMap(packet: Opplayert): Map<String, Any> = mapOf("player" to packet.player, "selsub" to packet.selsub, "selhash" to packet.selhash, "selobj" to packet.selobj, "ctrl" to packet.ctrl)
    }
}

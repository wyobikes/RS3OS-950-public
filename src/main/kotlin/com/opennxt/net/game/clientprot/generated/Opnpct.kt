package com.opennxt.net.game.clientprot.generated

import com.opennxt.net.game.GamePacket
import com.opennxt.net.game.pipeline.DynamicGamePacketCodec
import com.opennxt.net.game.protocol.PacketFieldDeclaration

data class Opnpct(val selobj: Int, val selsub: Int, val selhash: Int, val npc: Int, val ctrl: Int) : GamePacket {
    class Codec(fields: Array<PacketFieldDeclaration>) : DynamicGamePacketCodec<Opnpct>(fields) {
        override fun fromMap(packet: Map<String, Any>): Opnpct = Opnpct(packet["selobj"] as Int, packet["selsub"] as Int, packet["selhash"] as Int, packet["npc"] as Int, packet["ctrl"] as Int)
        override fun toMap(packet: Opnpct): Map<String, Any> = mapOf("selobj" to packet.selobj, "selsub" to packet.selsub, "selhash" to packet.selhash, "npc" to packet.npc, "ctrl" to packet.ctrl)
    }
}

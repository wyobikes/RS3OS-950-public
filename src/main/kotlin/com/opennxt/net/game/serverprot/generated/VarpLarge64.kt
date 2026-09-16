package com.opennxt.net.game.serverprot.generated

import com.opennxt.net.game.GamePacket
import com.opennxt.net.game.pipeline.DynamicGamePacketCodec
import com.opennxt.net.game.protocol.PacketFieldDeclaration

data class VarpLarge64(val id: Int, val hi: Int, val lo: Int) : GamePacket {
    class Codec(fields: Array<PacketFieldDeclaration>) : DynamicGamePacketCodec<VarpLarge64>(fields) {
        override fun fromMap(packet: Map<String, Any>): VarpLarge64 = VarpLarge64(packet["id"] as Int, packet["hi"] as Int, packet["lo"] as Int)
        override fun toMap(packet: VarpLarge64): Map<String, Any> = mapOf("id" to packet.id, "hi" to packet.hi, "lo" to packet.lo)
    }
}

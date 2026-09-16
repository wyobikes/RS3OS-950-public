package com.opennxt.net.game.serverprot.generated

import com.opennxt.net.game.GamePacket
import com.opennxt.net.game.pipeline.DynamicGamePacketCodec
import com.opennxt.net.game.protocol.PacketFieldDeclaration

data class ClientSetvarcLarge64(val hi: Int, val lo: Int, val id: Int) : GamePacket {
    class Codec(fields: Array<PacketFieldDeclaration>) : DynamicGamePacketCodec<ClientSetvarcLarge64>(fields) {
        override fun fromMap(packet: Map<String, Any>): ClientSetvarcLarge64 = ClientSetvarcLarge64(packet["hi"] as Int, packet["lo"] as Int, packet["id"] as Int)
        override fun toMap(packet: ClientSetvarcLarge64): Map<String, Any> = mapOf("hi" to packet.hi, "lo" to packet.lo, "id" to packet.id)
    }
}

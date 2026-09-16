package com.opennxt.net.game.serverprot.generated

import com.opennxt.net.game.GamePacket
import com.opennxt.net.game.pipeline.DynamicGamePacketCodec
import com.opennxt.net.game.protocol.PacketFieldDeclaration

data class Setdraworder(val mode: Int) : GamePacket {
    class Codec(fields: Array<PacketFieldDeclaration>) : DynamicGamePacketCodec<Setdraworder>(fields) {
        override fun fromMap(packet: Map<String, Any>): Setdraworder = Setdraworder(packet["mode"] as Int)
        override fun toMap(packet: Setdraworder): Map<String, Any> = mapOf("mode" to packet.mode)
    }
}

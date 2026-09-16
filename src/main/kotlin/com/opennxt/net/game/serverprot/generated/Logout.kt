package com.opennxt.net.game.serverprot.generated

import com.opennxt.net.game.GamePacket
import com.opennxt.net.game.pipeline.DynamicGamePacketCodec
import com.opennxt.net.game.protocol.PacketFieldDeclaration

data class Logout(val reason: Int) : GamePacket {
    class Codec(fields: Array<PacketFieldDeclaration>) : DynamicGamePacketCodec<Logout>(fields) {
        override fun fromMap(packet: Map<String, Any>): Logout = Logout(packet["reason"] as Int)
        override fun toMap(packet: Logout): Map<String, Any> = mapOf("reason" to packet.reason)
    }
}

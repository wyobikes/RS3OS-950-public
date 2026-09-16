package com.opennxt.net.game.serverprot.generated

import com.opennxt.net.game.GamePacket
import com.opennxt.net.game.pipeline.DynamicGamePacketCodec
import com.opennxt.net.game.protocol.PacketFieldDeclaration

data class LogoutFull(val reason: Int) : GamePacket {
    class Codec(fields: Array<PacketFieldDeclaration>) : DynamicGamePacketCodec<LogoutFull>(fields) {
        override fun fromMap(packet: Map<String, Any>): LogoutFull = LogoutFull(packet["reason"] as Int)
        override fun toMap(packet: LogoutFull): Map<String, Any> = mapOf("reason" to packet.reason)
    }
}

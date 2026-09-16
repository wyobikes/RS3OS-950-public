package com.opennxt.net.game.clientprot.generated

import com.opennxt.net.game.GamePacket
import com.opennxt.net.game.pipeline.DynamicGamePacketCodec
import com.opennxt.net.game.protocol.PacketFieldDeclaration

data class SetChatfiltersettings(val public: Int, val private: Int, val trade: Int) : GamePacket {
    class Codec(fields: Array<PacketFieldDeclaration>) : DynamicGamePacketCodec<SetChatfiltersettings>(fields) {
        override fun fromMap(packet: Map<String, Any>): SetChatfiltersettings = SetChatfiltersettings(packet["public"] as Int, packet["private"] as Int, packet["trade"] as Int)
        override fun toMap(packet: SetChatfiltersettings): Map<String, Any> = mapOf("public" to packet.public, "private" to packet.private, "trade" to packet.trade)
    }
}

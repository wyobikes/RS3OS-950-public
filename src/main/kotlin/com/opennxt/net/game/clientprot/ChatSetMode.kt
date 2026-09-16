package com.opennxt.net.game.clientprot

import com.opennxt.net.game.GamePacket
import com.opennxt.net.game.pipeline.DynamicGamePacketCodec
import com.opennxt.net.game.protocol.PacketFieldDeclaration

data class ChatSetMode(val mode: Int, val arg: Int) : GamePacket {
    override fun toString(): String =
        "CHAT_SETMODE(mode=$mode${com.opennxt.model.entity.ChatMode.nameOf(mode)?.let { "/$it" } ?: ""}, arg=$arg)"

    class Codec(fields: Array<PacketFieldDeclaration>) : DynamicGamePacketCodec<ChatSetMode>(fields) {
        override fun fromMap(packet: Map<String, Any>): ChatSetMode =
            ChatSetMode(packet["mode"] as Int, packet["arg"] as Int)

        override fun toMap(packet: ChatSetMode): Map<String, Any> =
            mapOf("mode" to packet.mode, "arg" to packet.arg)
    }
}

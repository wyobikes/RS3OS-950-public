package com.opennxt.net.game.serverprot.generated

import com.opennxt.net.game.GamePacket
import com.opennxt.net.game.pipeline.DynamicGamePacketCodec
import com.opennxt.net.game.protocol.PacketFieldDeclaration

data class SetLocOpOverride(val param: Int, val cursor: Int, val slot: Int, val value: Int, val text: String) : GamePacket {
    class Codec(fields: Array<PacketFieldDeclaration>) : DynamicGamePacketCodec<SetLocOpOverride>(fields) {
        override fun fromMap(packet: Map<String, Any>): SetLocOpOverride = SetLocOpOverride(packet["param"] as Int, packet["cursor"] as Int, packet["slot"] as Int, packet["value"] as Int, packet["text"] as String)
        override fun toMap(packet: SetLocOpOverride): Map<String, Any> = mapOf("param" to packet.param, "cursor" to packet.cursor, "slot" to packet.slot, "value" to packet.value, "text" to packet.text)
    }
}

package com.opennxt.net.game.serverprot.generated

import com.opennxt.net.game.GamePacket
import com.opennxt.net.game.pipeline.DynamicGamePacketCodec
import com.opennxt.net.game.protocol.PacketFieldDeclaration

data class NpcSay(val text: String, val npc: Int, val a: Int, val b: Int) : GamePacket {
    class Codec(fields: Array<PacketFieldDeclaration>) : DynamicGamePacketCodec<NpcSay>(fields) {
        override fun fromMap(packet: Map<String, Any>): NpcSay = NpcSay(packet["text"] as String, packet["npc"] as Int, packet["a"] as Int, packet["b"] as Int)
        override fun toMap(packet: NpcSay): Map<String, Any> = mapOf("text" to packet.text, "npc" to packet.npc, "a" to packet.a, "b" to packet.b)
    }
}

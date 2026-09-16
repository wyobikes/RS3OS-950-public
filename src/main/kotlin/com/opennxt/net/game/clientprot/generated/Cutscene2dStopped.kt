package com.opennxt.net.game.clientprot.generated

import com.opennxt.net.game.GamePacket
import com.opennxt.net.game.pipeline.DynamicGamePacketCodec
import com.opennxt.net.game.protocol.PacketFieldDeclaration

data class Cutscene2dStopped(val cutscene: Int, val reason: Int) : GamePacket {
    class Codec(fields: Array<PacketFieldDeclaration>) : DynamicGamePacketCodec<Cutscene2dStopped>(fields) {
        override fun fromMap(packet: Map<String, Any>): Cutscene2dStopped = Cutscene2dStopped(packet["cutscene"] as Int, packet["reason"] as Int)
        override fun toMap(packet: Cutscene2dStopped): Map<String, Any> = mapOf("cutscene" to packet.cutscene, "reason" to packet.reason)
    }
}

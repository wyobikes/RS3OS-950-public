package com.opennxt.net.game.serverprot.generated

import com.opennxt.net.game.GamePacket
import com.opennxt.net.game.pipeline.DynamicGamePacketCodec
import com.opennxt.net.game.protocol.PacketFieldDeclaration

data class ReducePlayerAttackPriority(val priority: Int) : GamePacket {
    class Codec(fields: Array<PacketFieldDeclaration>) : DynamicGamePacketCodec<ReducePlayerAttackPriority>(fields) {
        override fun fromMap(packet: Map<String, Any>): ReducePlayerAttackPriority = ReducePlayerAttackPriority(packet["priority"] as Int)
        override fun toMap(packet: ReducePlayerAttackPriority): Map<String, Any> = mapOf("priority" to packet.priority)
    }
}

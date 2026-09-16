package com.opennxt.net.game.serverprot.generated

import com.opennxt.net.game.GamePacket
import com.opennxt.net.game.pipeline.DynamicGamePacketCodec
import com.opennxt.net.game.protocol.PacketFieldDeclaration

data class PlayerAnimSpecific(val anim0: Int, val anim1: Int, val anim2: Int, val anim3: Int, val delay: Int) : GamePacket {
    class Codec(fields: Array<PacketFieldDeclaration>) : DynamicGamePacketCodec<PlayerAnimSpecific>(fields) {
        override fun fromMap(packet: Map<String, Any>): PlayerAnimSpecific = PlayerAnimSpecific(packet["anim0"] as Int, packet["anim1"] as Int, packet["anim2"] as Int, packet["anim3"] as Int, packet["delay"] as Int)
        override fun toMap(packet: PlayerAnimSpecific): Map<String, Any> = mapOf("anim0" to packet.anim0, "anim1" to packet.anim1, "anim2" to packet.anim2, "anim3" to packet.anim3, "delay" to packet.delay)
    }
}

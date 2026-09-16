package com.opennxt.net.game.serverprot.generated

import com.opennxt.net.game.GamePacket
import com.opennxt.net.game.pipeline.DynamicGamePacketCodec
import com.opennxt.net.game.protocol.PacketFieldDeclaration

data class SoundStop(val sound: Int, val group: Int) : GamePacket {
    class Codec(fields: Array<PacketFieldDeclaration>) : DynamicGamePacketCodec<SoundStop>(fields) {
        override fun fromMap(packet: Map<String, Any>): SoundStop = SoundStop(packet["sound"] as Int, packet["group"] as Int)
        override fun toMap(packet: SoundStop): Map<String, Any> = mapOf("sound" to packet.sound, "group" to packet.group)
    }
}

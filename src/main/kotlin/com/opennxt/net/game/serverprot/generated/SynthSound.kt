package com.opennxt.net.game.serverprot.generated

import com.opennxt.net.game.GamePacket
import com.opennxt.net.game.pipeline.DynamicGamePacketCodec
import com.opennxt.net.game.protocol.PacketFieldDeclaration

data class SynthSound(val sound: Int, val count: Int, val delay: Int, val volume: Int, val extra: Int) : GamePacket {
    class Codec(fields: Array<PacketFieldDeclaration>) : DynamicGamePacketCodec<SynthSound>(fields) {
        override fun fromMap(packet: Map<String, Any>): SynthSound = SynthSound(packet["sound"] as Int, packet["count"] as Int, packet["delay"] as Int, packet["volume"] as Int, packet["extra"] as Int)
        override fun toMap(packet: SynthSound): Map<String, Any> = mapOf("sound" to packet.sound, "count" to packet.count, "delay" to packet.delay, "volume" to packet.volume, "extra" to packet.extra)
    }
}

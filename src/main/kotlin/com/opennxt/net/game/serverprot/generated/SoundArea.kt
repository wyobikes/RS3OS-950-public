package com.opennxt.net.game.serverprot.generated

import com.opennxt.net.game.GamePacket
import com.opennxt.net.game.pipeline.DynamicGamePacketCodec
import com.opennxt.net.game.protocol.PacketFieldDeclaration

data class SoundArea(val xz: Int, val sound: Int, val countradius: Int, val loop: Int, val volume: Int, val extra: Int) : GamePacket {
    class Codec(fields: Array<PacketFieldDeclaration>) : DynamicGamePacketCodec<SoundArea>(fields) {
        override fun fromMap(packet: Map<String, Any>): SoundArea = SoundArea(packet["xz"] as Int, packet["sound"] as Int, packet["countradius"] as Int, packet["loop"] as Int, packet["volume"] as Int, packet["extra"] as Int)
        override fun toMap(packet: SoundArea): Map<String, Any> = mapOf("xz" to packet.xz, "sound" to packet.sound, "countradius" to packet.countradius, "loop" to packet.loop, "volume" to packet.volume, "extra" to packet.extra)
    }
}

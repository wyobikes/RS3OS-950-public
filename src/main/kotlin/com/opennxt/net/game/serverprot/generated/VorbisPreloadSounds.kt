package com.opennxt.net.game.serverprot.generated

import com.opennxt.net.game.GamePacket
import com.opennxt.net.game.pipeline.DynamicGamePacketCodec
import com.opennxt.net.game.protocol.PacketFieldDeclaration

data class VorbisPreloadSounds(val id: Int) : GamePacket {
    class Codec(fields: Array<PacketFieldDeclaration>) : DynamicGamePacketCodec<VorbisPreloadSounds>(fields) {
        override fun fromMap(packet: Map<String, Any>): VorbisPreloadSounds = VorbisPreloadSounds(packet["id"] as Int)
        override fun toMap(packet: VorbisPreloadSounds): Map<String, Any> = mapOf("id" to packet.id)
    }
}

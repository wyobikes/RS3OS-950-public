package com.opennxt.net.game.serverprot.generated

import com.opennxt.net.game.GamePacket
import com.opennxt.net.game.pipeline.DynamicGamePacketCodec
import com.opennxt.net.game.protocol.PacketFieldDeclaration

data class SongPreload(val id: Int) : GamePacket {
    class Codec(fields: Array<PacketFieldDeclaration>) : DynamicGamePacketCodec<SongPreload>(fields) {
        override fun fromMap(packet: Map<String, Any>): SongPreload = SongPreload(packet["id"] as Int)
        override fun toMap(packet: SongPreload): Map<String, Any> = mapOf("id" to packet.id)
    }
}

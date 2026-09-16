package com.opennxt.net.game.serverprot.generated

import com.opennxt.net.game.GamePacket
import com.opennxt.net.game.pipeline.DynamicGamePacketCodec
import com.opennxt.net.game.protocol.PacketFieldDeclaration

data class MidiJingle(val volume: Int, val id: Int) : GamePacket {
    class Codec(fields: Array<PacketFieldDeclaration>) : DynamicGamePacketCodec<MidiJingle>(fields) {
        override fun fromMap(packet: Map<String, Any>): MidiJingle = MidiJingle(packet["volume"] as Int, packet["id"] as Int)
        override fun toMap(packet: MidiJingle): Map<String, Any> = mapOf("volume" to packet.volume, "id" to packet.id)
    }
}

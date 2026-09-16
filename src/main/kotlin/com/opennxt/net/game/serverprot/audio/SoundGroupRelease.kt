package com.opennxt.net.game.serverprot.audio

import com.opennxt.net.game.GamePacket
import com.opennxt.net.game.pipeline.DynamicGamePacketCodec
import com.opennxt.net.game.protocol.PacketFieldDeclaration
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap

data class SoundGroupRelease(val group: Int) : GamePacket {
    class Codec(fields: Array<PacketFieldDeclaration>) : DynamicGamePacketCodec<SoundGroupRelease>(fields) {
        override fun fromMap(packet: Map<String, Any>): SoundGroupRelease = SoundGroupRelease(packet["group"] as Int)

        override fun toMap(packet: SoundGroupRelease): Map<String, Any> {
            val map = Object2ObjectOpenHashMap<String, Any>()
            map["group"] = packet.group
            return map
        }
    }
}

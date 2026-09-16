package com.opennxt.net.game.serverprot.generated

import com.opennxt.net.game.GamePacket
import com.opennxt.net.game.pipeline.DynamicGamePacketCodec
import com.opennxt.net.game.protocol.PacketFieldDeclaration

data class CamShake(val index: Int, val arg4: Int, val arg5: Int, val arg2: Int, val arg3: Int) : GamePacket {
    class Codec(fields: Array<PacketFieldDeclaration>) : DynamicGamePacketCodec<CamShake>(fields) {
        override fun fromMap(packet: Map<String, Any>): CamShake = CamShake(packet["index"] as Int, packet["arg4"] as Int, packet["arg5"] as Int, packet["arg2"] as Int, packet["arg3"] as Int)
        override fun toMap(packet: CamShake): Map<String, Any> = mapOf("index" to packet.index, "arg4" to packet.arg4, "arg5" to packet.arg5, "arg2" to packet.arg2, "arg3" to packet.arg3)
    }
}

package com.opennxt.net.game.clientprot.generated

import com.opennxt.net.game.GamePacket
import com.opennxt.net.game.pipeline.DynamicGamePacketCodec
import com.opennxt.net.game.protocol.PacketFieldDeclaration

data class IfButtont(val targetsub: Int, val selsub: Int, val targetobj: Int, val selobj: Int, val selhash: Int, val targethash: Int) : GamePacket {
    class Codec(fields: Array<PacketFieldDeclaration>) : DynamicGamePacketCodec<IfButtont>(fields) {
        override fun fromMap(packet: Map<String, Any>): IfButtont = IfButtont(packet["targetsub"] as Int, packet["selsub"] as Int, packet["targetobj"] as Int, packet["selobj"] as Int, packet["selhash"] as Int, packet["targethash"] as Int)
        override fun toMap(packet: IfButtont): Map<String, Any> = mapOf("targetsub" to packet.targetsub, "selsub" to packet.selsub, "targetobj" to packet.targetobj, "selobj" to packet.selobj, "selhash" to packet.selhash, "targethash" to packet.targethash)
    }
}

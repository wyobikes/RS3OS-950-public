package com.opennxt.net.game.serverprot.generated

import com.opennxt.net.game.GamePacket
import com.opennxt.net.game.pipeline.DynamicGamePacketCodec
import com.opennxt.net.game.protocol.PacketFieldDeclaration

data class IfSettargetparam(val to: Int, val from: Int, val param: Int, val component: Int) : GamePacket {
    class Codec(fields: Array<PacketFieldDeclaration>) : DynamicGamePacketCodec<IfSettargetparam>(fields) {
        override fun fromMap(packet: Map<String, Any>): IfSettargetparam = IfSettargetparam(packet["to"] as Int, packet["from"] as Int, packet["param"] as Int, packet["component"] as Int)
        override fun toMap(packet: IfSettargetparam): Map<String, Any> = mapOf("to" to packet.to, "from" to packet.from, "param" to packet.param, "component" to packet.component)
    }
}

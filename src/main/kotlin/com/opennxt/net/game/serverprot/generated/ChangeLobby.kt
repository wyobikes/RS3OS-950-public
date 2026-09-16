package com.opennxt.net.game.serverprot.generated

import com.opennxt.net.game.GamePacket
import com.opennxt.net.game.pipeline.DynamicGamePacketCodec
import com.opennxt.net.game.protocol.PacketFieldDeclaration

data class ChangeLobby(val host: String, val port: Int, val port2: Int, val port3: Int) : GamePacket {
    class Codec(fields: Array<PacketFieldDeclaration>) : DynamicGamePacketCodec<ChangeLobby>(fields) {
        override fun fromMap(packet: Map<String, Any>): ChangeLobby = ChangeLobby(packet["host"] as String, packet["port"] as Int, packet["port2"] as Int, packet["port3"] as Int)
        override fun toMap(packet: ChangeLobby): Map<String, Any> = mapOf("host" to packet.host, "port" to packet.port, "port2" to packet.port2, "port3" to packet.port3)
    }
}

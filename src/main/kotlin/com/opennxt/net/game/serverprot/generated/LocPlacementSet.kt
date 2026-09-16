package com.opennxt.net.game.serverprot.generated

import com.opennxt.net.game.GamePacket
import com.opennxt.net.game.pipeline.DynamicGamePacketCodec
import com.opennxt.net.game.protocol.PacketFieldDeclaration

data class LocPlacementSet(val enabled: Int, val hiddenops: Int, val cornerb: Int, val cornera: Int, val script: Int) : GamePacket {
    class Codec(fields: Array<PacketFieldDeclaration>) : DynamicGamePacketCodec<LocPlacementSet>(fields) {
        override fun fromMap(packet: Map<String, Any>): LocPlacementSet = LocPlacementSet(packet["enabled"] as Int, packet["hiddenops"] as Int, packet["cornerb"] as Int, packet["cornera"] as Int, packet["script"] as Int)
        override fun toMap(packet: LocPlacementSet): Map<String, Any> = mapOf("enabled" to packet.enabled, "hiddenops" to packet.hiddenops, "cornerb" to packet.cornerb, "cornera" to packet.cornera, "script" to packet.script)
    }
}

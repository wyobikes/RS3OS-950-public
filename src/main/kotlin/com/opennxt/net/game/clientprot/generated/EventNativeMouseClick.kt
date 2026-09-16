package com.opennxt.net.game.clientprot.generated

import com.opennxt.net.game.GamePacket
import com.opennxt.net.game.pipeline.DynamicGamePacketCodec
import com.opennxt.net.game.protocol.PacketFieldDeclaration

data class EventNativeMouseClick(val button: Int, val delta: Int, val coord: Int) : GamePacket {
    class Codec(fields: Array<PacketFieldDeclaration>) : DynamicGamePacketCodec<EventNativeMouseClick>(fields) {
        override fun fromMap(packet: Map<String, Any>): EventNativeMouseClick = EventNativeMouseClick(packet["button"] as Int, packet["delta"] as Int, packet["coord"] as Int)
        override fun toMap(packet: EventNativeMouseClick): Map<String, Any> = mapOf("button" to packet.button, "delta" to packet.delta, "coord" to packet.coord)
    }
}

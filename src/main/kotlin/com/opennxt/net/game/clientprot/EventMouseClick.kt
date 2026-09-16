package com.opennxt.net.game.clientprot

import com.opennxt.net.game.GamePacket
import com.opennxt.net.game.pipeline.DynamicGamePacketCodec
import com.opennxt.net.game.protocol.PacketFieldDeclaration

class EventMouseClick(val x: Int, val y: Int, val buttonDelta: Int) : GamePacket {
    val notLeftButton: Boolean get() = (buttonDelta and 0x8000) != 0

    val deltaMs: Int get() = buttonDelta and 0x7fff

    val deltaSaturated: Boolean get() = deltaMs == 0x7fff

    override fun toString(): String =
        "EVENT_MOUSE_CLICK(x=$x, y=$y, notLeftButton=$notLeftButton, deltaMs=$deltaMs" +
            (if (deltaSaturated) " SATURATED" else "") + ")"

    class Codec(fields: Array<PacketFieldDeclaration>) : DynamicGamePacketCodec<EventMouseClick>(fields) {
        override fun fromMap(packet: Map<String, Any>): EventMouseClick = EventMouseClick(
            packet["x"] as Int,
            packet["y"] as Int,
            packet["buttondelta"] as Int
        )

        override fun toMap(packet: EventMouseClick): Map<String, Any> = mapOf(
            "x" to packet.x,
            "y" to packet.y,
            "buttondelta" to packet.buttonDelta
        )
    }
}

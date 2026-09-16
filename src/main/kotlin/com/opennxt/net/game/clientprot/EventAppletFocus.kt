package com.opennxt.net.game.clientprot

import com.opennxt.net.game.GamePacket
import com.opennxt.net.game.pipeline.DynamicGamePacketCodec
import com.opennxt.net.game.protocol.PacketFieldDeclaration

class EventAppletFocus(val focused: Int) : GamePacket {
    val hasFocus: Boolean get() = focused != 0

    override fun toString(): String = "EVENT_APPLET_FOCUS(focused=$focused)"

    class Codec(fields: Array<PacketFieldDeclaration>) : DynamicGamePacketCodec<EventAppletFocus>(fields) {
        override fun fromMap(packet: Map<String, Any>): EventAppletFocus =
            EventAppletFocus(packet["focused"] as Int)

        override fun toMap(packet: EventAppletFocus): Map<String, Any> =
            mapOf("focused" to packet.focused)
    }
}

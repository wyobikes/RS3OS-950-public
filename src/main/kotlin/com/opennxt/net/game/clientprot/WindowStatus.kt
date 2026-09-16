package com.opennxt.net.game.clientprot

import com.opennxt.net.game.GamePacket
import com.opennxt.net.game.pipeline.DynamicGamePacketCodec
import com.opennxt.net.game.protocol.PacketFieldDeclaration

class WindowStatus(val mode: Int, val width: Int, val height: Int, val trailing: Int) : GamePacket {
    class Codec(fields: Array<PacketFieldDeclaration>) : DynamicGamePacketCodec<WindowStatus>(fields) {
        override fun fromMap(packet: Map<String, Any>): WindowStatus = WindowStatus(
            packet["mode"] as Int, packet["width"] as Int,
            packet["height"] as Int, packet["trailing"] as Int
        )

        override fun toMap(packet: WindowStatus): Map<String, Any> = mapOf(
            "mode" to packet.mode, "width" to packet.width,
            "height" to packet.height, "trailing" to packet.trailing
        )
    }

    override fun toString(): String =
        "WindowStatus(mode=$mode, ${width}x$height, trailing=$trailing)"
}

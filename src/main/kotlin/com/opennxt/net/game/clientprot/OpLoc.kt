package com.opennxt.net.game.clientprot

import com.opennxt.net.game.GamePacket
import com.opennxt.net.game.pipeline.DynamicGamePacketCodec
import com.opennxt.net.game.protocol.PacketFieldDeclaration

sealed class OpLoc(
    val option: Int,
    val x: Int,
    val y: Int,
    val id: Int,
    val ctrl: Int
) : GamePacket {
    val ctrlHeld: Boolean get() = (ctrl and 1) == 1

    override fun toString(): String =
        "OPLOC$option(loc=$id at ($x,$y), ctrl=$ctrl)"

    abstract class Codec<T : OpLoc>(fields: Array<PacketFieldDeclaration>) : DynamicGamePacketCodec<T>(fields) {
        protected abstract fun create(x: Int, y: Int, id: Int, ctrl: Int): T

        override fun fromMap(packet: Map<String, Any>): T = create(
            packet["x"] as Int, packet["y"] as Int, packet["id"] as Int, packet["ctrl"] as Int
        )

        override fun toMap(packet: T): Map<String, Any> = mapOf(
            "x" to packet.x, "y" to packet.y, "id" to packet.id, "ctrl" to packet.ctrl
        )
    }
}

class OpLoc1(x: Int, y: Int, id: Int, ctrl: Int) : OpLoc(1, x, y, id, ctrl) {
    class Codec(fields: Array<PacketFieldDeclaration>) : OpLoc.Codec<OpLoc1>(fields) {
        override fun create(x: Int, y: Int, id: Int, ctrl: Int) = OpLoc1(x, y, id, ctrl)
    }
}

class OpLoc2(x: Int, y: Int, id: Int, ctrl: Int) : OpLoc(2, x, y, id, ctrl) {
    class Codec(fields: Array<PacketFieldDeclaration>) : OpLoc.Codec<OpLoc2>(fields) {
        override fun create(x: Int, y: Int, id: Int, ctrl: Int) = OpLoc2(x, y, id, ctrl)
    }
}

class OpLoc3(x: Int, y: Int, id: Int, ctrl: Int) : OpLoc(3, x, y, id, ctrl) {
    class Codec(fields: Array<PacketFieldDeclaration>) : OpLoc.Codec<OpLoc3>(fields) {
        override fun create(x: Int, y: Int, id: Int, ctrl: Int) = OpLoc3(x, y, id, ctrl)
    }
}

class OpLoc4(x: Int, y: Int, id: Int, ctrl: Int) : OpLoc(4, x, y, id, ctrl) {
    class Codec(fields: Array<PacketFieldDeclaration>) : OpLoc.Codec<OpLoc4>(fields) {
        override fun create(x: Int, y: Int, id: Int, ctrl: Int) = OpLoc4(x, y, id, ctrl)
    }
}

class OpLoc5(x: Int, y: Int, id: Int, ctrl: Int) : OpLoc(5, x, y, id, ctrl) {
    class Codec(fields: Array<PacketFieldDeclaration>) : OpLoc.Codec<OpLoc5>(fields) {
        override fun create(x: Int, y: Int, id: Int, ctrl: Int) = OpLoc5(x, y, id, ctrl)
    }
}

class OpLoc6(x: Int, y: Int, id: Int, ctrl: Int) : OpLoc(6, x, y, id, ctrl) {
    class Codec(fields: Array<PacketFieldDeclaration>) : OpLoc.Codec<OpLoc6>(fields) {
        override fun create(x: Int, y: Int, id: Int, ctrl: Int) = OpLoc6(x, y, id, ctrl)
    }
}

package com.opennxt.net.game.clientprot

import com.opennxt.net.game.GamePacket
import com.opennxt.net.game.pipeline.DynamicGamePacketCodec
import com.opennxt.net.game.protocol.PacketFieldDeclaration

sealed class OpObj(
    val option: Int,
    val id: Int,
    val x: Int,
    val y: Int,
    val flags: Int
) : GamePacket {
    val ctrlHeld: Boolean get() = (flags and 1) == 1

    val inputBit: Boolean get() = (flags and 2) == 2

    override fun toString(): String = "OPOBJ$option(obj=$id at ($x,$y), flags=$flags)"

    abstract class Codec<T : OpObj>(fields: Array<PacketFieldDeclaration>) : DynamicGamePacketCodec<T>(fields) {
        protected abstract fun create(id: Int, x: Int, y: Int, flags: Int): T

        private val flagsKey: String = if (fields.any { it.key == "ctrl" }) "ctrl" else "flags"

        override fun fromMap(packet: Map<String, Any>): T = create(
            packet["id"] as Int, packet["x"] as Int, packet["y"] as Int, packet[flagsKey] as Int
        )

        override fun toMap(packet: T): Map<String, Any> = mapOf(
            "id" to packet.id, "x" to packet.x, "y" to packet.y, flagsKey to packet.flags
        )
    }
}

class OpObj1(id: Int, x: Int, y: Int, flags: Int) : OpObj(1, id, x, y, flags) {
    class Codec(fields: Array<PacketFieldDeclaration>) : OpObj.Codec<OpObj1>(fields) {
        override fun create(id: Int, x: Int, y: Int, flags: Int) = OpObj1(id, x, y, flags)
    }
}

class OpObj2(id: Int, x: Int, y: Int, flags: Int) : OpObj(2, id, x, y, flags) {
    class Codec(fields: Array<PacketFieldDeclaration>) : OpObj.Codec<OpObj2>(fields) {
        override fun create(id: Int, x: Int, y: Int, flags: Int) = OpObj2(id, x, y, flags)
    }
}

class OpObj3(id: Int, x: Int, y: Int, flags: Int) : OpObj(3, id, x, y, flags) {
    class Codec(fields: Array<PacketFieldDeclaration>) : OpObj.Codec<OpObj3>(fields) {
        override fun create(id: Int, x: Int, y: Int, flags: Int) = OpObj3(id, x, y, flags)
    }
}

class OpObj4(id: Int, x: Int, y: Int, flags: Int) : OpObj(4, id, x, y, flags) {
    class Codec(fields: Array<PacketFieldDeclaration>) : OpObj.Codec<OpObj4>(fields) {
        override fun create(id: Int, x: Int, y: Int, flags: Int) = OpObj4(id, x, y, flags)
    }
}

class OpObj5(id: Int, x: Int, y: Int, flags: Int) : OpObj(5, id, x, y, flags) {
    class Codec(fields: Array<PacketFieldDeclaration>) : OpObj.Codec<OpObj5>(fields) {
        override fun create(id: Int, x: Int, y: Int, flags: Int) = OpObj5(id, x, y, flags)
    }
}

class OpObj6(id: Int, x: Int, y: Int, flags: Int) : OpObj(6, id, x, y, flags) {
    class Codec(fields: Array<PacketFieldDeclaration>) : OpObj.Codec<OpObj6>(fields) {
        override fun create(id: Int, x: Int, y: Int, flags: Int) = OpObj6(id, x, y, flags)
    }
}

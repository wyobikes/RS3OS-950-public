package com.opennxt.net.game.clientprot

import com.opennxt.net.game.GamePacket
import com.opennxt.net.game.pipeline.DynamicGamePacketCodec
import com.opennxt.net.game.protocol.PacketFieldDeclaration

sealed class OpNpc(
    val option: Int,
    val index: Int,
    val ctrl: Int
) : GamePacket {
    val ctrlHeld: Boolean get() = (ctrl and 1) == 1

    override fun toString(): String = "OPNPC$option(npcIndex=$index, ctrl=$ctrl)"

    abstract class Codec<T : OpNpc>(fields: Array<PacketFieldDeclaration>) : DynamicGamePacketCodec<T>(fields) {
        protected abstract fun create(index: Int, ctrl: Int): T

        override fun fromMap(packet: Map<String, Any>): T =
            create(packet["index"] as Int, packet["ctrl"] as Int)

        override fun toMap(packet: T): Map<String, Any> =
            mapOf("index" to packet.index, "ctrl" to packet.ctrl)
    }
}

class OpNpc1(index: Int, ctrl: Int) : OpNpc(1, index, ctrl) {
    class Codec(fields: Array<PacketFieldDeclaration>) : OpNpc.Codec<OpNpc1>(fields) {
        override fun create(index: Int, ctrl: Int) = OpNpc1(index, ctrl)
    }
}

class OpNpc2(index: Int, ctrl: Int) : OpNpc(2, index, ctrl) {
    class Codec(fields: Array<PacketFieldDeclaration>) : OpNpc.Codec<OpNpc2>(fields) {
        override fun create(index: Int, ctrl: Int) = OpNpc2(index, ctrl)
    }
}

class OpNpc3(index: Int, ctrl: Int) : OpNpc(3, index, ctrl) {
    class Codec(fields: Array<PacketFieldDeclaration>) : OpNpc.Codec<OpNpc3>(fields) {
        override fun create(index: Int, ctrl: Int) = OpNpc3(index, ctrl)
    }
}

class OpNpc4(index: Int, ctrl: Int) : OpNpc(4, index, ctrl) {
    class Codec(fields: Array<PacketFieldDeclaration>) : OpNpc.Codec<OpNpc4>(fields) {
        override fun create(index: Int, ctrl: Int) = OpNpc4(index, ctrl)
    }
}

class OpNpc5(index: Int, ctrl: Int) : OpNpc(5, index, ctrl) {
    class Codec(fields: Array<PacketFieldDeclaration>) : OpNpc.Codec<OpNpc5>(fields) {
        override fun create(index: Int, ctrl: Int) = OpNpc5(index, ctrl)
    }
}

class OpNpc6(index: Int, ctrl: Int) : OpNpc(6, index, ctrl) {
    class Codec(fields: Array<PacketFieldDeclaration>) : OpNpc.Codec<OpNpc6>(fields) {
        override fun create(index: Int, ctrl: Int) = OpNpc6(index, ctrl)
    }
}

package com.opennxt.net.game.serverprot.ifaces

import com.opennxt.model.InterfaceHash
import com.opennxt.net.buf.DataOrder
import com.opennxt.net.buf.DataType
import com.opennxt.net.buf.GamePacketBuilder
import com.opennxt.net.buf.GamePacketReader
import com.opennxt.net.game.GamePacket
import com.opennxt.net.game.pipeline.GamePacketCodec

data class IfOpensubActiveNpc(
    val npcIndex: Int,
    val parent: InterfaceHash,
    val id: Int,
    val flag: Int = FLAG_BYTE
) : GamePacket {
    object Codec : GamePacketCodec<IfOpensubActiveNpc> {
        fun layout950(): Boolean =
            System.getProperty("opennxt.compat.ifOpensubActiveNpc") != "949" &&
                com.opennxt.model.entity.rendering.UpdateBlockType.experimentalBuild()

        fun encode950(packet: IfOpensubActiveNpc, buf: GamePacketBuilder) {
            buf.put(DataType.INT, 0)
            buf.put(DataType.SHORT, com.opennxt.net.buf.DataTransformation.ADD, packet.npcIndex)
            buf.put(DataType.SHORT, DataOrder.LITTLE, packet.id)
            buf.put(DataType.INT, 0)
            buf.put(DataType.INT, DataOrder.INVERSED_MIDDLE, packet.parent.hash)
            buf.put(DataType.INT, 0)
            buf.put(DataType.INT, 0)
            buf.put(DataType.BYTE, packet.flag)
        }

        fun decode950(buf: GamePacketReader): IfOpensubActiveNpc {
            buf.getSigned(DataType.INT)
            val index = buf.getUnsigned(DataType.SHORT, com.opennxt.net.buf.DataTransformation.ADD).toInt()
            val id = buf.getUnsigned(DataType.SHORT, DataOrder.LITTLE).toInt()
            buf.getSigned(DataType.INT)
            val parent = buf.getSigned(DataType.INT, DataOrder.INVERSED_MIDDLE).toInt()
            buf.getSigned(DataType.INT); buf.getSigned(DataType.INT)
            val flag = buf.getUnsigned(DataType.BYTE).toInt()
            return IfOpensubActiveNpc(index, InterfaceHash(parent), id, flag)
        }

        override fun encode(packet: IfOpensubActiveNpc, buf: GamePacketBuilder) {
            require(packet.npcIndex in 0..0xffff) { "npc index out of the u16 field: ${packet.npcIndex}" }
            require(packet.id in 0..0xffff) { "interface id out of the u16 field: ${packet.id}" }
            require(packet.flag in 0..0xff) { "flag is one byte: ${packet.flag}" }
            if (layout950()) { encode950(packet, buf); return }
            buf.put(DataType.INT, 0)
            buf.put(DataType.SHORT, DataOrder.LITTLE, packet.npcIndex)
            buf.put(DataType.INT, DataOrder.LITTLE, packet.parent.hash)
            buf.put(DataType.INT, 0)
            buf.put(DataType.INT, 0)
            buf.put(DataType.INT, 0)
            buf.put(DataType.SHORT, packet.id)
            buf.put(DataType.BYTE, packet.flag)
        }

        override fun decode(buf: GamePacketReader): IfOpensubActiveNpc {
            if (layout950()) return decode950(buf)
            buf.getSigned(DataType.INT)
            val index = buf.getUnsigned(DataType.SHORT, DataOrder.LITTLE).toInt()
            val parent = buf.getSigned(DataType.INT, DataOrder.LITTLE).toInt()
            buf.getSigned(DataType.INT); buf.getSigned(DataType.INT); buf.getSigned(DataType.INT)
            val id = buf.getUnsigned(DataType.SHORT).toInt()
            val flag = buf.getUnsigned(DataType.BYTE).toInt()
            return IfOpensubActiveNpc(index, InterfaceHash(parent), id, flag)
        }
    }

    companion object {
        const val FLAG_BYTE = 0xff

        const val TARGETING_INTERFACE = 1488

        const val TARGETING_COMPONENT = 4

        const val TARGET_INFO_INTERFACE = 1490

        val TARGET_INFO_PARENT: InterfaceHash
            get() = InterfaceHash((TARGETING_INTERFACE shl 16) or TARGETING_COMPONENT)

        fun targetInfo(npcIndex: Int): IfOpensubActiveNpc =
            IfOpensubActiveNpc(npcIndex, TARGET_INFO_PARENT, TARGET_INFO_INTERFACE)
    }
}

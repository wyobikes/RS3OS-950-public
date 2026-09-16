package com.opennxt.model.entity.rendering.npc.blocks

import com.opennxt.model.entity.rendering.npc.NpcUpdateBlock
import com.opennxt.model.entity.rendering.npc.NpcUpdateBlockType
import com.opennxt.net.buf.DataOrder
import com.opennxt.net.buf.DataTransformation
import com.opennxt.net.buf.DataType
import com.opennxt.net.buf.GamePacketBuilder

class NpcLifepointsBlock(val entries: List<Entry>) : NpcUpdateBlock(NpcUpdateBlockType.LIFEPOINTS) {
    data class Entry(val current: Int, val maximum: Int, val kind: Int = KIND_WIRE_BYTE) {
        init {
            require(kind in 0..0xff) { "kind is one byte; got $kind" }
            require(current in 0..MAX_CURRENT) {
                "current is a 4-byte field; got $current"
            }
            require(maximum in 0..MAX_MAXIMUM) {
                "maximum is a 3-byte field and cannot exceed $MAX_MAXIMUM; got $maximum"
            }
            require(current <= maximum) {
                "current ($current) above maximum ($maximum) draws a bar longer than its track"
            }
        }
    }

    init {
        require(entries.isNotEmpty()) { "a lifepoints block with no entries carries nothing" }
        require(entries.size <= MAX_ENTRIES) {
            "the entry count is one byte; got ${entries.size}"
        }
    }

    val width: Int get() = 1 + ENTRY_WIDTH * entries.size

    override fun encode(buffer: GamePacketBuilder) {
        buffer.put(DataType.BYTE, entries.size)
        for (entry in entries) {
            buffer.put(DataType.BYTE, entry.kind)
            buffer.put(DataType.INT, DataOrder.INVERSED_MIDDLE, entry.current)
            putMediumMiddle(buffer, entry.maximum)
        }
    }

    override fun encode950(buffer: GamePacketBuilder) {
        buffer.put(DataType.BYTE, entries.size)
        for (entry in entries) {
            buffer.put(DataType.BYTE, DataTransformation.ADD, entry.kind)
            buffer.put(DataType.INT, DataOrder.LITTLE, entry.current)
            buffer.put(DataType.BYTE, (entry.maximum shr 16) and 0xff)
            buffer.put(DataType.BYTE, entry.maximum and 0xff)
            buffer.put(DataType.BYTE, (entry.maximum shr 8) and 0xff)
        }
    }

    companion object {
        const val KIND_WIRE_BYTE = 3

        const val MAX_ENTRIES = 255

        const val ENTRY_WIDTH = 8

        const val MAX_CURRENT = Int.MAX_VALUE

        const val MAX_MAXIMUM = 0xFFFFFF

        fun putMediumMiddle(buffer: GamePacketBuilder, value: Int) {
            buffer.put(DataType.BYTE, (value shr 8) and 0xff)
            buffer.put(DataType.BYTE, (value shr 16) and 0xff)
            buffer.put(DataType.BYTE, value and 0xff)
        }

        fun single(current: Int, maximum: Int): NpcLifepointsBlock =
            NpcLifepointsBlock(listOf(Entry(current, maximum)))
    }
}

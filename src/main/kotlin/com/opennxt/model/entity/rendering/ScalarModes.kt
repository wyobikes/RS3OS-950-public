package com.opennxt.model.entity.rendering

import com.opennxt.net.buf.DataOrder
import com.opennxt.net.buf.DataTransformation
import com.opennxt.net.buf.DataType
import com.opennxt.net.buf.GamePacketBuilder

object ScalarModes {
    const val MODES = 4

    private fun requireMode(mode: Int, reader: String) {
        require(mode in 0 until MODES) {
            "mode $mode is not supported by $reader: the client reads 0 and consumes no " +
                "bytes, so it cannot be encoded as a value"
        }
    }

    fun putU8(buffer: GamePacketBuilder, mode: Int, value: Int) {
        requireMode(mode, "the u8 reader")
        buffer.put(DataType.BYTE, u8Transform(mode), value)
    }

    fun u8Transform(mode: Int): DataTransformation {
        requireMode(mode, "the u8 reader")
        return when (mode) {
            0 -> DataTransformation.NONE
            1 -> DataTransformation.ADD
            2 -> DataTransformation.NEGATE
            else -> DataTransformation.SUBTRACT
        }
    }

    fun putU16(buffer: GamePacketBuilder, mode: Int, value: Int) {
        requireMode(mode, "the u16 reader")
        val order = if (mode == 0 || mode == 2) DataOrder.BIG else DataOrder.LITTLE
        val transform = if (mode >= 2) DataTransformation.ADD else DataTransformation.NONE
        buffer.put(DataType.SHORT, order, transform, value)
    }

    fun putU24(buffer: GamePacketBuilder, mode: Int, value: Int) {
        requireMode(mode, "the u24 reader")
        val b = intArrayOf((value ushr 16) and 0xff, (value ushr 8) and 0xff, value and 0xff)
        val wire = when (mode) {
            0 -> intArrayOf(b[0], b[1], b[2])
            1 -> intArrayOf(b[2], b[1], b[0])
            2 -> intArrayOf(b[0], b[2], b[1])
            else -> intArrayOf(b[1], b[0], b[2])
        }
        for (v in wire) buffer.put(DataType.BYTE, v)
    }

    fun putU32(buffer: GamePacketBuilder, mode: Int, value: Int) {
        requireMode(mode, "the u32 reader")
        buffer.put(DataType.INT, u32Order(mode), value)
    }

    fun u32Order(mode: Int): DataOrder {
        requireMode(mode, "the u32 reader")
        return when (mode) {
            0 -> DataOrder.BIG
            1 -> DataOrder.LITTLE
            2 -> DataOrder.MIDDLE
            else -> DataOrder.INVERSED_MIDDLE
        }
    }

    fun putBarSigned(buffer: GamePacketBuilder, value: Int) {
        require(value in MIN_BAR_SIGNED..MAX_BAR_SIGNED) {
            "the bar's signed smart carries $MIN_BAR_SIGNED..$MAX_BAR_SIGNED" +
                "; got $value"
        }
        if (value <= 126) {
            buffer.put(DataType.BYTE, value + 1)
        } else {
            buffer.put(DataType.SHORT, DataOrder.BIG, (value - 0x7fff) and 0xffff)
        }
    }

    const val MIN_BAR_SIGNED = -1

    const val MAX_BAR_SIGNED = 32766

    fun barSignedLength(value: Int): Int = if (value <= 126) 1 else 2
}

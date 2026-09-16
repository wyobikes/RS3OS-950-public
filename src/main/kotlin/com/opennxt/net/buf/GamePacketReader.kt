package com.opennxt.net.buf

import com.opennxt.ext.readString
import io.netty.buffer.ByteBuf

class GamePacketReader(
    val buffer: ByteBuf
) {
    private var bitIndex: Int = 0

    private var mode = AccessMode.BYTE_ACCESS

    fun getBit(): Int = getBits(1)

    fun getLength(): Int {
        checkByteAccess()
        return buffer.writableBytes()
    }

    fun getSignedSmart(): Int {
        checkByteAccess()
        val peek = buffer.getByte(buffer.readerIndex()).toInt()
        return if (peek < 128) {
            buffer.readByte() - 64
        } else buffer.readShort() - 49152
    }

    fun getString(): String {
        checkByteAccess()
        return buffer.readString()
    }

    private fun checkBitAccess() {
        if (mode !== AccessMode.BIT_ACCESS) {
            throw IllegalArgumentException("For bit-based calls to work, the mode must be bit access.")
        }
    }

    private fun checkByteAccess() {
        if (mode !== AccessMode.BYTE_ACCESS) {
            throw IllegalArgumentException("For byte-based calls to work, the mode must be byte access.")
        }
    }

    private operator fun get(type: DataType, order: DataOrder, transformation: DataTransformation): Long {
        checkByteAccess()
        var longValue: Long = 0
        val length = type.bytes
        when (order) {
            DataOrder.BIG -> for (i in length - 1 downTo 0) {
                if (i == 0 && transformation !== DataTransformation.NONE) {
                    if (transformation === DataTransformation.ADD) {
                        longValue = longValue or (buffer.readByte().toLong() - 128 and 0xFFL)
                    } else if (transformation === DataTransformation.NEGATE) {
                        longValue = longValue or (-buffer.readByte().toLong() and 0xFFL)
                    } else if (transformation === DataTransformation.SUBTRACT) {
                        longValue = longValue or (128 - buffer.readByte().toLong() and 0xFFL)
                    } else {
                        throw IllegalArgumentException("Unknown transformation.")
                    }
                } else {
                    longValue = longValue or (buffer.readByte().toLong() and 0xFFL shl i * 8)
                }
            }
            DataOrder.INVERSED_MIDDLE -> {
                if (transformation !== DataTransformation.NONE) {
                    throw IllegalArgumentException("Inversed middle endian cannot be transformed.")
                }
                if (type !== DataType.INT) {
                    throw IllegalArgumentException("Inversed middle endian can only be used with an integer.")
                }
                longValue = longValue or (buffer.readByte().toInt() and 0xFF shl 16).toLong()
                longValue = longValue or (buffer.readByte().toInt() and 0xFF shl 24).toLong()
                longValue = longValue or (buffer.readByte().toInt() and 0xFF).toLong()
                longValue = longValue or (buffer.readByte().toInt() and 0xFF shl 8).toLong()
            }
            DataOrder.LITTLE -> for (i in 0 until length) {
                if (i == 0 && transformation !== DataTransformation.NONE) {
                    if (transformation === DataTransformation.ADD) {
                        longValue = longValue or (buffer.readByte().toLong() - 128 and 0xFFL)
                    } else if (transformation === DataTransformation.NEGATE) {
                        longValue = longValue or (-buffer.readByte().toLong() and 0xFFL)
                    } else if (transformation === DataTransformation.SUBTRACT) {
                        longValue = longValue or (128 - buffer.readByte().toLong() and 0xFFL)
                    } else {
                        throw IllegalArgumentException("Unknown transformation.")
                    }
                } else {
                    longValue = longValue or (buffer.readByte().toLong() and 0xFFL shl i * 8)
                }
            }
            DataOrder.MIDDLE -> {
                if (transformation !== DataTransformation.NONE) {
                    throw IllegalArgumentException("Middle endian cannot be transformed.")
                }
                if (type !== DataType.INT) {
                    throw IllegalArgumentException("Middle endian can only be used with an integer.")
                }
                longValue = longValue or (buffer.readByte().toInt() and 0xFF shl 8).toLong()
                longValue = longValue or (buffer.readByte().toInt() and 0xFF).toLong()
                longValue = longValue or (buffer.readByte().toInt() and 0xFF shl 24).toLong()
                longValue = longValue or (buffer.readByte().toInt() and 0xFF shl 16).toLong()
            }
            else -> throw IllegalArgumentException("Unknown order.")
        }
        return longValue
    }

    fun getBits(amount: Int): Int {
        var amount = amount
        checkBitAccess()

        var bytePos = bitIndex shr 3
        var bitOffset = 8 - (bitIndex and 7)
        var value = 0
        bitIndex += amount

        while (amount > bitOffset) {
            value += buffer.getByte(bytePos++).toInt() and DataConstants.BIT_MASK[bitOffset] shl amount - bitOffset
            amount -= bitOffset
            bitOffset = 8
        }
        if (amount == bitOffset) {
            value += buffer.getByte(bytePos).toInt() and DataConstants.BIT_MASK[bitOffset]
        } else {
            value += buffer.getByte(bytePos).toInt() shr bitOffset - amount and DataConstants.BIT_MASK[amount]
        }
        return value
    }

    fun getBytes(bytes: ByteArray) {
        checkByteAccess()
        for (i in bytes.indices) {
            bytes[i] = buffer.readByte()
        }
    }

    fun getBytes(transformation: DataTransformation, bytes: ByteArray) {
        if (transformation === DataTransformation.NONE) {
            getBytesReverse(bytes)
        } else {
            for (i in bytes.indices) {
                bytes[i] = getSigned(DataType.BYTE, transformation).toByte()
            }
        }
    }

    fun getBytesReverse(bytes: ByteArray) {
        checkByteAccess()
        for (i in bytes.indices.reversed()) {
            bytes[i] = buffer.readByte()
        }
    }

    fun getBytesReverse(transformation: DataTransformation, bytes: ByteArray) {
        if (transformation === DataTransformation.NONE) {
            getBytesReverse(bytes)
        } else {
            for (i in bytes.indices.reversed()) {
                bytes[i] = getSigned(DataType.BYTE, transformation).toByte()
            }
        }
    }

    @JvmOverloads
    fun getSigned(
        type: DataType,
        order: DataOrder = DataOrder.BIG,
        transformation: DataTransformation = DataTransformation.NONE
    ): Long {
        var longValue = get(type, order, transformation)
        if (type !== DataType.LONG) {
            val max = (Math.pow(2.0, type.bytes.toDouble() * 8 - 1) - 1).toInt()
            if (longValue > max) {
                longValue -= ((max + 1) * 2).toLong()
            }
        }
        return longValue
    }

    fun getSigned(type: DataType, transformation: DataTransformation): Long {
        return getSigned(type, DataOrder.BIG, transformation)
    }

    @JvmOverloads
    fun getUnsigned(
        type: DataType,
        order: DataOrder = DataOrder.BIG,
        transformation: DataTransformation = DataTransformation.NONE
    ): Long {
        val longValue = get(type, order, transformation)
        return longValue and -0x1L
    }

    fun getUnsigned(type: DataType, transformation: DataTransformation): Long {
        return getUnsigned(type, DataOrder.BIG, transformation)
    }

    fun switchToBitAccess() {
        if (mode === AccessMode.BIT_ACCESS) {
            return
        }

        mode = AccessMode.BIT_ACCESS
        bitIndex = buffer.readerIndex() * 8
    }

    fun switchToByteAccess() {
        if (mode === AccessMode.BYTE_ACCESS) {
            return
        }

        mode = AccessMode.BYTE_ACCESS
        buffer.readerIndex((bitIndex + 7) / 8)
    }

}

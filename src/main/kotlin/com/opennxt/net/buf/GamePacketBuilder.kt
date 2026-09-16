package com.opennxt.net.buf

import com.opennxt.ext.writeString
import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled

class GamePacketBuilder(val buffer: ByteBuf = Unpooled.buffer()) {
    private var bitIndex: Int = 0

    private var mode = AccessMode.BYTE_ACCESS

    val length: Int
        get() {
            checkByteAccess()
            return buffer.writerIndex()
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

    fun put(type: DataType, order: DataOrder, transformation: DataTransformation, value: Number) {
        checkByteAccess()
        val longValue = value.toLong()
        val length = type.bytes
        when (order) {
            DataOrder.BIG -> for (i in length - 1 downTo 0) {
                if (i == 0 && transformation !== DataTransformation.NONE) {
                    if (transformation === DataTransformation.ADD) {
                        buffer.writeByte((longValue + 128).toByte().toInt())
                    } else if (transformation === DataTransformation.NEGATE) {
                        buffer.writeByte((-longValue).toByte().toInt())
                    } else if (transformation === DataTransformation.SUBTRACT) {
                        buffer.writeByte((128 - longValue).toByte().toInt())
                    } else {
                        throw IllegalArgumentException("Unknown transformation.")
                    }
                } else {
                    buffer.writeByte((longValue shr i * 8).toByte().toInt())
                }
            }
            DataOrder.INVERSED_MIDDLE -> {
                if (transformation !== DataTransformation.NONE) {
                    throw IllegalArgumentException("Inversed middle endian cannot be transformed.")
                }

                if (type !== DataType.INT) {
                    throw IllegalArgumentException("Inversed middle endian can only be used with an integer.")
                }
                buffer.writeByte((longValue shr 16).toByte().toInt())
                buffer.writeByte((longValue shr 24).toByte().toInt())
                buffer.writeByte(longValue.toByte().toInt())
                buffer.writeByte((longValue shr 8).toByte().toInt())
            }
            DataOrder.LITTLE -> for (i in 0 until length) {
                if (i == 0 && transformation !== DataTransformation.NONE) {
                    if (transformation === DataTransformation.ADD) {
                        buffer.writeByte((longValue + 128).toByte().toInt())
                    } else if (transformation === DataTransformation.NEGATE) {
                        buffer.writeByte((-longValue).toByte().toInt())
                    } else if (transformation === DataTransformation.SUBTRACT) {
                        buffer.writeByte((128 - longValue).toByte().toInt())
                    } else {
                        throw IllegalArgumentException("Unknown transformation.")
                    }
                } else {
                    buffer.writeByte((longValue shr i * 8).toByte().toInt())
                }
            }
            DataOrder.MIDDLE -> {
                if (transformation !== DataTransformation.NONE) {
                    throw IllegalArgumentException("Middle endian cannot be transformed.")
                }

                if (type !== DataType.INT) {
                    throw IllegalArgumentException("Middle endian can only be used with an integer.")
                }

                buffer.writeByte((longValue shr 8).toByte().toInt())
                buffer.writeByte(longValue.toByte().toInt())
                buffer.writeByte((longValue shr 24).toByte().toInt())
                buffer.writeByte((longValue shr 16).toByte().toInt())
            }
            else -> throw IllegalArgumentException("Unknown order.")
        }
    }

    fun put(type: DataType, order: DataOrder, value: Number) {
        put(type, order, DataTransformation.NONE, value)
    }

    fun put(type: DataType, transformation: DataTransformation, value: Number) {
        put(type, DataOrder.BIG, transformation, value)
    }

    fun put(type: DataType, value: Number) {
        put(type, DataOrder.BIG, DataTransformation.NONE, value)
    }

    fun putBit(flag: Boolean) {
        putBit(if (flag) 1 else 0)
    }

    fun putBit(value: Int) {
        putBits(1, value)
    }

    fun putBits(numBits: Int, value: Int) {
        var numBits = numBits
        checkBitAccess()

        var bytePos = bitIndex shr 3
        var bitOffset = 8 - (bitIndex and 7)
        bitIndex += numBits

        var requiredSpace = bytePos - buffer.writerIndex() + 1
        requiredSpace += (numBits + 7) / 8
        buffer.ensureWritable(requiredSpace)

        while (numBits > bitOffset) {
            var tmp = buffer.getByte(bytePos).toInt()
            tmp = tmp and DataConstants.BIT_MASK[bitOffset].inv()
            tmp = tmp or (value shr numBits - bitOffset and DataConstants.BIT_MASK[bitOffset])
            buffer.setByte(bytePos++, tmp)
            numBits -= bitOffset
            bitOffset = 8
        }
        if (numBits == bitOffset) {
            var tmp = buffer.getByte(bytePos).toInt()
            tmp = tmp and DataConstants.BIT_MASK[bitOffset].inv()
            tmp = tmp or (value and DataConstants.BIT_MASK[bitOffset])
            buffer.setByte(bytePos, tmp)
        } else {
            var tmp = buffer.getByte(bytePos).toInt()
            tmp = tmp and (DataConstants.BIT_MASK[numBits] shl bitOffset - numBits).inv()
            tmp = tmp or (value and DataConstants.BIT_MASK[numBits] shl bitOffset - numBits)
            buffer.setByte(bytePos, tmp)
        }
    }

    fun putBytes(bytes: ByteArray) {
        buffer.writeBytes(bytes)
    }

    fun putBytes(buffer: ByteBuf) {
        val bytes = ByteArray(buffer.readableBytes())
        buffer.markReaderIndex()
        try {
            buffer.readBytes(bytes)
        } finally {
            buffer.resetReaderIndex()
        }
        putBytes(bytes)
    }

    fun putBytes(transformation: DataTransformation, bytes: ByteArray) {
        if (transformation === DataTransformation.NONE) {
            putBytes(bytes)
        } else {
            for (b in bytes) {
                put(DataType.BYTE, transformation, b)
            }
        }
    }

    fun putBytesReverse(bytes: ByteArray) {
        checkByteAccess()
        for (i in bytes.indices.reversed()) {
            buffer.writeByte(bytes[i].toInt())
        }
    }

    fun putBytesReverse(buffer: ByteBuf) {
        val bytes = ByteArray(buffer.readableBytes())
        buffer.markReaderIndex()
        try {
            buffer.readBytes(bytes)
        } finally {
            buffer.resetReaderIndex()
        }
        putBytesReverse(bytes)
    }

    fun putBytesReverse(transformation: DataTransformation, bytes: ByteArray) {
        if (transformation === DataTransformation.NONE) {
            putBytesReverse(bytes)
        } else {
            for (i in bytes.indices.reversed()) {
                put(DataType.BYTE, transformation, bytes[i])
            }
        }
    }

    fun putSmart(value: Int) {
        checkByteAccess()
        if (value >= 128) {
            buffer.writeShort(value + 32768)
        } else {
            buffer.writeByte(value)
        }
    }

    fun putLargeSmart(value: Int) {
        checkByteAccess()
        if (value >= java.lang.Short.MAX_VALUE) {
            buffer.writeInt(value - Integer.MAX_VALUE - 1)
        } else {
            buffer.writeShort(if (value >= 0) value else 32767)
        }
    }

    fun putPrefixedString(str: String) {
        checkByteAccess()

        buffer.writeByte(0)
        putString(str)
    }

    fun putString(str: String) {
        checkByteAccess()

        buffer.writeString(str)
    }

    fun switchToBitAccess() {
        if (mode === AccessMode.BIT_ACCESS) {
            return
        }

        mode = AccessMode.BIT_ACCESS
        bitIndex = buffer.writerIndex() * 8
    }

    fun switchToByteAccess() {
        if (mode === AccessMode.BYTE_ACCESS) {
            return
        }

        mode = AccessMode.BYTE_ACCESS
        buffer.writerIndex((bitIndex + 7) / 8)
    }

    fun writerIndex(): Int = buffer.writerIndex()

}

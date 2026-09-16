package com.opennxt.net.game.serverprot

import com.opennxt.net.buf.DataType
import com.opennxt.net.buf.GamePacketBuilder
import com.opennxt.net.buf.GamePacketReader
import com.opennxt.net.game.GamePacket
import com.opennxt.net.game.pipeline.GamePacketCodec

class PlayerSnapshot(val slot: Int, val flag: Int, val record: ByteArray) : GamePacket {
    override fun equals(other: Any?): Boolean =
        other is PlayerSnapshot && other.slot == slot && other.flag == flag && other.record.contentEquals(record)

    override fun hashCode(): Int = (slot * 31 + flag) * 31 + record.contentHashCode()

    override fun toString(): String = "PlayerSnapshot(slot=$slot, flag=$flag, record=${record.size} bytes)"

    object Codec : GamePacketCodec<PlayerSnapshot> {
        override fun encode(packet: PlayerSnapshot, buf: GamePacketBuilder) {
            buf.put(DataType.BYTE, packet.slot)
            buf.put(DataType.BYTE, packet.flag)
            buf.putBytes(packet.record)
        }

        override fun decode(buf: GamePacketReader): PlayerSnapshot {
            val slot = buf.getUnsigned(DataType.BYTE).toInt()
            val flag = buf.getSigned(DataType.BYTE).toInt()
            val rest = ByteArray(buf.buffer.readableBytes())
            buf.getBytes(rest)
            return PlayerSnapshot(slot, flag, rest)
        }
    }

    companion object {
        const val MODEL_OVERRIDE = 1

        fun varint(bytes: ByteArray, at: Int): Pair<Int, Int>? {
            var p = at
            var value = 0
            var shift = 0
            while (true) {
                if (p >= bytes.size || shift > 28) return null
                val b = bytes[p].toInt() and 0xff
                p++
                value = value or ((b and 0x7f) shl shift)
                shift += 7
                if (b <= 0x7f) return value to p
            }
        }

        fun recordLength(bytes: ByteArray, offset: Int, slotCount: Int): Int? {
            var p = offset
            var override = false
            for (i in 0 until slotCount) {
                val (v, next) = varint(bytes, p) ?: return null
                p = next
                if (v == 0) continue
                if (i == 0 && v == MODEL_OVERRIDE) {
                    if (p >= bytes.size) return null
                    val long = (bytes[p].toInt() and 0xff) >= 0x80
                    if (long) {
                        p += 4
                    } else {
                        if (p + 2 > bytes.size) return null
                        val short = ((bytes[p].toInt() and 0xff) shl 8) or (bytes[p + 1].toInt() and 0xff)
                        p += 2
                        override = short != 0x7fff
                    }
                    if (long) override = true
                    p += 1
                    break
                }
            }
            if (!override) {
                if (p + 2 > bytes.size) return null
                val mask = ((bytes[p].toInt() and 0xff) shl 8) or (bytes[p + 1].toInt() and 0xff)
                p += 2
                if (mask != 0) return null
            }
            p += 10 + 10 + 2
            return if (p <= bytes.size) p - offset else null
        }

        fun slotValues(bytes: ByteArray, offset: Int, slotCount: Int): IntArray? {
            val out = IntArray(slotCount)
            var p = offset
            for (i in 0 until slotCount) {
                val (v, next) = varint(bytes, p) ?: return null
                if (i == 0 && v == MODEL_OVERRIDE) return null
                out[i] = v
                p = next
            }
            return out
        }
    }
}

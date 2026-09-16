package com.opennxt.util

import com.opennxt.OpenNXT
import com.opennxt.filesystem.Container
import com.opennxt.filesystem.Filesystem
import com.opennxt.net.buf.GamePacketBuilder
import com.opennxt.net.buf.GamePacketReader
import com.opennxt.ext.readSmartShort
import com.opennxt.ext.writeSmartShort
import mu.KotlinLogging

class HuffmanCodec private constructor(
    val lengths: IntArray,
    val masks: IntArray
) {
    private val child: IntArray

    val internalNodes: Int

    init {
        require(lengths.size == masks.size) { "lengths/masks disagree: ${lengths.size} vs ${masks.size}" }

        var cap = 2
        for (l in lengths) cap += l
        child = IntArray(cap * 2)
        var next = 1
        for (sym in lengths.indices) {
            val bits = lengths[sym]
            if (bits == 0) continue
            var node = 1
            for (b in bits - 1 downTo 0) {
                val bit = (masks[sym] ushr (31 - (bits - 1 - b))) and 1
                val slot = node * 2 + bit
                if (b == 0) {
                    require(child[slot] == 0) {
                        "huffman table is not prefix-free: symbol $sym collides at depth ${bits - b}"
                    }
                    child[slot] = -(sym + 1)
                } else {
                    var nxt = child[slot]
                    require(nxt >= 0) {
                        "huffman table is not prefix-free: symbol $sym extends a leaf at depth ${bits - b}"
                    }
                    if (nxt == 0) {
                        next++
                        nxt = next
                        child[slot] = nxt
                    }
                    node = nxt
                }
            }
        }
        internalNodes = next
    }

    val symbolCount: Int get() = lengths.count { it != 0 }

    fun kraftSum(): Double {
        var sum = 0.0
        for (l in lengths) if (l != 0) sum += Math.pow(2.0, -l.toDouble())
        return sum
    }

    fun encode(text: String): ByteArray {
        val symbols = toCp1252(text)
        var bits = 0
        for (b in symbols) {
            val len = lengths[b.toInt() and 0xff]
            require(len != 0) { "no huffman code for symbol ${b.toInt() and 0xff}" }
            bits += len
        }

        val out = ByteArray((bits + 7) / 8)
        var pos = 0
        for (b in symbols) {
            val sym = b.toInt() and 0xff
            var word = masks[sym]
            var remaining = lengths[sym]
            while (remaining > 0) {
                val byteIndex = pos shr 3
                val room = 8 - (pos and 7)
                val take = if (remaining < room) remaining else room
                val chunk = (word ushr (32 - take)) and ((1 shl take) - 1)
                out[byteIndex] = (out[byteIndex].toInt() or (chunk shl (room - take))).toByte()
                word = word shl take
                remaining -= take
                pos += take
            }
        }
        return out
    }

    fun write(buf: GamePacketBuilder, text: String) {
        val bytes = encode(text)
        val count = text.length
        require(count in 0..0x7fff) { "huffman charCount does not fit a smart: $count" }
        buf.buffer.writeSmartShort(count)
        buf.putBytes(bytes)
    }

    fun decode(data: ByteArray, offset: Int, charCount: Int): Pair<String, Int> {
        require(charCount >= 0) { "negative charCount: $charCount" }
        if (charCount == 0) return "" to 0

        val sb = StringBuilder(charCount)
        var node = 1
        var bits = 0
        var produced = 0
        val limitBits = (data.size - offset) * 8
        while (produced < charCount) {
            if (bits >= limitBits) {
                throw IllegalArgumentException(
                    "huffman bitstream ran out after $produced of $charCount symbols " +
                        "(${data.size - offset} bytes available)"
                )
            }
            val byte = data[offset + (bits shr 3)].toInt() and 0xff
            val bit = (byte ushr (7 - (bits and 7))) and 1
            bits++
            val nxt = child[node * 2 + bit]
            if (nxt == 0) {
                throw IllegalArgumentException(
                    "huffman bitstream took an undefined branch after $produced symbol(s), bit $bits"
                )
            }
            if (nxt < 0) {
                sb.append(TextUtils.cp1252ToChar((-nxt - 1).toByte()))
                produced++
                node = 1
            } else {
                node = nxt
            }
        }
        return sb.toString() to (bits + 7) / 8
    }

    fun readFrom(reader: GamePacketReader): String {
        val count = reader.buffer.readSmartShort()
        if (count == 0) return ""
        val available = reader.buffer.readableBytes()
        val bytes = ByteArray(available)
        reader.buffer.getBytes(reader.buffer.readerIndex(), bytes)
        val (text, consumed) = decode(bytes, 0, count)
        reader.buffer.readerIndex(reader.buffer.readerIndex() + consumed)
        return text
    }

    private fun toCp1252(text: String): ByteArray {
        val out = ByteArray(text.length)
        for (i in text.indices) out[i] = TextUtils.charToCp1252(text[i])
        return out
    }

    companion object {
        private val logger = KotlinLogging.logger { }

        const val INDEX = 10

        const val NAME = "huffman"

        const val SYMBOLS = 256

        fun fromLengths(raw: ByteArray): HuffmanCodec {
            val lengths = IntArray(raw.size) { raw[it].toInt() and 0xff }
            return fromLengths(lengths)
        }

        fun fromLengths(lengths: IntArray): HuffmanCodec {
            val masks = IntArray(lengths.size)
            val next = IntArray(33)

            for (sym in lengths.indices) {
                val bits = lengths[sym]
                if (bits == 0) continue
                require(bits in 1..32) { "symbol $sym has an impossible bit length $bits" }

                val bit = 1 shl (32 - bits)
                val code = next[bits]
                masks[sym] = code

                var newCode: Int
                if (code and bit != 0) {
                    newCode = next[bits - 1]
                } else {
                    newCode = code or bit
                    var k = bits - 1
                    while (k >= 1) {
                        val v = next[k]
                        if (v != code) break
                        val m = 1 shl (32 - k)
                        if (v and m != 0) {
                            next[k] = next[k - 1]
                            break
                        }
                        next[k] = v or m
                        k--
                    }
                }
                next[bits] = newCode

                for (i in bits + 1..32) {
                    if (next[i] == code) next[i] = newCode
                }
            }

            return HuffmanCodec(lengths, masks)
        }

        fun loadLengths(filesystem: Filesystem): ByteArray? {
            val raw = filesystem.read(INDEX, NAME) ?: return null
            val data = try {
                Container.decode(raw).data
            } catch (t: Throwable) {
                logger.warn(t) { "js5[$INDEX, '$NAME'] would not decode as a container" }
                return null
            }

            if (data.size != SYMBOLS) {
                logger.warn {
                    "js5[$INDEX, '$NAME'] is ${data.size} bytes, expected $SYMBOLS; ignoring it"
                }
                return null
            }
            return data
        }

        fun load(filesystem: Filesystem): HuffmanCodec? {
            val raw = loadLengths(filesystem) ?: return null
            val codec = fromLengths(raw)
            logger.info {
                "Loaded chat huffman table: ${codec.symbolCount} symbols, " +
                    "lengths ${codec.lengths.filter { it != 0 }.minOrNull()}..${codec.lengths.maxOrNull()}, " +
                    "kraft sum ${codec.kraftSum()}"
            }
            return codec
        }

        @Volatile
        private var cached: HuffmanCodec? = null

        @Volatile
        private var attempted = false

        @Synchronized
        fun instance(): HuffmanCodec? {
            if (attempted) return cached
            attempted = true
            cached = try {
                load(OpenNXT.filesystem)
            } catch (t: Throwable) {
                logger.warn(t) { "Could not load the chat huffman table" }
                null
            }
            if (cached == null) {
                logger.warn {
                    "No chat huffman table in cache (js5[$INDEX, '$NAME']); public chat is disabled"
                }
            }
            return cached
        }

        @Synchronized
        fun forget() {
            cached = null
            attempted = false
        }

        @Synchronized
        fun install(codec: HuffmanCodec?) {
            cached = codec
            attempted = true
        }
    }
}

package com.opennxt.filesystem

import com.opennxt.filesystem.compression.BZIP2Compression
import com.opennxt.filesystem.compression.ContainerCompression
import com.opennxt.filesystem.compression.GZIPCompression
import com.opennxt.filesystem.compression.LZMACompression
import java.nio.ByteBuffer
import java.util.zip.Inflater

class Container(
    var data: ByteArray,
    var compression: ContainerCompression = ContainerCompression.LZMA,
    var version: Int = -1
) {
    companion object {
        private val ZLB_MAGIC = byteArrayOf(0x5A, 0x4C, 0x42, 0x01)

        private const val MAX_ZLB_INFLATED = 512 * 1024 * 1024

        fun unwrapZlbOrNull(data: ByteBuffer): ByteBuffer? = unwrapZlb(data)

        private val reframeZlb: Boolean =
            System.getProperty("opennxt.js5.reframeZlb")?.lowercase() != "false"

        fun reframeForClient(data: ByteBuffer): ByteBuffer {
            if (!reframeZlb) return trimToDeclaredLength(data)

            val inflated = try {
                unwrapZlb(data)
            } catch (e: Throwable) {
                null
            } ?: return trimToDeclaredLength(data)

            val body = ByteArray(inflated.remaining())
            inflated.get(body)

            val out = ByteBuffer.allocate(5 + body.size)
            out.put(0)
            out.putInt(body.size)
            out.put(body)
            out.flip()
            return out
        }

        private fun trimToDeclaredLength(data: ByteBuffer): ByteBuffer {
            if (data.remaining() < 5) return data
            val start = data.position()
            val compression = data.get(start).toInt() and 0xff
            if (compression > 3) return data
            val declared =
                ((data.get(start + 1).toInt() and 0xff) shl 24) or
                    ((data.get(start + 2).toInt() and 0xff) shl 16) or
                    ((data.get(start + 3).toInt() and 0xff) shl 8) or
                    (data.get(start + 4).toInt() and 0xff)
            if (declared < 0) return data
            val total = 5 + declared + (if (compression != 0) 4 else 0)
            if (total >= data.remaining()) return data
            val out = data.duplicate()
            out.limit(start + total)
            return out.slice()
        }

        private fun unwrapZlb(data: ByteBuffer): ByteBuffer? {
            if (data.remaining() < 8) return null
            val start = data.position()
            for (i in ZLB_MAGIC.indices) {
                if (data.get(start + i) != ZLB_MAGIC[i]) return null
            }

            val dup = data.duplicate()
            dup.position(start + ZLB_MAGIC.size)
            val inflatedSize = dup.int
            if (inflatedSize < 0 || inflatedSize > MAX_ZLB_INFLATED) {
                throw IllegalArgumentException(
                    "ZLB container declares an implausible inflated size: $inflatedSize"
                )
            }

            val deflated = ByteArray(dup.remaining())
            dup.get(deflated)

            val out = ByteArray(inflatedSize)
            val inflater = Inflater()
            try {
                inflater.setInput(deflated)
                var off = 0
                while (off < inflatedSize && !inflater.finished()) {
                    val n = inflater.inflate(out, off, inflatedSize - off)
                    if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
                    off += n
                }
                if (off != inflatedSize) {
                    throw IllegalArgumentException(
                        "ZLB container declared $inflatedSize inflated bytes but produced $off"
                    )
                }

                val overflow = ByteArray(1)
                if (inflater.inflate(overflow, 0, 1) > 0) {
                    throw IllegalArgumentException(
                        "ZLB container declared $inflatedSize inflated bytes but the stream " +
                            "continues past that - the payload would be silently truncated"
                    )
                }
            } finally {
                inflater.end()
            }

            return ByteBuffer.wrap(out)
        }

        fun decode(rawData: ByteBuffer): Container {
            if (!rawData.hasRemaining()) throw IllegalArgumentException("Provided non-readable (empty?) buffer")

            val unwrapped = unwrapZlb(rawData)
            if (unwrapped != null) {
                val bytes = ByteArray(unwrapped.remaining())
                unwrapped.get(bytes)
                return Container(bytes, ContainerCompression.NONE, -1)
            }

            val data = rawData
            val compression = ContainerCompression.of(data.get().toInt())
            val size = data.int

            val decompressedSize = if (compression == ContainerCompression.NONE) 0 else data.int
            val compressed = ByteArray(size)
            data.get(compressed)
            val version = if (data.remaining() >= 2) data.short.toInt() and 0xffff else -1

            val decompressed = when (compression) {
                ContainerCompression.NONE -> compressed
                ContainerCompression.BZIP2 -> BZIP2Compression.decompress(compressed)
                ContainerCompression.GZIP -> GZIPCompression.decompress(compressed)
                ContainerCompression.LZMA -> LZMACompression.decompress(compressed, decompressedSize)
            }

            return Container(decompressed, compression, version)
        }

        fun wrap(data: ByteBuffer): ByteBuffer {
            val buf = ByteBuffer.allocate(5 + data.remaining())

            buf.put(ContainerCompression.NONE.id.toByte())
            buf.putInt(data.remaining())
            buf.put(data)
            buf.flip()

            return buf
        }

        fun wrap(data: ByteArray): ByteBuffer {
            val buf = ByteBuffer.allocate(5 + data.size)

            buf.put(ContainerCompression.NONE.id.toByte())
            buf.putInt(data.size)
            buf.put(data)
            buf.flip()

            return buf
        }
    }

    fun compress(): ByteBuffer {
        val compressed = when (compression) {
            ContainerCompression.NONE -> data
            ContainerCompression.BZIP2 -> BZIP2Compression.compress(data)
            ContainerCompression.GZIP -> GZIPCompression.compress(data)
            ContainerCompression.LZMA -> LZMACompression.compress(data)
        }

        val buffer =
            ByteBuffer.allocate(compressed.size + 1 + 4 + (if (compression != ContainerCompression.NONE) 4 else 0) + (if (version != -1) 2 else 0))
        buffer.put(compression.id.toByte())
        buffer.putInt(compressed.size)
        if (compression != ContainerCompression.NONE)
            buffer.putInt(data.size)
        buffer.put(compressed)
        if (version != -1)
            buffer.putShort(version.toShort())
        buffer.flip()

        return buffer
    }
}

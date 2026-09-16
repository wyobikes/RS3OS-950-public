package com.opennxt.tools.impl

import com.opennxt.Constants
import com.opennxt.filesystem.Index
import com.opennxt.filesystem.sqlite.SqliteFilesystem
import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.ByteBuffer

object MapTileExtract {
    private const val TILES = 4 * 64 * 64

    private class Reader(val buf: ByteBuffer) {
        fun remaining() = buf.remaining()
        fun skip(n: Int) { buf.position(buf.position() + minOf(n, buf.remaining())) }
        fun u8(): Int = buf.get().toInt() and 0xff
        fun u16(): Int = ((u8() shl 8) or u8())

        fun smart(): Int {
            if (remaining() <= 0) return 0
            val peek = buf.get(buf.position()).toInt() and 0xff
            return if (peek >= 0x80) u16() - 0x8000 else u8()
        }
    }

    private class Underrun : Exception()

    @JvmStatic
    fun main(args: Array<String>) {
        val out = args.firstOrNull { it.startsWith("--out=") }?.substringAfter("=")
            ?: "maptiles.bin"

        val fs = SqliteFilesystem(Constants.CACHE_PATH)
        val table = fs.getReferenceTable(Index.MAPS)
        if (table == null) {
            System.err.println("no reference table for index ${Index.MAPS}")
            return
        }

        var squares = 0
        var missing = 0
        var short = 0
        var trailerBytes = 0L
        var fmt936 = 0

        DataOutputStream(BufferedOutputStream(File(out).outputStream(), 1 shl 20)).use { w ->
            for (sid in 0 until 32768) {
                val arc = try { table.loadArchive(sid) } catch (e: Exception) { null }
                val data = arc?.files?.get(3)?.data
                if (data == null) {
                    missing++
                    continue
                }

                val sq = decodeSquare(data)
                if (sq == null) {
                    short++
                    System.err.println("square $sid ran short mid-walk")
                    continue
                }
                if (sq.wide) fmt936++
                trailerBytes += sq.trailingBytes

                w.writeInt(sid)
                w.write(sq.settings)
                for (v in sq.heights) w.writeShort(v.toInt())
                for (v in sq.underlay) w.writeShort(v.toInt())
                for (v in sq.overlay) w.writeShort(v.toInt())
                w.write(sq.shape)
                squares++
            }
        }

        println("squares decoded : $squares")
        println("format 936      : $fmt936")
        println("no file 3       : $missing")
        println("ran short       : $short")
        println("trailer bytes   : $trailerBytes (env section, not parsed)")
        println("written         : $out")
    }

    class Square(
        val settings: ByteArray, val heights: ShortArray, val underlay: ShortArray,
        val overlay: ShortArray, val shape: ByteArray,
        val wide: Boolean,
        val trailingBytes: Int
    )

    fun decodeSquare(data: ByteArray): Square? {
        val r = Reader(ByteBuffer.wrap(data))
        val wide = data.size >= 5 && data[0] == 'j'.code.toByte() &&
                data[1] == 'a'.code.toByte() && data[2] == 'g'.code.toByte() &&
                data[3] == 'x'.code.toByte() && data[4] == 0x01.toByte()
        if (wide) r.skip(5)

        val settings = ByteArray(TILES)
        val heights = ShortArray(TILES) { Short.MIN_VALUE }
        val underlay = ShortArray(TILES) { -1 }
        val overlay = ShortArray(TILES) { -1 }
        val shape = ByteArray(TILES) { -1 }

        try {
            decode(r, wide, settings, heights, underlay, overlay, shape)
        } catch (e: Underrun) {
            return null
        }
        return Square(settings, heights, underlay, overlay, shape, wide, r.remaining())
    }

    private fun decode(
        r: Reader, wide: Boolean, settings: ByteArray, heights: ShortArray,
        underlay: ShortArray, overlay: ShortArray, shape: ByteArray
    ) {
        for (plane in 0 until 4) {
            for (x in 0 until 64) {
                for (y in 0 until 64) {
                    if (r.remaining() <= 0) throw Underrun()
                    val i = (plane * 64 + x) * 64 + y
                    val flags = r.u8()
                    if ((flags and 0x1) != 0) {
                        shape[i] = r.u8().toByte()
                        overlay[i] = r.smart().toShort()
                    }
                    if ((flags and 0x2) != 0) settings[i] = r.u8().toByte()
                    if ((flags and 0x4) != 0) underlay[i] = r.smart().toShort()
                    if ((flags and 0x8) != 0) {
                        heights[i] = (if (wide) r.u16() else r.u8()).toShort()
                    }
                }
            }
        }
    }
}

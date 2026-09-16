package com.opennxt.tools.impl

import com.opennxt.Constants
import com.opennxt.filesystem.Index
import com.opennxt.filesystem.sqlite.SqliteFilesystem
import java.io.File
import java.nio.ByteBuffer

object MapLocExtract {
    private fun squareId(i: Int, j: Int) = j * 128 + i

    class Reader(val buf: ByteBuffer) {
        fun remaining() = buf.remaining()
        fun u8(): Int = buf.get().toInt() and 0xff
        fun u16(): Int = ((u8() shl 8) or u8())
        fun s16(): Int = u16().let { if (it > 32767) it - 65536 else it }
        fun skip(n: Int) { buf.position(buf.position() + minOf(n, buf.remaining())) }

        fun smart(): Int {
            val peek = buf.get(buf.position()).toInt() and 0xff
            return if (peek < 0x80) u8() else (u16() and 0x7fff)
        }

        fun chainedSmart(): Int {
            var total = 0
            while (true) {
                val v = smart()
                total += v
                if (v != 0x7fff) return total
            }
        }
    }

    @JvmStatic
    fun main(args: Array<String>) {
        val out = args.firstOrNull { it.startsWith("--out=") }?.substringAfter("=")
            ?: "maploc.tsv"
        val onlyKnown = args.any { it == "--only-known" }
        val knownArg = args.firstOrNull { it.startsWith("--known=") }?.substringAfter("=")

        val known: Set<Int> = if (onlyKnown && knownArg != null) {
            File(knownArg).readLines().mapNotNull { it.trim().toIntOrNull() }.toSet()
        } else emptySet()

        val fs = SqliteFilesystem(Constants.CACHE_PATH)
        val table = fs.getReferenceTable(Index.MAPS)
        if (table == null) {
            System.err.println("no reference table for index ${Index.MAPS}")
            return
        }

        var squares = 0
        var rows = 0L
        var missing = 0
        var failed = 0
        var leftover = 0
        var leftoverBytes = 0L

        File(out).bufferedWriter().use { w ->
            w.write("square_id\tplane\tx\ty\tloc_id\ttype\trot\tfile\txform\n")
            for (i in 0 until 128) {
                for (j in 0 until 256) {
                    val sid = squareId(i, j)
                    if (onlyKnown && known.isNotEmpty() && sid !in known) continue

                    val arc = try {
                        table.loadArchive(sid)
                    } catch (e: Exception) {
                        null
                    }
                    if (arc == null) {
                        missing++
                        continue
                    }
                    val parts = listOfNotNull(
                        arc.files[0]?.data?.let { 0 to it },
                        arc.files[1]?.data?.let { 1 to it }
                    )
                    if (parts.isEmpty()) {
                        missing++
                        continue
                    }

                    try {
                        var n = 0L
                        for ((fileId, part) in parts) {
                            val rd = Reader(ByteBuffer.wrap(part))
                            n += decodeInto(rd, sid, fileId) { row ->
                                w.write("${row.squareId}\t${row.plane}\t${row.x}\t${row.y}\t" +
                                        "${row.locId}\t${row.type}\t${row.rot}\t${row.fileId}\t${row.xform}\n")
                            }
                            if (rd.remaining() > 0) {
                                leftover++
                                leftoverBytes += rd.remaining()
                            }
                        }
                        rows += n
                        squares++
                    } catch (e: Exception) {
                        failed++
                        System.err.println("square $sid failed: ${e.message}")
                    }
                }
            }
        }

        println("squares decoded : $squares")
        println("placements      : $rows")
        println("archives absent : $missing")
        println("squares failed  : $failed")
        println("desynced parts  : $leftover ($leftoverBytes bytes unread)")
        println("written         : $out")
    }

    class LocRow(
        val squareId: Int, val plane: Int, val x: Int, val y: Int,
        val locId: Int, val type: Int, val rot: Int,
        val fileId: Int,
        val xform: String
    )

    fun decodeInto(r: Reader, sid: Int, fileId: Int, emit: (LocRow) -> Unit): Long {
        var id = -1
        var count = 0L
        while (r.remaining() > 0) {
            val inc = r.chainedSmart()
            if (inc == 0) break
            id += inc

            var pos = 0
            while (r.remaining() > 0) {
                val pinc = r.smart()
                if (pinc == 0) break
                pos += pinc - 1

                val plane = (pos shr 12) and 0x3
                val x = (pos shr 6) and 0x3f
                val y = pos and 0x3f

                if (r.remaining() < 1) return count
                val data = r.u8()
                val type = (data shr 2) and 0x1f
                val rot = data and 0x3

                var xform = ""
                if (data >= 0x80 && r.remaining() > 0) {
                    val flags = r.u8()
                    val parts = ArrayList<String>(4)
                    if ((flags and 0x01) != 0) {
                        parts.add("\"rot\":[${r.s16()},${r.s16()},${r.s16()},${r.s16()}]")
                    }
                    val move = ArrayList<String>(3)
                    if ((flags and 0x02) != 0) move.add("\"x\":${r.s16()}")
                    if ((flags and 0x04) != 0) move.add("\"y\":${r.s16()}")
                    if ((flags and 0x08) != 0) move.add("\"z\":${r.s16()}")
                    if (move.isNotEmpty()) parts.add("\"move\":{${move.joinToString(",")}}")
                    if ((flags and 0x10) != 0) {
                        parts.add("\"scale\":${r.s16()}")
                    } else if ((flags and 0xe0) != 0) {
                        val sx = if ((flags and 0x20) != 0) r.s16() else 128
                        val sy = if ((flags and 0x40) != 0) r.s16() else 128
                        val sz = if ((flags and 0x80) != 0) r.s16() else 128
                        parts.add("\"scale3\":{\"x\":$sx,\"y\":$sy,\"z\":$sz}")
                    }
                    if (parts.isNotEmpty()) xform = "{${parts.joinToString(",")}}"
                }

                emit(LocRow(sid, plane, x, y, id, type, rot, fileId, xform))
                count++
            }
        }
        return count
    }
}

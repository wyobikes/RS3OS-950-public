package com.opennxt.net.game.clientprot

object UndecodedStructure {
    fun describe(packet: UndecodedClientPacket): String? = try {
        val p = packet.payload
        when (packet.opcode) {
            4 -> cursorBatch(p, tailBytes = 0)
            16 -> pointerReport(p)
            67 -> if (p.isEmpty()) "no payload"
                  else "ANOMALY: size-0 opcode arrived with ${p.size} byte(s)"
            89 -> if (p.size == 4) "int=${be32(p, 0)}"
                  else "ANOMALY: expected 4 bytes, got ${p.size}"
            98 -> cursorBatch(p, tailBytes = 1)
            105 -> bulkUpload(p)
            113 -> if (p.size == 4)
                       "id=${be16(p, 0)} value=${(u8(p, 2) - 128) and 0xff} trailing=${u8(p, 3)}"
                   else "ANOMALY: expected 4 bytes, got ${p.size}"
            114 -> if (p.size == 3)
                       "cs2 args=[${u8(p, 0)}, ${u8(p, 1)}, ${u8(p, 2)}]"
                   else "ANOMALY: expected 3 bytes, got ${p.size}"
            127 -> if (p.size == 6)
                       "interface=${be16(p, 0)} component=${be16(p, 2)} sub=${subSlot(p)} " +
                           "(RESUME_PAUSEBUTTON)"
                   else "ANOMALY: expected 6 bytes, got ${p.size}"
            133 -> if (p.size == 4) "elapsedMs=${be32(p, 0)}"
                   else "ANOMALY: expected 4 bytes, got ${p.size}"
            146 -> configBlob(p)
            else -> null
        }
    } catch (t: Throwable) {
        "ANOMALY: ${t.javaClass.simpleName} while decoding"
    }

    private fun pointerReport(p: ByteArray): String {
        if (p.size != 7) return "ANOMALY: expected 7 bytes, got ${p.size}"
        val tag = (u8(p, 0) - 128) and 0xff
        val dt = be16(p, 1)
        return "rawInputMask=${tag shr 1} flag=${tag and 1} dt=${dt}ms${if (dt == 0xffff) " (saturated)" else ""} " +
            "x=${be16(p, 3)} y=${be16(p, 5)}"
    }

    private fun cursorBatch(p: ByteArray, tailBytes: Int): String {
        if (p.size < 2) return "ANOMALY: ${p.size} byte(s), too short for the 2-byte header"
        var i = 2
        var records = 0
        var shortDeltas = 0
        var byteDeltas = 0
        var absolutes = 0
        var sentinels = 0
        var dtTotal = 0
        var firstAbs: Pair<Int, Int>? = null
        var lastAbs: Pair<Int, Int>? = null
        var anomaly: String? = null

        fun readAbsolute(at: Int): Boolean {
            if (at + 4 > p.size) { anomaly = "absolute record runs past the end at offset $at"; return false }
            val raw = be32(p, at)
            if (raw == 0x80000000.toInt()) { sentinels++ } else {
                val y = be16(p, at); val x = be16(p, at + 2)
                if (firstAbs == null) firstAbs = x to y
                lastAbs = x to y
                absolutes++
            }
            return true
        }

        while (i < p.size && anomaly == null) {
            val b = u8(p, i)
            when {
                b <= 0x7f -> {
                    if (i + 2 > p.size) { anomaly = "2-byte delta runs past the end at offset $i"; break }
                    val w = be16(p, i)
                    dtTotal += (w shr 12) * 20
                    shortDeltas++
                    i += 2
                }
                b <= 0x9f -> {
                    if (i + 3 > p.size) { anomaly = "3-byte delta runs past the end at offset $i"; break }
                    dtTotal += (b - 0x80) * 20
                    byteDeltas++
                    i += 3
                }
                b <= 0xbf -> { anomaly = "unexpected lead byte 0x%02x at offset %d".format(b, i); break }
                b <= 0xdf -> {
                    dtTotal += ((b + 0x40) and 0xff) * 20
                    if (!readAbsolute(i + 1)) break
                    i += 5
                }
                else -> {
                    if (i + 2 > p.size) { anomaly = "6-byte record runs past the end at offset $i"; break }
                    dtTotal += (((b and 0x1f) shl 8) or u8(p, i + 1)) * 20
                    if (!readAbsolute(i + 2)) break
                    i += 6
                }
            }
            if (anomaly != null) break
            records++
            i += tailBytes
            if (i > p.size) { anomaly = "record tail runs past the end at offset $i"; break }
        }

        if (anomaly != null)
            return "ANOMALY after $records record(s): $anomaly (${p.size} byte(s))"

        return "cursor history: header mean=${u8(p, 0)} check=${u8(p, 1)}, $records record(s) " +
            "($shortDeltas short delta, $byteDeltas byte delta, $absolutes absolute, $sentinels sentinel), " +
            "~${dtTotal}ms spanned" +
            (firstAbs?.let { ", first abs=(${it.first},${it.second})" } ?: "") +
            (lastAbs?.let { ", last abs=(${it.first},${it.second})" } ?: "")
    }

    private fun bulkUpload(p: ByteArray): String {
        if (p.isEmpty()) return "ANOMALY: empty payload"
        val body = p.size - 1
        if (body % 6 != 0)
            return "ANOMALY: ${p.size} byte(s); body $body does not divide by the 6-byte record"
        val n = body / 6
        var first = -1
        var last = -1
        var consecutive = true
        var ffSlots = 0
        for (r in 0 until n) {
            val at = 1 + r * 6
            val id = be16(p, at)
            if (r == 0) first = id else if (id != last + 1) consecutive = false
            last = id
            for (k in 2 until 6) if (u8(p, at + k) == 0xff) ffSlots++
        }
        return "bulk upload: allRecordsSent=${u8(p, 0)}, $n record(s) of 6 bytes, " +
            "ids $first..$last${if (consecutive) " (consecutive)" else " (not consecutive)"}, " +
            "$ffSlots of ${n * 4} value slots are 0xff"
    }

    private fun configBlob(p: ByteArray): String {
        if (p.size != 58)
            return "ANOMALY: expected 58 bytes, got ${p.size}"
        val diff = ArrayList<String>()
        for (i in REFERENCE_146.indices) {
            if (i >= p.size) break
            val v = u8(p, i)
            if (v != (REFERENCE_146[i].toInt() and 0xff)) diff.add("[$i] %02x->%02x".format(REFERENCE_146[i], v))
        }
        return "client config blob (58 bytes) vs reference: " +
            (if (diff.isEmpty()) "identical over the first ${REFERENCE_146.size} bytes"
             else "${diff.size} byte(s) differ ${diff.take(8).joinToString(" ")}")
    }

    private val REFERENCE_146: ByteArray =
        byteArrayOf(
            0x26, 0x01, 0x00, 0x02, 0x03, 0x03, 0x05, 0x03,
            0x01, 0x01, 0x01, 0x01, 0x02, 0x01, 0x02, 0x04,
            0x03, 0x00, 0x02, 0x01, 0x01, 0x01, 0x00, 0x02,
            0x01, 0x00, 0x01, 0x02, 0x01, 0x03, 0x03, 0x03
        )

    private fun u8(p: ByteArray, i: Int): Int = p[i].toInt() and 0xff
    private fun be16(p: ByteArray, i: Int): Int = (u8(p, i) shl 8) or u8(p, i + 1)
    private fun be32(p: ByteArray, i: Int): Int =
        (u8(p, i) shl 24) or (u8(p, i + 1) shl 16) or (u8(p, i + 2) shl 8) or u8(p, i + 3)

    private fun subSlot(p: ByteArray): Int {
        val v = (u8(p, 4) shl 8) or ((u8(p, 5) - 128) and 0xff)
        return if (v == 0xffff) -1 else v
    }
}

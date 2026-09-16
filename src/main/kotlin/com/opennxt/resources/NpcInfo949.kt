package com.opennxt.resources

object NpcInfo949 {
    const val MOVE_MASK_ONLY = 0
    const val MOVE_ONE_DIR = 1
    const val MOVE_SELECTED = 2
    const val MOVE_REMOVE = 3

    const val ADD_SENTINEL = 0xFFFF

    const val COORD_BITS = 7

    val MASK_CONTINUATIONS = intArrayOf(5, 14, 18, 26)

    fun readUpdateMask(next: () -> Int): Pair<Long, Int> {
        var used = 1
        var mask = (next() and 0xFF).toLong()
        if (mask and 0x20L != 0L) {
            mask = mask or ((next() and 0xFF).toLong() shl 8); used++
        }
        if ((mask shr 14) and 1L != 0L) {
            mask += (next() and 0xFF).toLong() shl 16; used++
        }
        if ((mask shr 18) and 1L != 0L) {
            mask += (next() and 0xFF).toLong() shl 24; used++
        }
        if ((mask shr 26) and 1L != 0L) {
            mask += (next() and 0xFF).toLong() shl 32; used++
        }
        return mask to used
    }

    fun maskWidth(mask: Long): Int {
        var n = 1
        if (mask and 0x20L != 0L) n++
        if ((mask shr 14) and 1L != 0L) n++
        if ((mask shr 18) and 1L != 0L) n++
        if ((mask shr 26) and 1L != 0L) n++
        return n
    }

    class BitReader(private val d: ByteArray) {
        var pos: Int = 0
            private set

        fun bits(n: Int): Int {
            var v = 0
            repeat(n) {
                if (pos >= d.size * 8) throw IndexOutOfBoundsException("out of bits")
                v = (v shl 1) or ((d[pos ushr 3].toInt() shr (7 - (pos and 7))) and 1)
                pos++
            }
            return v
        }
    }

    data class Add(val index: Int, val npcId: Int, val dx: Int, val dy: Int)

    fun highRes(payload: ByteArray): Pair<Int, IntArray> {
        val r = BitReader(payload)
        val count = r.bits(8)
        val tally = IntArray(4)
        repeat(count) {
            if (r.bits(1) == 0) return@repeat
            val mv = r.bits(2)
            tally[mv]++
            when (mv) {
                MOVE_MASK_ONLY -> {}
                MOVE_ONE_DIR -> { r.bits(3); r.bits(1) }
                MOVE_SELECTED -> {
                    if (r.bits(1) == 1) { r.bits(3); r.bits(3) } else r.bits(3)
                    r.bits(1)
                }
            }
        }
        return r.pos to tally
    }

    fun adds(payload: ByteArray, w: Int = COORD_BITS): List<Add> {
        val r = BitReader(payload)
        val (start, _) = highRes(payload)
        repeat(start) { r.bits(1) }
        val out = ArrayList<Add>()
        val half = (1 shl (w - 1)) - 1
        val full = 1 shl w
        val recordBits = 39 + 2 * w
        while (payload.size * 8 - r.pos >= recordBits) {
            val index = r.bits(16)
            if (index == ADD_SENTINEL) break
            var dx = r.bits(w)
            val npcId = r.bits(16)
            r.bits(1); r.bits(2); r.bits(3)
            var dy = r.bits(w)
            r.bits(1)
            if (dx > half) dx -= full
            if (dy > half) dy -= full
            out.add(Add(index, npcId, dx, dy))
        }
        return out
    }
}

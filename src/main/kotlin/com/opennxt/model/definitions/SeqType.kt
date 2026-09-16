package com.opennxt.model.definitions

import com.google.gson.JsonArray
import com.google.gson.JsonNull
import com.google.gson.JsonObject

class SeqType(
    val id: Int,
    val frames: List<SeqFrame>? = null,
    val unknown02: Int? = null,
    val unknown03: IntArray? = null,
    val unknown05: Int? = null,
    val unknown06: Int? = null,
    val unknown07: Int? = null,
    val unknown08: Int? = null,
    val unknown09: Int? = null,
    val unknown0A: Int? = null,
    val unknown0B: Int? = null,
    val unknown0C: List<SeqIntPair>? = null,
    val unknown0D: List<SeqSound>? = null,
    val unknown0E: Boolean = false,
    val unknown0F: Boolean = false,
    val unknown10: Boolean = false,
    val unknown11: Boolean = false,
    val unknown12: Boolean = false,
    val unknown13: IntArray? = null,
    val unknown14: IntArray? = null,
    val unknown16: Int? = null,
    val unknown17: IntArray? = null,
    val unknown18: Int? = null,
    val skeletalAnimation: Int? = null,
    val skeletalRange: IntArray? = null,
    val unknown1B: Int? = null,
    val unknown70: List<SeqIntPair>? = null,
    val unknown77: IntArray? = null,
    val unknown78: IntArray? = null,
    val extra: List<SeqParam>? = null,
    val frameFlags: Map<Int, Int> = emptyMap(),
    val framePairs: Map<Int, IntArray> = emptyMap()
) {
    val delays: IntArray? get() = frames?.map { it.framelength }?.toIntArray()

    val durationInTicks: Int
        get() {
            val d = delays ?: return 0
            val totalClientTicks = d.sum()
            return (totalClientTicks + 15) / 30
        }

    override fun equals(other: Any?) = other is SeqType && other.id == id
    override fun hashCode() = id

    fun toJson(includeId: Boolean = true): JsonObject {
        val o = JsonObject()
        frames?.let { fs ->
            val a = JsonArray()
            for (f in fs) {
                val e = JsonObject()
                e.addProperty("framelength", f.framelength)
                e.addProperty("frameindex", f.frameindex)
                e.addProperty("framefile", f.framefile)
                a.add(e)
            }
            o.add("frames", a)
        }
        unknown02?.let { o.addProperty("unknown_02", it) }
        unknown03?.let { o.add("unknown_03", intArr(it)) }
        unknown05?.let { o.addProperty("unknown_05", it) }
        unknown06?.let { o.addProperty("unknown_06", it) }
        unknown07?.let { o.addProperty("unknown_07", it) }
        unknown08?.let { o.addProperty("unknown_08", it) }
        unknown09?.let { o.addProperty("unknown_09", it) }
        unknown0A?.let { o.addProperty("unknown_0A", it) }
        unknown0B?.let { o.addProperty("unknown_0B", it) }
        unknown0C?.let { o.add("unknown_0C", pairArr(it)) }
        unknown0D?.let { ss ->
            val a = JsonArray()
            for (s in ss) {
                val e = JsonObject()
                if (s.value0 == null) {
                    e.add("value0", JsonNull.INSTANCE)
                    e.add("extras", JsonNull.INSTANCE)
                } else {
                    e.addProperty("value0", s.value0)
                    e.add("extras", intArr(s.extras ?: IntArray(0)))
                }
                a.add(e)
            }
            o.add("unknown_0D", a)
        }
        if (unknown0E) o.addProperty("unknown_0E", true)
        if (unknown0F) o.addProperty("unknown_0F", true)
        if (unknown10) o.addProperty("unknown_10", true)
        if (unknown11) o.addProperty("unknown_11", true)
        if (unknown12) o.addProperty("unknown_12", true)
        unknown13?.let { o.add("unknown_13", intArr(it)) }
        unknown14?.let { o.add("unknown_14", intArr(it)) }
        unknown16?.let { o.addProperty("unknown_16", it) }
        unknown17?.let { o.add("unknown_17", intArr(it)) }
        unknown18?.let { o.addProperty("unknown_18", it) }
        skeletalAnimation?.let { o.addProperty("skeletal_animation", it) }
        skeletalRange?.let { o.add("skeletal_range", intArr(it)) }
        unknown1B?.let { o.addProperty("unknown_1B", it) }
        unknown70?.let { o.add("unknown_70", pairArr(it)) }
        unknown77?.let { o.add("unknown_77", intArr(it)) }
        unknown78?.let { o.add("unknown_78", intArr(it)) }
        extra?.let { ps ->
            val a = JsonArray()
            for (p in ps) {
                val e = JsonObject()
                e.addProperty("prop", p.prop)
                if (p.intvalue == null) e.add("intvalue", JsonNull.INSTANCE)
                else e.addProperty("intvalue", p.intvalue)
                if (p.stringvalue == null) e.add("stringvalue", JsonNull.INSTANCE)
                else e.addProperty("stringvalue", p.stringvalue)
                a.add(e)
            }
            o.add("extra", a)
        }
        if (includeId) o.addProperty("id", id)
        return o
    }

    private fun intArr(v: IntArray): JsonArray {
        val a = JsonArray()
        for (x in v) a.add(x)
        return a
    }

    private fun pairArr(v: List<SeqIntPair>): JsonArray {
        val a = JsonArray()
        for (p in v) {
            val e = JsonObject()
            e.addProperty("intlow", p.intlow)
            e.addProperty("maybe_file", p.maybeFile)
            a.add(e)
        }
        return a
    }
}

data class SeqFrame(val framelength: Int, val frameindex: Int, val framefile: Int)

data class SeqIntPair(val intlow: Int, val maybeFile: Int)

class SeqSound(val value0: Int?, val extras: IntArray?)

class SeqParam(val prop: Int, val intvalue: Int?, val stringvalue: String?)

object SeqCodec {
    val KNOWN_OPCODES = intArrayOf(
        1, 2, 3, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18,
        19, 20, 22, 23, 24, 25, 26, 27, 112, 119, 120, 249
    )

    val UNEXERCISED_OPCODES = intArrayOf(3, 12, 17, 19, 20, 23)

    enum class Defect {
        NONE,

        OP1_INTERLEAVED_TUPLES,

        OP249_VALUE_BEFORE_KEY,

        OP13_NO_PAYLOAD
    }

    class Underflow(message: String) : Exception(message)

    class Reader(val d: ByteArray) {
        var p = 0

        fun need(n: Int) {
            if (p + n > d.size) throw Underflow("want $n at $p of ${d.size}")
        }

        fun u8(): Int {
            need(1); return d[p++].toInt() and 0xff
        }

        fun u16(): Int {
            need(2)
            val v = ((d[p].toInt() and 0xff) shl 8) or (d[p + 1].toInt() and 0xff)
            p += 2
            return v
        }

        fun u24(): Int {
            need(3)
            val v = ((d[p].toInt() and 0xff) shl 16) or
                    ((d[p + 1].toInt() and 0xff) shl 8) or (d[p + 2].toInt() and 0xff)
            p += 3
            return v
        }

        fun i32(): Int {
            need(4)
            val v = ((d[p].toInt() and 0xff) shl 24) or ((d[p + 1].toInt() and 0xff) shl 16) or
                    ((d[p + 2].toInt() and 0xff) shl 8) or (d[p + 3].toInt() and 0xff)
            p += 4
            return v
        }

        fun smart(): Int {
            need(1)
            if ((d[p].toInt() and 0xff) < 0x80) return u8()
            return (u16() + 0x8000) and 0xffff
        }

        fun cstr(): String {
            var i = p
            while (i < d.size && d[i].toInt() != 0) i++
            if (i >= d.size) throw Underflow("unterminated string at $p")
            val s = String(d, p, i - p, Charsets.ISO_8859_1)
            p = i + 1
            return s
        }

        fun remaining() = d.size - p
    }

    class Result(val type: SeqType, val error: String?)

    fun decode(
        id: Int,
        data: ByteArray,
        census: MutableList<Int>? = null,
        defect: Defect = Defect.NONE,
        offsets: MutableList<Int>? = null
    ): Result {
        val r = Reader(data)

        var frames: List<SeqFrame>? = null
        var u02: Int? = null; var u03: IntArray? = null; var u05: Int? = null
        var u06: Int? = null; var u07: Int? = null; var u08: Int? = null
        var u09: Int? = null; var u0A: Int? = null; var u0B: Int? = null
        var u0C: List<SeqIntPair>? = null; var u0D: List<SeqSound>? = null
        var u0E = false; var u0F = false; var u10 = false; var u11 = false; var u12 = false
        var u13: IntArray? = null; var u14: IntArray? = null; var u16v: Int? = null
        var u17: IntArray? = null; var u18: Int? = null; var skelAnim: Int? = null
        var skelRange: IntArray? = null; var u1B: Int? = null
        var u70: List<SeqIntPair>? = null; var u77: IntArray? = null; var u78: IntArray? = null
        var extra: List<SeqParam>? = null
        val frameFlags = LinkedHashMap<Int, Int>()
        val framePairs = LinkedHashMap<Int, IntArray>()

        fun build(err: String?) = Result(
            SeqType(
                id, frames, u02, u03, u05, u06, u07, u08, u09, u0A, u0B, u0C, u0D,
                u0E, u0F, u10, u11, u12, u13, u14, u16v, u17, u18, skelAnim,
                skelRange, u1B, u70, u77, u78, extra, frameFlags, framePairs
            ), err
        )

        try {
            while (true) {
                if (r.remaining() == 0) return build("no terminator")
                val opAt = r.p
                val op = r.u8()
                if (op == 0) {
                    if (r.remaining() > 0) return build("trailing ${r.remaining()} byte(s)")
                    return build(null)
                }
                census?.add(op)
                offsets?.add(opAt)
                when (op) {
                    1 -> {
                        val n = r.u16()
                        frames = if (defect == Defect.OP1_INTERLEAVED_TUPLES) {
                            (0 until n).map { SeqFrame(r.u16(), r.u16(), r.u16()) }
                        } else {
                            val lens = IntArray(n) { r.u16() }
                            val idxs = IntArray(n) { r.u16() }
                            val files = IntArray(n) { r.u16() }
                            (0 until n).map { SeqFrame(lens[it], idxs[it], files[it]) }
                        }
                    }
                    2 -> u02 = r.u16()
                    3 -> {
                        val n = r.smart()
                        u03 = IntArray(n) { r.smart() }
                    }
                    5 -> u05 = r.u8()
                    6 -> u06 = r.u16()
                    7 -> u07 = r.u16()
                    8 -> u08 = r.u8()
                    9 -> u09 = r.u8()
                    10 -> u0A = r.u8()
                    11 -> u0B = r.u8()
                    12 -> u0C = twoArrays(r, r.u8())
                    13 -> if (defect != Defect.OP13_NO_PAYLOAD) {
                        val n = r.u16()
                        val items = ArrayList<SeqSound>(n)
                        for (i in 0 until n) {
                            val k = r.u8()
                            if (k == 0) items.add(SeqSound(null, null))
                            else {
                                val v = r.u24()
                                items.add(SeqSound(v, IntArray(k - 1) { r.u16() }))
                            }
                        }
                        u0D = items
                    }
                    14 -> u0E = true
                    15 -> u0F = true
                    16 -> u10 = true
                    17 -> u11 = true
                    18 -> u12 = true
                    19 -> {
                        val f = r.u8(); val v = r.u8()
                        u13 = intArrayOf(f, v); frameFlags[f] = v
                    }
                    20 -> {
                        val f = r.u8(); val a = r.u16(); val b = r.u16()
                        u14 = intArrayOf(f, a, b); framePairs[f] = intArrayOf(a, b)
                    }
                    22 -> u16v = r.u8()
                    23 -> u17 = intArrayOf(r.u8(), r.u8())
                    24 -> u18 = r.u16()
                    25 -> skelAnim = r.u16()
                    26 -> skelRange = intArrayOf(r.u16(), r.u16())
                    27 -> u1B = r.u8()
                    112 -> u70 = twoArrays(r, r.u16())
                    119 -> {
                        val f = r.u16(); val v = r.u8()
                        u77 = intArrayOf(f, v); frameFlags[f] = v
                    }
                    120 -> {
                        val f = r.u16(); val a = r.u16(); val b = r.u16()
                        u78 = intArrayOf(f, a, b); framePairs[f] = intArrayOf(a, b)
                    }
                    249 -> {
                        val n = r.u8()
                        val ps = ArrayList<SeqParam>(n)
                        for (i in 0 until n) {
                            val isString = r.u8()
                            if (defect == Defect.OP249_VALUE_BEFORE_KEY) {
                                if (isString != 0) {
                                    val s = r.cstr(); ps.add(SeqParam(r.u24(), null, s))
                                } else {
                                    val v = r.i32(); ps.add(SeqParam(r.u24(), v, null))
                                }
                            } else {
                                val key = r.u24()
                                if (isString != 0) ps.add(SeqParam(key, null, r.cstr()))
                                else ps.add(SeqParam(key, r.i32(), null))
                            }
                        }
                        extra = ps
                    }
                    else -> return build("unknown opcode $op at ${r.p - 1}")
                }
            }
        } catch (e: Underflow) {
            return build("underflow: ${e.message}")
        }
    }

    private fun twoArrays(r: Reader, n: Int): List<SeqIntPair> {
        val lo = IntArray(n) { r.u16() }
        val hi = IntArray(n) { r.u16() }
        return (0 until n).map { SeqIntPair(lo[it], hi[it]) }
    }
}

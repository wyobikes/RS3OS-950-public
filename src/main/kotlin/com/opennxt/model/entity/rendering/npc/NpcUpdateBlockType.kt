package com.opennxt.model.entity.rendering.npc

import com.opennxt.net.buf.GamePacketBuilder

enum class NpcUpdateBlockType(val bit: Int, val order: Int) {
    ANIMATION(3, 4),

    HITS(0, 6),

    FACE_COORDINATE(6, 18),

    SAY(2, 19),

    ANIMATION_GROUP(10, 29),

    FACE_ENTITY(7, 7),

    FORCE_MOVEMENT(11, 12),

    STRING_17(17, 26),

    SHORT_22(22, 1),

    BYTE_25(25, 8),

    FLAG_28(28, 3),

    BYTE_34(34, 21),

    LIFEPOINTS(16, 13),

    DISCARDED_4(4, 2),

    DISCARDED_8(8, 24),

    DISCARDED_24(24, 27),

    DISCARDED_27(27, 14),

    DISCARDED_29(29, 22),

    DISCARDED_12(12, 28),

    DISCARDED_13(13, 20);

    val maskBit: Long get() = 1L shl bit

    companion object {
        private val CONTINUATIONS = listOf(32 to 26, 24 to 18, 16 to 14, 8 to 5)

        val NEVER_TESTED = setOf(15)

        val DISPATCH_ORDER = listOf(
            20, 22, 4, 28, 3, 1, 0, 7, 25, 9, 21, 31, 11, 16, 27, 33, 30, 19, 6,
            2, 13, 34, 29, 32, 8, 23, 17, 24, 12, 10
        )

        val UNDECODED: Map<Int, String> = mapOf(
            1 to "one smart32 value",
            9 to "large: u8, arrays, smart32, s16 x4, u16 x2 and more; layout not settled",
            19 to "skip 2, u8 count, count x (u8 + u16 + u32) = 3 + 7n bytes",
            20 to "u8 flags then up to four sub-blocks (flag bits 0..3), arrays of u16",
            21 to "two nested containers; no scalar fields",
            23 to "same shape as 19: skip 2, u8 count, count x 7 bytes = 3 + 7n",
            30 to "u16 + u32 plus nested containers; not settled",
            31 to "u8 x4 then u16 x2 = 8 bytes fixed",
            32 to "u32 x3 in MIDDLE-endian form, then hit/health data; not settled",
            33 to "s16 x2 then u32 x11; not settled"
        )

        val DECODED_THIS_PASS: Map<Int, String> = mapOf(
            4 to "7-byte discarded u16+u32+u8",
            7 to "face entity, u24 BIG, tag first",
            8 to "7-byte discarded u16+u32+u8",
            11 to "force movement, 12 bytes",
            12 to "7-byte discarded skip1+u16x3",
            13 to "4-byte discarded skip2+u16",
            17 to "one NUL-terminated string, empty restores the definition value",
            22 to "u16 BE, 0xffff restores the definition value",
            24 to "7-byte discarded u16+u32+u8",
            25 to "u8 SUBTRACT",
            27 to "7-byte discarded u16+u32+u8",
            28 to "u8 ADD, read as a boolean",
            29 to "7-byte discarded u16+u32+u8",
            34 to "u8 SUBTRACT, 0 clears the value",
            0 to "eight-field health-bar record",
            16 to "lifepoints: u8 count, {u8 kind, u32 current INVERSED_MIDDLE, " +
                "u24 maximum [v>>8,v>>16,v]}"
        )

        fun writeMask(buffer: GamePacketBuilder, dataMask: Long) {
            require(dataMask != 0L) { "an extended-info block with an empty mask is not encodable" }
            for (bit in NEVER_TESTED) {
                require((dataMask shr bit) and 1L == 0L) {
                    "mask bit $bit is never read by the client; it cannot carry a block"
                }
            }

            var mask = dataMask
            for ((threshold, bit) in CONTINUATIONS) {
                if ((mask ushr threshold) != 0L) mask = mask or (1L shl bit)
            }

            buffer.putRawByte((mask and 0xff).toInt())
            if ((mask shr 5) and 1L == 1L) buffer.putRawByte(((mask shr 8) and 0xff).toInt())
            if ((mask shr 14) and 1L == 1L) buffer.putRawByte(((mask shr 16) and 0xff).toInt())
            if ((mask shr 18) and 1L == 1L) buffer.putRawByte(((mask shr 24) and 0xff).toInt())
            if ((mask shr 26) and 1L == 1L) buffer.putRawByte(((mask shr 32) and 0xff).toInt())
        }

        fun maskLength(dataMask: Long): Int {
            var mask = dataMask
            for ((threshold, bit) in CONTINUATIONS) {
                if ((mask ushr threshold) != 0L) mask = mask or (1L shl bit)
            }
            var n = 1
            if ((mask shr 5) and 1L == 1L) n++
            if ((mask shr 14) and 1L == 1L) n++
            if ((mask shr 18) and 1L == 1L) n++
            if ((mask shr 26) and 1L == 1L) n++
            return n
        }

        val NPC_MASK_950: Map<NpcUpdateBlockType, Int> = mapOf(
            ANIMATION to 3,
            SAY to 6,
            FACE_COORDINATE to 7,
            ANIMATION_GROUP to 12,
            FACE_ENTITY to 1,
            HITS to 33,
            LIFEPOINTS to 19,
            FORCE_MOVEMENT to 14,
            STRING_17 to 18
        )

        val ORDER_950: Map<NpcUpdateBlockType, Int> = mapOf(
            SAY to 1,
            ANIMATION_GROUP to 4,
            FACE_ENTITY to 17,
            FORCE_MOVEMENT to 10,
            LIFEPOINTS to 14,
            STRING_17 to 15,
            FACE_COORDINATE to 18,
            HITS to 23,
            ANIMATION to 25
        )

        val CONTINUATIONS_950 = listOf(32 to 27, 24 to 23, 16 to 13, 8 to 4)

        fun maskBit950(type: NpcUpdateBlockType): Long? = NPC_MASK_950[type]?.let { 1L shl it }

        fun writeMask950(buffer: GamePacketBuilder, dataMask: Long) {
            require(dataMask != 0L) { "an extended-info block with an empty mask is not encodable" }
            for ((_, bit) in CONTINUATIONS_950) {
                require((dataMask shr bit) and 1L == 0L) { "bit $bit is a 950 continuation, not a data bit" }
            }
            var mask = dataMask
            for ((threshold, bit) in CONTINUATIONS_950) {
                if ((mask ushr threshold) != 0L) mask = mask or (1L shl bit)
            }
            buffer.putRawByte((mask and 0xff).toInt())
            if ((mask shr 4) and 1L == 1L) buffer.putRawByte(((mask shr 8) and 0xff).toInt())
            if ((mask shr 13) and 1L == 1L) buffer.putRawByte(((mask shr 16) and 0xff).toInt())
            if ((mask ushr 23) != 0L) buffer.putRawByte(((mask shr 24) and 0xff).toInt())
            if ((mask shr 27) and 1L == 1L) buffer.putRawByte(((mask shr 32) and 0xff).toInt())
        }
    }
}

private fun GamePacketBuilder.putRawByte(value: Int) {
    put(com.opennxt.net.buf.DataType.BYTE, value)
}

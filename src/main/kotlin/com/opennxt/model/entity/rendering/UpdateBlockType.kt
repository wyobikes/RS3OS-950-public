package com.opennxt.model.entity.rendering

import com.opennxt.OpenNXT
import com.opennxt.config.ServerConfig
import com.opennxt.net.buf.DataType
import com.opennxt.net.buf.GamePacketBuilder

enum class UpdateBlockType(
    val playerMask: Int,
    val playerPos: Int,
    val npcMask: Int = 0,
    val npcPos: Int = -1
) {
    FACE_ENTITY(0x1, 10),

    ANIMATION(0x2, 11),

    APPEARANCE(0x4, 3),

    HITS(0x10, 12),

    FACE_DIRECTION(0x80, 13),

    SAY(0x400, 14),

    FORCE_MOVEMENT(0x40, 15),

    STRING_18(1 shl 18, 16),

    BYTE_11(1 shl 11, 17),

    FLAG_21(1 shl 21, 18),

    DISCARDED_13(1 shl 13, 19),

    DISCARDED_9(1 shl 9, 20),

    DISCARDED_3(1 shl 3, 21),

    DISCARDED_27(1 shl 27, 22),

    DISCARDED_16(1 shl 16, 23),

    DISCARDED_23(1 shl 23, 24),

    DISCARDED_8(1 shl 8, 25);

    val playerBit: Int get() = Integer.numberOfTrailingZeros(playerMask)

    val wireOrder: Int get() = DISPATCH_ORDER.indexOf(playerBit)

    companion object {
        val CONTINUATIONS = listOf(24 to 17, 16 to 14, 8 to 5)

        val DISPATCH_ORDER = listOf(
            13, 10, 12, 7, 26, 4, 9, 11, 2, 3, 1, 23,
            24, 27, 18, 19, 6, 20, 8, 22, 16, 0, 25, 21
        )

        val NEVER_TESTED = setOf(15, 28, 29, 30, 31)

        val UNDECODED: Map<Any, String> = mapOf(
            12 to "a u8 length followed by that many bytes (a second byte-array block)",
            19 to "no scalar fields of its own; shares its reader with another block",
            20 to "u8 SUBTRACT, u8 SUBTRACT, u8 &0x7f, u8 ADD, u16, u16 -> a table index and a float",
            22 to "two skipped bytes, then the same shape as bit 19",
            24 to "npc configuration data, then u16 + u32",
            25 to "u32 x3 (middle-endian), then the same hit-splat fields as HITS; layout not settled",
            26 to "u8 count then a per-element record of s16 x2 + u32 x10; layout not settled",
            "HITS bar escape" to
                "f2 == 0x7fff selects an eight-field bar update record; not decoded",
            "HITS splat escapes" to
                "type 0x7fff (five-field splat) and 0x7ffe (descriptor u8 amount); not decoded"
        )

        val DECODED_THIS_PASS: Map<Int, String> = mapOf(
            3 to "7-byte discarded u16+u32+u8",
            6 to "force movement, 12 bytes",
            8 to "4-byte discarded skip2+u16",
            9 to "7-byte discarded u16+u32+u8",
            11 to "one PLAIN byte",
            13 to "7-byte discarded u16+u32+u8",
            16 to "7-byte discarded u16+u32+u8",
            18 to "[string NUL][u8 PLAIN flag]",
            21 to "one ADD byte (boolean)",
            23 to "7-byte discarded skip1+u16x3",
            27 to "7-byte discarded u16+u32+u8"
        )

        fun writeMask(buffer: GamePacketBuilder, dataMask: Int) {
            require(dataMask != 0) { "an extended-info record with an empty mask is not encodable" }
            if (experimentalBuild()) return writeMask950(buffer, dataMask)
            for (bit in NEVER_TESTED) {
                require((dataMask ushr bit) and 1 == 0) {
                    "player mask bit $bit is never read by the client; it cannot carry a block"
                }
            }
            for ((_, bit) in CONTINUATIONS) {
                require((dataMask ushr bit) and 1 == 0) {
                    "player mask bit $bit is a continuation flag, not a block bit; " +
                        "writeMask sets it itself"
                }
            }

            val mask = withContinuations(dataMask)
            buffer.put(DataType.BYTE, mask and 0xff)
            if ((mask ushr 5) and 1 == 1) buffer.put(DataType.BYTE, (mask ushr 8) and 0xff)
            if ((mask ushr 14) and 1 == 1) buffer.put(DataType.BYTE, (mask ushr 16) and 0xff)
            if ((mask ushr 17) and 1 == 1) buffer.put(DataType.BYTE, (mask ushr 24) and 0xff)
        }

        val CONTINUATIONS_950 = listOf(24 to 18, 16 to 15, 8 to 4)

        val PLAYER_MASK_950: Map<UpdateBlockType, Int> = mapOf(
            APPEARANCE to 0x20,
            ANIMATION to (1 shl 3),
            FACE_ENTITY to (1 shl 7),
            SAY to (1 shl 10),
            FACE_DIRECTION to (1 shl 1),
            HITS to (1 shl 6),
            FORCE_MOVEMENT to (1 shl 0),
            STRING_18 to (1 shl 22)
        )

        val DISPATCH_ORDER_950 = listOf(26, 8, 20, 3, 23, 7, 12, 24, 19, 6, 11, 22, 5, 0, 17, 21, 16, 25, 27, 9, 10, 1, 2, 13)

        fun wireOrderForBuild(type: UpdateBlockType): Int {
            if (!experimentalBuild()) return type.wireOrder
            val bit = PLAYER_MASK_950[type] ?: return Int.MAX_VALUE
            return DISPATCH_ORDER_950.indexOf(Integer.numberOfTrailingZeros(bit))
        }

        fun experimentalBuild(): Boolean =
            try {
                OpenNXT.config.build != ServerConfig.SUPPORTED_BUILD
            } catch (_: UninitializedPropertyAccessException) {
                false
            }

        fun playerMaskForBuild(type: UpdateBlockType): Int =
            if (!experimentalBuild()) type.playerMask else PLAYER_MASK_950[type] ?: 0

        private fun writeMask950(buffer: GamePacketBuilder, dataMask: Int) {
            for ((_, bit) in CONTINUATIONS_950) {
                require((dataMask ushr bit) and 1 == 0) {
                    "player mask bit $bit is a continuation flag on build 950, " +
                        "not a block bit; writeMask sets it itself"
                }
            }
            var mask = dataMask
            for ((threshold, bit) in CONTINUATIONS_950) {
                if ((mask ushr threshold) != 0) mask = mask or (1 shl bit)
            }
            buffer.put(DataType.BYTE, mask and 0xff)
            if ((mask ushr 4) and 1 == 1) buffer.put(DataType.BYTE, (mask ushr 8) and 0xff)
            if ((mask ushr 15) and 1 == 1) buffer.put(DataType.BYTE, (mask ushr 16) and 0xff)
            if ((mask ushr 18) and 1 == 1) buffer.put(DataType.BYTE, (mask ushr 24) and 0xff)
        }

        fun withContinuations(dataMask: Int): Int {
            var mask = dataMask
            for ((threshold, bit) in CONTINUATIONS) {
                if ((mask ushr threshold) != 0) mask = mask or (1 shl bit)
            }
            return mask
        }

        fun maskLength(dataMask: Int): Int {
            val continuations = if (experimentalBuild()) CONTINUATIONS_950 else CONTINUATIONS
            var mask = dataMask
            for ((threshold, bit) in continuations) {
                if ((mask ushr threshold) != 0) mask = mask or (1 shl bit)
            }
            var n = 1
            for ((_, bit) in continuations) if ((mask ushr bit) and 1 == 1) n++
            return n
        }
    }
}

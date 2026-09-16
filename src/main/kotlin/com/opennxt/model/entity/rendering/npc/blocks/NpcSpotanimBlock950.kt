package com.opennxt.model.entity.rendering.npc.blocks

import com.opennxt.model.entity.rendering.ScalarModes
import com.opennxt.net.buf.DataType
import com.opennxt.net.buf.GamePacketBuilder

class NpcSpotanimBlock950(val entries: List<Entry>) {
    data class Entry(
        val slot: Int,
        val spotanim: Int,
        val height: Int = 0,
        val delay: Int = 0,
        val flag: Boolean = false,
        val rotation: Int = 0,
        val subtile: Int = SUBTILE_NEUTRAL
    ) {
        init {
            require(slot in 0..0xff) { "slot is a byte: $slot" }
            require(spotanim in 0..0xffff) { "spotanim is a u16 (0xffff clears): $spotanim" }
            require(delay in 0..0x7fff) { "delay is 15 bits: $delay" }
            require(height in 0..0xffff) { "height packs into the u32's high half: $height" }
            require(rotation in 0..0xff) { "rotation is a byte: $rotation" }
            require(subtile in 0..0xffffff) { "subtile is a u24: $subtile" }
        }

        val packed: Int get() = (delay and 0x7fff) or (if (flag) 0x8000 else 0) or ((height and 0xffff) shl 16)
    }

    init {
        require(entries.size in 1..0xff) { "1..255 entries, was ${entries.size}" }
    }

    fun encode950(buffer: GamePacketBuilder) {
        buffer.put(DataType.BYTE, entries.size)
        for (e in entries) {
            ScalarModes.putU8(buffer, SLOT_MODE, e.slot)
            ScalarModes.putU16(buffer, SPOTANIM_MODE, e.spotanim)
            ScalarModes.putU32(buffer, PACKED_MODE, e.packed)
            ScalarModes.putU8(buffer, ROTATION_MODE, e.rotation)
            ScalarModes.putU24(buffer, SUBTILE_MODE, e.subtile)
        }
    }

    fun length(): Int = 1 + ENTRY_WIDTH * entries.size

    companion object {
        const val MASK_BIT_950 = 24

        const val ORDER_950 = 22

        const val SLOT_MODE = 1
        const val SPOTANIM_MODE = 0
        const val PACKED_MODE = 2
        const val ROTATION_MODE = 3
        const val SUBTILE_MODE = 3

        const val ENTRY_WIDTH = 1 + 2 + 4 + 1 + 3

        const val SUBTILE_NEUTRAL = 0x5ffbff

        const val DEFAULT_SLOT = 4

        const val CLEAR = 0xffff

        fun enabled(): Boolean = System.getProperty("opennxt.combat.npcSpotanim") == "true"

        fun single(spotanim: Int, height: Int = 0, delay: Int = 0, slot: Int = DEFAULT_SLOT) =
            NpcSpotanimBlock950(listOf(Entry(slot = slot, spotanim = spotanim, height = height, delay = delay)))
    }
}

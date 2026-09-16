package com.opennxt.model.entity.rendering.npc.blocks

import com.opennxt.model.entity.rendering.ScalarModes
import com.opennxt.model.entity.rendering.npc.NpcUpdateBlock
import com.opennxt.model.entity.rendering.npc.NpcUpdateBlockType
import com.opennxt.net.buf.DataType
import com.opennxt.net.buf.GamePacketBuilder

class NpcFaceEntityBlock(val index: Int, val tag: Int) :
    NpcUpdateBlock(NpcUpdateBlockType.FACE_ENTITY) {
    init {
        require(index in 0..0xffff) {
            "face-entity index $index does not fit in 16 bits"
        }
        require(tag in KNOWN_TAGS) {
            "unknown face-entity tag 0x%02x".format(tag)
        }
    }

    override fun encode(buffer: GamePacketBuilder) {
        ScalarModes.putU24(buffer, MODE, (tag shl 16) or index)
    }

    override fun encode950(buffer: GamePacketBuilder) {
        buffer.put(DataType.BYTE, (index shr 8) and 0xff)
        buffer.put(DataType.BYTE, tag)
        buffer.put(DataType.BYTE, index and 0xff)
    }

    companion object {
        const val MODE = 0

        const val TAG_NPC = 1

        const val TAG_PLAYER = 2

        const val TAG_CLEAR = 0x7f

        const val TAG_CLEAR_ALT = 0xff

        val KNOWN_TAGS = setOf(TAG_NPC, TAG_PLAYER, TAG_CLEAR, TAG_CLEAR_ALT)

        fun npc(index: Int) = NpcFaceEntityBlock(index, TAG_NPC)
        fun player(index: Int) = NpcFaceEntityBlock(index, TAG_PLAYER)

        fun clear() = NpcFaceEntityBlock(0xffff, TAG_CLEAR)
    }
}

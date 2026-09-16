package com.opennxt.model.entity.rendering.blocks

import com.opennxt.model.entity.Entity
import com.opennxt.model.entity.rendering.UpdateBlock
import com.opennxt.model.entity.rendering.UpdateBlockType
import com.opennxt.model.world.WorldPlayer
import com.opennxt.net.buf.DataType
import com.opennxt.net.buf.GamePacketBuilder

class PlayerFaceEntityBlock(val index: Int, val tag: Int) : UpdateBlock(UpdateBlockType.FACE_ENTITY) {
    init {
        require(index in 0..0xffff) { "the face-entity index is 16 bits; $index does not fit" }
        require(tag in KNOWN_TAGS) {
            "tag 0x%02x is not recognised by the client - it would consume the three bytes and do nothing".format(tag)
        }
    }

    override fun encode(buffer: GamePacketBuilder, viewer: WorldPlayer, entity: Entity) {
        buffer.put(DataType.BYTE, index and 0xff)
        buffer.put(DataType.BYTE, (index shr 8) and 0xff)
        buffer.put(DataType.BYTE, tag)
    }

    companion object {
        const val TAG_NPC = 1

        const val TAG_PLAYER = 2

        const val TAG_CLEAR = 0x7f

        const val TAG_CLEAR_ALT = 0xff

        val KNOWN_TAGS = setOf(TAG_NPC, TAG_PLAYER, TAG_CLEAR, TAG_CLEAR_ALT)

        fun npc(index: Int) = PlayerFaceEntityBlock(index, TAG_NPC)
        fun player(index: Int) = PlayerFaceEntityBlock(index, TAG_PLAYER)

        fun clear() = PlayerFaceEntityBlock(0xffff, TAG_CLEAR)
    }
}

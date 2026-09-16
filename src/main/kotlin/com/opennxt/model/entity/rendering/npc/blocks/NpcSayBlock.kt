package com.opennxt.model.entity.rendering.npc.blocks

import com.opennxt.model.entity.rendering.npc.NpcUpdateBlock
import com.opennxt.model.entity.rendering.npc.NpcUpdateBlockType
import com.opennxt.net.buf.GamePacketBuilder

class NpcSayBlock(val text: String) : NpcUpdateBlock(NpcUpdateBlockType.SAY) {
    init {
        require(!text.contains('\u0000')) {
            "overhead text must not contain NUL characters"
        }
        require(text.length <= MAX_LENGTH) {
            "overhead text exceeds $MAX_LENGTH characters"
        }
    }

    override fun encode(buffer: GamePacketBuilder) {
        buffer.putString(text)
    }

    override fun encode950(buffer: GamePacketBuilder) {
        buffer.putString(text)
    }

    companion object {
        const val MAX_LENGTH = 250
    }
}

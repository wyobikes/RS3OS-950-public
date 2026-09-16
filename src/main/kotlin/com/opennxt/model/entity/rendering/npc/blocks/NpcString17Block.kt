package com.opennxt.model.entity.rendering.npc.blocks

import com.opennxt.model.entity.rendering.npc.NpcUpdateBlock
import com.opennxt.model.entity.rendering.npc.NpcUpdateBlockType
import com.opennxt.net.buf.GamePacketBuilder

class NpcString17Block(val text: String) : NpcUpdateBlock(NpcUpdateBlockType.STRING_17) {
    init {
        require(!text.contains('\u0000')) {
            "text must not contain a NUL character"
        }
        require(text.length <= MAX_LENGTH) {
            "text is longer than $MAX_LENGTH characters"
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

        fun restoreDefault() = NpcString17Block("")
    }
}

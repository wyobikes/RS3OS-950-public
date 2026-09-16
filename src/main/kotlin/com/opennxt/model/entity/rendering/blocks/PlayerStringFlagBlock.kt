package com.opennxt.model.entity.rendering.blocks

import com.opennxt.model.entity.Entity
import com.opennxt.model.entity.rendering.ScalarModes
import com.opennxt.model.entity.rendering.UpdateBlock
import com.opennxt.model.entity.rendering.UpdateBlockType
import com.opennxt.model.world.WorldPlayer
import com.opennxt.net.buf.GamePacketBuilder

class PlayerStringFlagBlock(val text: String, val flag: Int = FLAG_ENABLE) :
    UpdateBlock(UpdateBlockType.STRING_18) {
    init {
        require(!text.contains('\u0000')) {
            "text must not contain a NUL character"
        }
        require(text.length <= MAX_LENGTH) {
            "text is longer than $MAX_LENGTH characters"
        }
        require(flag in 0..0xff) { "the flag is one plain byte; got $flag" }
    }

    override fun encode(buffer: GamePacketBuilder, viewer: WorldPlayer, entity: Entity) {
        buffer.putString(text)
        ScalarModes.putU8(buffer, FLAG_MODE, flag)
    }

    companion object {
        const val FLAG_MODE = 0

        const val FLAG_ENABLE = 1

        const val MAX_LENGTH = 250
    }
}

package com.opennxt.model.entity.rendering.npc.blocks

import com.opennxt.model.entity.rendering.npc.NpcUpdateBlock
import com.opennxt.model.entity.rendering.npc.NpcUpdateBlockType
import com.opennxt.net.buf.DataTransformation
import com.opennxt.net.buf.DataType
import com.opennxt.net.buf.GamePacketBuilder

class NpcAnimationBlock(
    val ids: IntArray,
    val delay: Int
) : NpcUpdateBlock(NpcUpdateBlockType.ANIMATION) {
    init {
        require(ids.size == SLOTS) {
            "the animation block is exactly $SLOTS ids; got ${ids.size}"
        }
        require(delay in 0..255) { "the delay is one byte; got $delay" }
        for (id in ids) {
            require(id == NONE || id >= 0) {
                "an animation id is either $NONE or non-negative; $id would arrive as $NONE"
            }
        }
    }

    override fun encode(buffer: GamePacketBuilder) {
        for (id in ids) buffer.putLargeSmart(id)
        buffer.put(DataType.BYTE, DataTransformation.ADD, delay)
    }

    override fun encode950(buffer: GamePacketBuilder) {
        for (id in ids) buffer.putLargeSmart(id)
        buffer.put(DataType.BYTE, DataTransformation.NONE, delay)
    }

    companion object {
        const val SLOTS = 4

        const val NONE = -1

        fun single(id: Int, delay: Int = 0): NpcAnimationBlock =
            NpcAnimationBlock(intArrayOf(id, NONE, NONE, NONE), delay)

        fun stop(): NpcAnimationBlock =
            NpcAnimationBlock(intArrayOf(NONE, NONE, NONE, NONE), 0)
    }
}

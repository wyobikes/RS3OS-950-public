package com.opennxt.model.entity.rendering.blocks

import com.opennxt.model.entity.Entity
import com.opennxt.model.entity.rendering.ScalarModes
import com.opennxt.model.entity.rendering.UpdateBlock
import com.opennxt.model.entity.rendering.UpdateBlockType
import com.opennxt.model.world.WorldPlayer
import com.opennxt.net.buf.DataType
import com.opennxt.net.buf.GamePacketBuilder

class PlayerByte11Block(val value: Int) : UpdateBlock(UpdateBlockType.BYTE_11) {
    init {
        require(value in 0..0xff) { "the value is one plain byte; got $value" }
    }

    override fun encode(buffer: GamePacketBuilder, viewer: WorldPlayer, entity: Entity) {
        buffer.put(DataType.BYTE, value)
    }
}

class PlayerFlag21Block(val value: Int) : UpdateBlock(UpdateBlockType.FLAG_21) {
    init {
        require(value in 0..0xff) { "the value is one ADD byte; got $value" }
    }

    override fun encode(buffer: GamePacketBuilder, viewer: WorldPlayer, entity: Entity) {
        ScalarModes.putU8(buffer, MODE, value)
    }

    companion object {
        const val MODE = 1

        const val SET_VALUE = 1

        const val CLEAR_VALUE = 0

        fun set() = PlayerFlag21Block(SET_VALUE)
        fun clear() = PlayerFlag21Block(CLEAR_VALUE)
    }
}

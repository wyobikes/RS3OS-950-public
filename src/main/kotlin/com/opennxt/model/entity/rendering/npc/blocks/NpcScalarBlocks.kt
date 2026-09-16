package com.opennxt.model.entity.rendering.npc.blocks

import com.opennxt.model.entity.rendering.ScalarModes
import com.opennxt.model.entity.rendering.npc.NpcUpdateBlock
import com.opennxt.model.entity.rendering.npc.NpcUpdateBlockType
import com.opennxt.net.buf.GamePacketBuilder

class NpcShort22Block(val value: Int) : NpcUpdateBlock(NpcUpdateBlockType.SHORT_22) {
    init {
        require(value in 0..0xffff) { "the value is one BE u16; got $value" }
    }

    override fun encode(buffer: GamePacketBuilder) {
        ScalarModes.putU16(buffer, MODE, value)
    }

    companion object {
        const val MODE = 0

        const val DEFAULT = 0xffff

        fun clear() = NpcShort22Block(DEFAULT)
    }
}

class NpcByte25Block(val value: Int) : NpcUpdateBlock(NpcUpdateBlockType.BYTE_25) {
    init {
        require(value in 0..0xff) { "the value is one SUBTRACT byte; got $value" }
    }

    override fun encode(buffer: GamePacketBuilder) {
        ScalarModes.putU8(buffer, MODE, value)
    }

    companion object {
        const val MODE = 3
    }
}

class NpcFlag28Block(val value: Int) : NpcUpdateBlock(NpcUpdateBlockType.FLAG_28) {
    init {
        require(value in 0..0xff) { "the value is one ADD byte; got $value" }
    }

    override fun encode(buffer: GamePacketBuilder) {
        ScalarModes.putU8(buffer, MODE, value)
    }

    companion object {
        const val MODE = 1

        const val SET_VALUE = 1
        const val CLEAR_VALUE = 0

        fun set() = NpcFlag28Block(SET_VALUE)
        fun clear() = NpcFlag28Block(CLEAR_VALUE)
    }
}

class NpcByte34Block(val value: Int) : NpcUpdateBlock(NpcUpdateBlockType.BYTE_34) {
    init {
        require(value in 0..0xff) { "the value is one SUBTRACT byte; got $value" }
    }

    val clears: Boolean get() = value == CLEAR_VALUE

    override fun encode(buffer: GamePacketBuilder) {
        ScalarModes.putU8(buffer, MODE, value)
    }

    companion object {
        const val MODE = 3

        const val CLEAR_VALUE = 0x80

        fun clear() = NpcByte34Block(CLEAR_VALUE)
    }
}

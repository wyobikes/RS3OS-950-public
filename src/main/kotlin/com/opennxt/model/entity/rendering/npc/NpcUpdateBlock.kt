package com.opennxt.model.entity.rendering.npc

import com.opennxt.net.buf.GamePacketBuilder

abstract class NpcUpdateBlock(val type: NpcUpdateBlockType) {
    abstract fun encode(buffer: GamePacketBuilder)

    open fun encode950(buffer: GamePacketBuilder) {
        throw UnsupportedOperationException("$type has no build-950 encoding; NpcUpdates.sendable must have refused it")
    }
}

package com.opennxt.model.entity.rendering

import com.opennxt.model.entity.Entity
import com.opennxt.model.world.WorldPlayer
import com.opennxt.net.buf.GamePacketBuilder

abstract class UpdateBlock(val type: UpdateBlockType) {
    abstract fun encode(buffer: GamePacketBuilder, viewer: WorldPlayer, entity: Entity)

    fun needsUpdate(viewer: WorldPlayer): Boolean = true
}

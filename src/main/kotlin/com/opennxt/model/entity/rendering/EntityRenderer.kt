package com.opennxt.model.entity.rendering

import com.opennxt.model.entity.Entity
import com.opennxt.model.entity.PlayerEntity
import com.opennxt.model.world.WorldPlayer

class EntityRenderer(val entity: Entity) {
    val blocks = arrayOfNulls<UpdateBlock>(30)
    private val isPlayer = entity is PlayerEntity

    fun needsUpdate(viewer: WorldPlayer): Boolean {
        for (block in blocks) {
            if (block == null) continue
            if (block.needsUpdate(viewer)) return true
        }
        return false
    }
}

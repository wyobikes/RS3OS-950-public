package com.opennxt.model.entity

import com.opennxt.model.entity.movement.Movement
import com.opennxt.model.entity.rendering.EntityRenderer
import com.opennxt.model.world.TileLocation

abstract class Entity(var location: TileLocation) {
    var index: Int = -1
    var previousLocation: TileLocation = location

    abstract val renderer: EntityRenderer
    abstract val movement: Movement

    abstract fun clean()
}

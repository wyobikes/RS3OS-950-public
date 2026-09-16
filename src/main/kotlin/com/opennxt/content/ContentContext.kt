package com.opennxt.content

import com.opennxt.model.items.Equipment
import com.opennxt.model.items.ItemContainer
import com.opennxt.model.vars.VarPlayerState
import com.opennxt.model.world.TileLocation
import com.opennxt.resources.sqlite.LocDefinition
import com.opennxt.resources.sqlite.NpcDefinition

class ContentPlayer(
    val name: String = "content",
    var location: TileLocation = TileLocation(0, 0, 0),
    var onTeleport: ((TileLocation) -> Unit)? = null,
    val inventory: ItemContainer = ItemContainer.inventory(),
    val equipment: Equipment = Equipment(),
    val vars: VarPlayerState = VarPlayerState(),
    var onAnimate: ((Int) -> Unit)? = null
) {
    fun distanceTo(x: Int, z: Int): Int =
        maxOf(Math.abs(location.x - x), Math.abs(location.y - z))

    override fun toString() = "ContentPlayer($name at ${location.x},${location.y},${location.plane})"
}

sealed class ContentContext {
    abstract val player: ContentPlayer
    abstract val action: String
    abstract val actionSlot: Int
}

class LocContext(
    override val player: ContentPlayer,
    val definition: LocDefinition,
    override val action: String,
    override val actionSlot: Int,
    val x: Int,
    val z: Int,
    val plane: Int
) : ContentContext() {
    val locId: Int get() = definition.id
    override fun toString() =
        "LocContext(${definition.id} '${definition.name}' $action@$actionSlot at $x,$z,$plane)"
}

class NpcContext(
    override val player: ContentPlayer,
    val definition: NpcDefinition,
    override val action: String,
    override val actionSlot: Int,
    val npcIndex: Int = -1,
    val x: Int = 0,
    val z: Int = 0,
    val plane: Int = 0
) : ContentContext() {
    val npcId: Int get() = definition.id
    override fun toString() =
        "NpcContext(${definition.id} '${definition.name}' $action@$actionSlot index=$npcIndex)"
}

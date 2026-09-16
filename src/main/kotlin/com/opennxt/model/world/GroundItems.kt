package com.opennxt.model.world

import com.opennxt.model.items.AddResult
import com.opennxt.model.items.ItemContainer

class GroundItems {
    companion object {
        const val DESPAWN_TICKS = 200

        const val OWNER_ONLY_TICKS = 100

        const val PICKUP_RANGE = 1
    }

    private val items = ArrayList<GroundItem>()

    fun spawn(death: DeathResult, tile: TileLocation, owner: String?): SpawnResult {
        val spawned = ArrayList<GroundItem>()
        val skipped = ArrayList<SkippedUnresolved>()
        for (line in death.dropped) {
            if (line.itemIds.isEmpty()) {
                skipped += SkippedUnresolved(
                    itemName = line.itemName,
                    reason = "drop item name resolved to no item id; nothing to ground",
                    rarity = line.rarity,
                    source = line.source
                )
                continue
            }
            val qty = line.quantity
            if (qty == null) {
                skipped += SkippedUnresolved(
                    itemName = line.itemName,
                    reason = "drop quantity did not parse; no amount to ground",
                    rarity = line.rarity,
                    source = line.source
                )
                continue
            }
            if (qty <= 0) {
                skipped += SkippedUnresolved(
                    itemName = line.itemName,
                    reason = "quantity $qty is not positive -- nothing to ground",
                    rarity = line.rarity,
                    source = line.source
                )
                continue
            }
            spawned += GroundItem(
                itemId = line.itemId ?: line.itemIds.min(),
                itemIdCandidates = line.itemIds,
                itemName = line.itemName,
                quantity = qty,
                tile = TileLocation(tile.x, tile.y, tile.plane),
                owner = owner,
                source = line.source,
                ticksRemaining = DESPAWN_TICKS
            )
        }
        items += spawned
        return SpawnResult(spawned, skipped)
    }

    fun spawnItem(
        itemId: Int,
        itemName: String,
        quantity: Int,
        tile: TileLocation,
        owner: String? = null,
        source: String = "server",
        ticksRemaining: Int = DESPAWN_TICKS
    ): GroundItem {
        require(quantity > 0) { "ground item quantity must be positive: $quantity" }
        val item = GroundItem(
            itemId = itemId,
            itemIdCandidates = listOf(itemId),
            itemName = itemName,
            quantity = quantity,
            tile = TileLocation(tile.x, tile.y, tile.plane),
            owner = owner,
            source = source,
            ticksRemaining = ticksRemaining
        )
        items += item
        return item
    }

    fun remove(item: GroundItem): Boolean = items.remove(item)

    fun pickup(playerTile: TileLocation, item: GroundItem, into: ItemContainer, takerName: String? = null): PickupResult {
        if (item !in items) return PickupResult.NotOnGround(item)
        if (!item.isPublic && item.owner != takerName) return PickupResult.NotYours(item, item.owner!!, item.ownerOnlyTicksRemaining)
        if (playerTile.plane != item.tile.plane ||
            !playerTile.withinDistance(item.tile, PICKUP_RANGE)
        ) {
            val dist = maxOf(
                Math.abs(playerTile.x - item.tile.x),
                Math.abs(playerTile.y - item.tile.y)
            )
            return PickupResult.TooFar(item, distance = dist, allowed = PICKUP_RANGE)
        }
        val add: AddResult = into.add(item.itemId, item.quantity)
        return when {
            add.added == 0 -> PickupResult.ContainerFull(item)
            add.complete -> {
                items.remove(item)
                PickupResult.PickedUp(item, add)
            }
            else -> {
                item.quantity -= add.added
                PickupResult.Partial(item, add)
            }
        }
    }

    fun tick(): List<GroundItem> {
        val despawned = ArrayList<GroundItem>()
        val it = items.iterator()
        while (it.hasNext()) {
            val item = it.next()
            item.ticksRemaining--
            if (item.ownerOnlyTicksRemaining > 0) item.ownerOnlyTicksRemaining--
            if (item.ticksRemaining <= 0) {
                it.remove()
                despawned += item
            }
        }
        return despawned
    }

    fun all(): List<GroundItem> = items

    fun count(): Int = items.size

    fun itemsAt(x: Int, y: Int, plane: Int = 0): List<GroundItem> =
        items.filter { it.tile.x == x && it.tile.y == y && it.tile.plane == plane }

    fun isOnGround(item: GroundItem): Boolean = item in items
}

class GroundItem(
    val itemId: Int,
    val itemIdCandidates: List<Int>,
    val itemName: String,
    var quantity: Int,
    val tile: TileLocation,
    val owner: String?,
    val source: String,
    var ticksRemaining: Int,
    var ownerOnlyTicksRemaining: Int = if (owner == null) 0 else GroundItems.OWNER_ONLY_TICKS
) {
    val itemIdAmbiguous: Boolean get() = itemIdCandidates.size > 1

    val isPublic: Boolean get() = ownerOnlyTicksRemaining <= 0

    override fun toString() =
        "GroundItem($itemName id=$itemId${if (itemIdAmbiguous) " of $itemIdCandidates" else ""} " +
            "x$quantity @ (${tile.x},${tile.y},${tile.plane}) owner=${owner ?: "none"} " +
            "despawn=${ticksRemaining}t)"
}

data class SkippedUnresolved(
    val itemName: String,
    val reason: String,
    val rarity: String,
    val source: String
) {
    override fun toString() = "SkippedUnresolved($itemName @ $rarity: $reason)"
}

data class SpawnResult(
    val spawned: List<GroundItem>,
    val skippedUnresolved: List<SkippedUnresolved>
) {
    override fun toString() =
        "SpawnResult(spawned=${spawned.map { "${it.itemName} x${it.quantity}" }}, " +
            "skippedUnresolved=$skippedUnresolved)"
}

sealed class PickupResult {
    data class NotOnGround(val item: GroundItem) : PickupResult()

    data class TooFar(val item: GroundItem, val distance: Int, val allowed: Int) : PickupResult()

    data class ContainerFull(val item: GroundItem) : PickupResult()

    data class NotYours(val item: GroundItem, val owner: String, val ticksUntilPublic: Int) : PickupResult()

    data class Partial(val item: GroundItem, val add: AddResult) : PickupResult()

    data class PickedUp(val item: GroundItem, val add: AddResult) : PickupResult()
}

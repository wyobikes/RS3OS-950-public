package com.opennxt.content.impl

import com.opennxt.content.ContentRegistry
import com.opennxt.content.ContentPlayer
import com.opennxt.content.LocContext
import com.opennxt.model.items.ItemContainer
import mu.KotlinLogging

object Gatherables {
    private val logger = KotlinLogging.logger { }

    @Volatile
    var containerSupplier: (com.opennxt.content.ContentPlayer) -> ItemContainer = { it.inventory }

    @Volatile
    var inventoryResend: (com.opennxt.content.ContentPlayer) -> Unit = { }

    @Volatile
    var messageSender: (com.opennxt.content.ContentPlayer, String) -> Unit = { _, _ -> }

    private val lastGather = java.util.Collections.synchronizedMap(java.util.WeakHashMap<ContentPlayer, Long>() as MutableMap<ContentPlayer, Long>)

    fun cooldownGate(player: ContentPlayer, tick: Long): Boolean {
        val last = lastGather[player]
        if (last != null && tick - last < Skilling.CYCLE_TICKS) return false
        lastGather[player] = tick
        return true
    }

    fun onGather(ctx: LocContext): Any? {
        val player = ctx.player

        val yield = ResourceNodes.yieldOf(ctx.locId, ResourceNodes.Kind.GATHERING)
        if (yield == null) {
            return "unresolved-item"
        }

        if (com.opennxt.resources.sqlite.RsDatabase.available &&
            com.opennxt.model.map.LocInteraction.placementOf(ctx.locId, ctx.x, ctx.z, ctx.plane) == null
        ) {
            return "no-placement"
        }

        val inv = containerSupplier(player)
        if (inv.isFull()) {
            messageSender(player, "Your inventory is too full to hold any more.")
            return "inventory-full"
        }
        if (!cooldownGate(player, Skilling.ticks())) return "cooldown"

        inv.add(yield.itemId, 1)
        inventoryResend(player)

        return "gathered"
    }

    fun install(): Pair<Int, Int> {
        val pick = ContentRegistry.onLocAction("Pick", ::onGather)
        val take = ContentRegistry.onLocAction("Take", ::onGather)
        logger.info { "gatherables: bound Pick across $pick locs, Take across $take" }
        return pick to take
    }
}

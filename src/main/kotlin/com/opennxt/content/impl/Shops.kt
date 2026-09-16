package com.opennxt.content.impl

import com.opennxt.content.ContentPlayer
import com.opennxt.content.ContentRegistry
import com.opennxt.content.DispatchResult
import com.opennxt.content.LocContext
import com.opennxt.content.NpcContext
import com.opennxt.content.SqliteDefinitions
import com.opennxt.model.shops.ShopData
import mu.KotlinLogging

object Shops {
    private val logger = KotlinLogging.logger { }

    const val STOCK_IS_NOT_IN_THE_CACHE =
        "STOCK_IS_NOT_IN_THE_CACHE: shop stock is not stored in the cache"

    const val OPENED = "shop-opened"

    data class ShopOpenedEvent(
        val player: String,
        val opener: ShopData.ShopOpener,
        val stock: List<ShopData.ItemStack>?,
        val stockAbsence: String = STOCK_IS_NOT_IN_THE_CACHE
    ) {
        fun itemValue(itemId: Int): Long? = ShopData.itemValue(itemId)
        fun itemName(itemId: Int): String? = ShopData.itemName(itemId)
        override fun toString() =
            "ShopOpenedEvent($player @ $opener, stock=${stock ?: "null[STOCK_IS_NOT_IN_THE_CACHE]"})"
    }

    private val events = ArrayList<ShopOpenedEvent>()

    fun eventCount(): Int = events.size
    fun lastEvent(): ShopOpenedEvent? = events.lastOrNull()
    fun clear() = events.clear()

    private fun fire(player: ContentPlayer, opener: ShopData.ShopOpener): Any {
        events.add(
            ShopOpenedEvent(
                player = player.name,
                opener = opener,
                stock = ShopData.stockFor(opener.gameId)
            )
        )
        return OPENED
    }

    fun onOpenNpcShop(ctx: NpcContext): Any? {
        val opener = ShopData.openersForNpc(ctx.npcId).firstOrNull { it.option == ctx.action }
            ?: return "not-a-documented-shop-opener"
        return fire(ctx.player, opener)
    }

    fun onOpenLocShop(ctx: LocContext): Any? {
        val opener = ShopData.openersForLoc(ctx.locId).firstOrNull { it.option == ctx.action }
            ?: return "not-a-documented-shop-opener"
        return fire(ctx.player, opener)
    }

    fun declaredForNpc(gameId: Int): List<String> {
        val cols = SqliteDefinitions.npc(gameId)?.actions?.filterNotNull() ?: emptyList()
        val attr = ShopData.openersForNpc(gameId).map { it.option }
        return (cols + attr).distinct()
    }

    fun openForNpc(
        player: ContentPlayer,
        gameId: Int,
        option: String,
        npcIndex: Int = -1,
        x: Int = 0,
        z: Int = 0,
        plane: Int = 0
    ): DispatchResult {
        val viaRegistry = ContentRegistry.dispatchNpc(player, gameId, option, npcIndex, x, z, plane)
        if (viaRegistry !is DispatchResult.Rejected) return viaRegistry

        val opener = ShopData.openersForNpc(gameId).firstOrNull { it.option == option && it.membersOnly }
            ?: return DispatchResult.Rejected(gameId, option, declaredForNpc(gameId))
        return DispatchResult.Handled(fire(player, opener))
    }

    fun openForLoc(
        player: ContentPlayer,
        locId: Int,
        option: String,
        x: Int,
        z: Int,
        plane: Int = 0
    ): DispatchResult {
        val viaRegistry = ContentRegistry.dispatchLoc(player, locId, option, x, z, plane)
        if (viaRegistry !is DispatchResult.Rejected) return viaRegistry

        val opener = ShopData.openersForLoc(locId).firstOrNull { it.option == option }
            ?: return DispatchResult.Rejected(
                locId, option,
                ((SqliteDefinitions.loc(locId)?.actions?.filterNotNull() ?: emptyList()) +
                    ShopData.openersForLoc(locId).map { it.option }).distinct()
            )
        return DispatchResult.Handled(fire(player, opener))
    }

    data class Installed(val npcBound: Int, val npcSeamOnly: Int, val locBound: Int, val locSeamOnly: Int) {
        override fun toString() =
            "Installed(npc: $npcBound bound + $npcSeamOnly fallback, loc: $locBound bound + $locSeamOnly fallback)"
    }

    fun install(): Installed {
        var npcBound = 0
        var npcSeamOnly = 0
        val seenNpc = HashSet<Pair<Int, String>>()
        for (o in ShopData.openers().filter { it.kind == ShopData.OpenerKind.NPC }) {
            if (!seenNpc.add(o.gameId to o.option)) continue
            val def = SqliteDefinitions.npc(o.gameId)
            if (def != null && def.actions.any { it == o.option }) {
                ContentRegistry.onNpc(o.gameId, o.option, ::onOpenNpcShop)
                npcBound++
            } else {
                npcSeamOnly++
            }
        }
        var locBound = 0
        var locSeamOnly = 0
        val seenLoc = HashSet<Pair<Int, String>>()
        for (o in ShopData.openers().filter { it.kind == ShopData.OpenerKind.LOC }) {
            if (!seenLoc.add(o.gameId to o.option)) continue
            val def = SqliteDefinitions.loc(o.gameId)
            if (def != null && def.actions.any { it == o.option }) {
                ContentRegistry.onLoc(o.gameId, o.option, ::onOpenLocShop)
                locBound++
            } else {
                locSeamOnly++
            }
        }
        val result = Installed(npcBound, npcSeamOnly, locBound, locSeamOnly)
        logger.info {
            "shops: $result (stock in cache: ${ShopData.STOCK_PRESENT_IN_CACHE})"
        }
        return result
    }
}

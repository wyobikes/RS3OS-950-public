package com.opennxt.content.impl

import com.opennxt.api.stat.Stat
import com.opennxt.content.ContentPlayer
import com.opennxt.content.LocContext
import com.opennxt.model.InterfaceHash
import com.opennxt.model.world.TileLocation
import com.opennxt.model.world.WorldPlayer
import com.opennxt.net.game.GamePacket
import com.opennxt.net.game.clientprot.IfButtonN
import com.opennxt.net.game.serverprot.RunClientScript
import com.opennxt.net.game.serverprot.ifaces.IfSethide
import com.opennxt.net.game.serverprot.variables.ClientSetvarcSmall
import com.opennxt.net.game.serverprot.variables.VarpLarge
import com.opennxt.net.game.serverprot.variables.VarpSmall
import mu.KotlinLogging
import java.util.Collections
import java.util.WeakHashMap

object SmithingPanel {
    private val logger = KotlinLogging.logger { }

    val enabled: Boolean get() = System.getProperty("opennxt.experiment.smithing.panel") != "false"

    const val IFACE = 37
    const val TOPLEVEL = 1477
    const val MOUNT = 726
    const val CLOSE_COMPONENT = 42
    const val BEGIN_COMPONENT = 163
    const val SMELT_HIDE_COMPONENT = 5

    val MATERIAL_ROWS: List<Int> = listOf(52, 62, 72, 82, 92)
    val PRODUCT_GRIDS: List<Int> = listOf(103, 114, 125, 136, 147)

    const val VARP_LOC = 8334
    const val VARP_COORD = 8335
    const val VARP_LIST_A = 8331
    const val VARP_LIST_B = 8332
    const val VARP_PRODUCT = 8333
    const val VARP_COUNT = 8336
    val ZERO_VARPS: List<Int> = listOf(1756, 1756, 1757, 1757, 1758, 1758, 1759, 1759, 3692, 3692, 3693, 3693, 3694)
    const val SCRIPT_STATION = 2600
    const val SCRIPT_LISTS = 2586
    const val SCRIPT_RESET = 8178
    const val VARC_MAKEABLE = 2223

    val EVENTS: List<IntArray> =
        MATERIAL_ROWS.map { intArrayOf(it, 0, 200, 62) } + PRODUCT_GRIDS.map { intArrayOf(it, 0, 200, 2) } +
            listOf(intArrayOf(35, 0, 60, 2))

    enum class Station(val listA: Int, val listB: Int, val smelting: Boolean) {
        FURNACE(1482, 1483, true), FORGE(1489, 1490, false), ANVIL(1489, 1490, false)
    }

    data class Session(val content: ContentPlayer, val station: Station, val locId: Int, var product: Int,
                       val locX: Int, val locZ: Int, val plane: Int)

    private val sessions: MutableMap<WorldPlayer, Session> = Collections.synchronizedMap(WeakHashMap())

    @Volatile var opens = 0
        private set
    @Volatile var begins = 0
        private set
    @Volatile var beginsRefusedDistance = 0
        private set
    @Volatile var beginsRefusedLocked = 0
        private set

    fun stationInReach(session: Session, here: TileLocation): Boolean =
        here.plane == session.plane &&
            com.opennxt.model.map.LocInteraction.adjacentToFootprint(here.x, here.y, session.locId, session.locX, session.locZ, session.plane)

    fun packCoord(x: Int, y: Int, plane: Int): Int = (plane shl 28) or (x shl 14) or y

    fun stationFor(action: String, hasProject: Boolean, locActions: List<String?> = emptyList()): Station? = when (action) {
        Smithing.SMELT_ACTION -> Station.FURNACE
        Smithing.HEAT_ACTION -> Station.FORGE
        Smithing.SMITH_ACTION -> if (hasProject) null else Station.ANVIL
        Smithing.OPEN_INTERFACE_ACTION -> when {
            Smithing.HEAT_ACTION in locActions -> Station.FORGE
            Smithing.SMITH_ACTION in locActions -> Station.ANVIL
            Smithing.SMELT_ACTION in locActions -> Station.FURNACE
            else -> null
        }
        else -> null
    }

    fun productsFor(station: Station): Set<Int> =
        if (station.smelting) Smithing.smeltRecipes.keys else Smithing.smithRecipes.keys

    val SECTION_TOGGLES: List<Int> = listOf(48, 58, 68, 78, 88, 99, 110)

    const val VARP_MATERIAL_RESET = 1174

    fun materialChoice(station: Station, currentProduct: Int, barId: Int): Int? {
        if (station.smelting) return barId.takeIf { it in Smithing.smeltRecipes }
        val recipes = Smithing.smithRecipes
        val usesBar = recipes.values.filter { r -> r.materials.any { it.itemId == barId } }
        if (usesBar.isEmpty()) return null
        val current = recipes[currentProduct]
        if (current != null && current.materials.any { it.itemId == barId }) return current.productId
        val oldMetal = current?.materials?.firstNotNullOfOrNull { m -> Smithing.smeltRecipes[m.itemId]?.barName }?.let(::metalOf)
        val newMetal = Smithing.smeltRecipes[barId]?.barName?.let(::metalOf)
        if (current != null && oldMetal != null && newMetal != null && current.productName.startsWith("$oldMetal ", ignoreCase = true)) {
            val wanted = newMetal + current.productName.substring(oldMetal.length)
            usesBar.firstOrNull { it.productName.equals(wanted, ignoreCase = true) }?.let { return it.productId }
        }
        return usesBar.minWithOrNull(compareBy<Smithing.SmithRecipe>({ it.level }, { it.productId }))?.productId
    }

    private fun metalOf(barName: String): String = barName.trim().removeSuffix(" bar").removeSuffix(" Bar")

    fun materialReply(station: Station, product: Int, count: Int): List<GamePacket> = listOf(
        VarpLarge(VARP_LIST_B, station.listB),
        VarpLarge(VARP_PRODUCT, product),
        if (count in -128..127) VarpSmall(VARP_COUNT, count) else VarpLarge(VARP_COUNT, count),
        VarpSmall(VARP_MATERIAL_RESET, 0),
        VarpSmall(VARP_MATERIAL_RESET, 0),
        VarpSmall(VARP_MATERIAL_RESET, 0)
    )

    fun countFor(content: ContentPlayer, station: Station, product: Int): Int {
        val container = Smithing.containerSupplier(content)
        val materials = (if (station.smelting) Smithing.smeltRecipes[product]?.materials else Smithing.smithRecipes[product]?.materials)
            ?: return 0
        if (materials.isEmpty()) return 0
        return materials.minOf { m -> if (m.count <= 0) Long.MAX_VALUE else container.count(m.itemId) / m.count }
            .coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
    }

    fun initialProduct(content: ContentPlayer, station: Station): Int? {
        val offered = productsFor(station)
        Smithing.selectionOf(content)?.takeIf { it in offered }?.let { return it }
        val container = Smithing.containerSupplier(content)
        val level = Smithing.levelSupplier(content, Stat.SMITHING)
        val chosen = if (station.smelting) Smithing.smeltChoice(container, level)?.barId else Smithing.smithChoice(container, level)?.productId
        return chosen ?: offered.minOrNull()
    }

    fun openBurst(station: Station, locId: Int, locX: Int, locY: Int, plane: Int, product: Int, count: Int): Pair<List<GamePacket>, List<GamePacket>> {
        val before = ArrayList<GamePacket>()
        before += VarpLarge(VARP_LOC, locId)
        before += VarpLarge(VARP_COORD, packCoord(locX, locY, plane))
        before += VarpLarge(VARP_LIST_A, station.listA)
        before += VarpLarge(VARP_LIST_B, station.listB)
        before += VarpLarge(VARP_PRODUCT, product)
        before += if (count in -128..127) VarpSmall(VARP_COUNT, count) else VarpLarge(VARP_COUNT, count)
        for (id in ZERO_VARPS) before += VarpSmall(id, 0)
        before += IfSethide(InterfaceHash(IFACE, SMELT_HIDE_COMPONENT), station.smelting)
        before += RunClientScript(SCRIPT_STATION, arrayOf(if (station.smelting) 1 else 0))
        before += RunClientScript(SCRIPT_LISTS, arrayOf(station.listA, station.listB))
        before += ClientSetvarcSmall(VARC_MAKEABLE, if (count > 0) 1 else 0)
        val after = listOf<GamePacket>(RunClientScript(SCRIPT_RESET, emptyArray()))
        return before to after
    }

    fun openFor(ctx: LocContext): Boolean {
        if (!enabled) return false
        val world = SmithingWiring.ownerOf(ctx.player) ?: return false
        if (!world.client.channel.isActive) return false
        val station = stationFor(ctx.action, Smithing.projectFor(ctx.player) != null, ctx.definition.actions.toList()) ?: return false
        val product = initialProduct(ctx.player, station) ?: run {
            logger.warn { "smithing panel: no ${station.name.lowercase()} recipes loaded; panel not opened" }
            return false
        }
        val count = countFor(ctx.player, station, product)
        val (before, after) = openBurst(station, ctx.locId, ctx.x, ctx.z, ctx.plane, product, count)
        for (p in before) world.client.write(p)
        world.interfaces.open(id = IFACE, parent = TOPLEVEL, component = MOUNT, walkable = false, native949 = true)
        for (p in after) world.client.write(p)
        for (e in EVENTS) world.interfaces.events(id = IFACE, component = e[0], from = e[1], to = e[2], mask = e[3])
        sessions[world] = Session(ctx.player, station, ctx.locId, product, ctx.x, ctx.z, ctx.plane)
        opens++
        logger.info {
            "smithing panel: ${world.name} ${ctx.action} on loc ${ctx.locId} -> interface $IFACE at $TOPLEVEL:$MOUNT as " +
                "${station.name}, product $product x$count"
        }
        return true
    }

    fun handleButton(player: WorldPlayer, packet: IfButtonN): Boolean {
        if (packet.interfaceId != IFACE) return false
        val session = sessions[player] ?: return false
        if (!player.interfaces.isOpened(IFACE)) {
            sessions.remove(player)
            return false
        }
        when (packet.component) {
            CLOSE_COMPONENT -> {
                close(player)
                logger.info { "smithing panel: ${player.name} closed the window (37:$CLOSE_COMPONENT)" }
            }
            in PRODUCT_GRIDS -> {
                val item = packet.mid
                if (item !in productsFor(session.station)) {
                    logger.info { "smithing panel: ${player.name} chose item $item on 37:${packet.component}, not a ${session.station.name.lowercase()} product" }
                    return true
                }
                session.product = item
                Smithing.select(session.content, item)
                val count = countFor(session.content, session.station, item)
                player.client.write(VarpLarge(VARP_PRODUCT, item))
                player.client.write(if (count in -128..127) VarpSmall(VARP_COUNT, count) else VarpLarge(VARP_COUNT, count))
                logger.info { "smithing panel: ${player.name} chose product $item (x$count) on 37:${packet.component}" }
            }
            BEGIN_COMPONENT -> {
                if (com.opennxt.content.ActionLock.refuse(session.content, "smithing BEGIN ($IFACE:$BEGIN_COMPONENT)")) {
                    beginsRefusedLocked++
                    return true
                }
                val here = Smithing.locationSupplier(session.content)
                if (!stationInReach(session, here)) {
                    close(player)
                    beginsRefusedDistance++
                    logger.info {
                        "smithing panel: ${player.name} pressed BEGIN at (${here.x}, ${here.y}, ${here.plane}), too far from " +
                            "${session.station.name} loc ${session.locId} at (${session.locX}, ${session.locZ}, ${session.plane})"
                    }
                    return true
                }
                close(player)
                Smithing.select(session.content, session.product)
                val result = when (session.station) {
                    Station.FURNACE -> Smithing.smelt(session.content, session.locId, here)
                    Station.FORGE -> Smithing.heat(session.content, session.locId, here)
                    Station.ANVIL -> Smithing.smith(session.content, session.locId, here)
                }
                begins++
                logger.info { "smithing panel: ${player.name} pressed BEGIN on ${session.station.name} product ${session.product} -> ${result.outcome} ${result.detail}" }
            }
            in MATERIAL_ROWS -> {
                val chosen = materialChoice(session.station, session.product, packet.mid)
                if (chosen == null) {
                    logger.info { "smithing panel: ${player.name} picked metal ${packet.mid} on 37:${packet.component}, no ${session.station.name.lowercase()} product for it" }
                    return true
                }
                session.product = chosen
                Smithing.select(session.content, chosen)
                val count = countFor(session.content, session.station, chosen)
                for (p in materialReply(session.station, chosen, count)) player.client.write(p)
                logger.info { "smithing panel: ${player.name} picked metal ${packet.mid} (37:${packet.component} slot ${packet.arg2}) -> product $chosen x$count" }
            }
            in SECTION_TOGGLES -> logger.info {
                "smithing panel: ${player.name} toggled section header 37:${packet.component}"
            }
            else -> logger.info {
                "smithing panel: ${player.name} IF_BUTTON${packet.buttonOp} 37:${packet.component} slot ${packet.arg2} mid ${packet.mid} not handled"
            }
        }
        return true
    }

    private fun close(player: WorldPlayer) {
        sessions.remove(player)
        player.interfaces.close(TOPLEVEL, MOUNT, native949 = true)
    }
}

package com.opennxt.content.impl

import com.opennxt.OpenNXT
import com.opennxt.model.entity.player.PlayerInventory
import com.opennxt.model.world.GroundItem
import com.opennxt.model.world.GroundItems
import com.opennxt.model.world.PickupResult
import com.opennxt.model.world.WorldPlayer
import com.opennxt.net.game.clientprot.IfButtonN
import com.opennxt.net.game.serverprot.InvEntry
import com.opennxt.net.game.serverprot.RunClientScript
import com.opennxt.net.game.serverprot.UpdateInvFull
import com.opennxt.net.game.serverprot.generated.SynthSound
import mu.KotlinLogging
import java.util.Collections
import java.util.WeakHashMap

object LootWindow {
    private val logger = KotlinLogging.logger { }

    const val LOOT_IFACE = 1622

    const val LOOT_PARENT = 1477
    const val LOOT_DOCK = 705

    const val SLOT_LAYER = 11

    const val LOOT_ALL_BUTTON = 21

    const val CLOSE_BUTTON = 8

    const val VALUE_TEXT_COMPONENT = 3

    const val LOOT_INV = 773

    const val LOOT_SLOTS = 28

    const val SLOT_EVENT_MASK = 6291462

    const val SCRIPT_SHOW = 8862
    const val SCRIPT_LAYOUT = 2651
    const val SCRIPT_REFRESH = 11413

    const val WINDOW_ID = 1028

    const val PICKUP_SOUND = 9704

    val LOOT_RADIUS: Int = System.getProperty("opennxt.loot.radius")?.toIntOrNull() ?: 2

    val CLOSE_RADIUS: Int = System.getProperty("opennxt.loot.closeRadius")?.toIntOrNull() ?: 16

    val enabled: Boolean get() = System.getProperty("opennxt.experiment.lootWindow") != "false"

    data class Row(val itemId: Int, val items: List<GroundItem>) {
        val quantity: Int get() = items.sumOf { it.quantity }
        val name: String get() = items.firstOrNull()?.itemName ?: "item $itemId"
    }

    private class Session(val x: Int, val y: Int, val plane: Int) {
        var sent: List<Pair<Int, Int>> = emptyList()
    }

    private val sessions: MutableMap<WorldPlayer, Session> =
        Collections.synchronizedMap(WeakHashMap<WorldPlayer, Session>())

    fun isOpen(player: WorldPlayer): Boolean = sessions[player] != null

    fun centreOf(player: WorldPlayer): Triple<Int, Int, Int>? =
        sessions[player]?.let { Triple(it.x, it.y, it.plane) }

    fun rowsAt(player: WorldPlayer, x: Int, y: Int, plane: Int, radius: Int = LOOT_RADIUS): List<Row> {
        val world = runCatching { OpenNXT.world }.getOrNull() ?: return emptyList()
        val visible = world.groundItems.all().filter {
            it.tile.plane == plane &&
                Math.abs(it.tile.x - x) <= radius && Math.abs(it.tile.y - y) <= radius &&
                (it.isPublic || it.owner == player.name)
        }
        fun dist(g: GroundItem) = maxOf(Math.abs(g.tile.x - x), Math.abs(g.tile.y - y))
        return visible.groupBy { it.itemId }
            .map { (id, items) -> Row(id, items.sortedBy { dist(it) }) }
            .sortedWith(compareBy({ dist(it.items.first()) }, { it.itemId }))
    }

    fun open(player: WorldPlayer, x: Int, y: Int, plane: Int): Boolean {
        if (!enabled) return false
        val rows = rowsAt(player, x, y, plane)
        if (rows.isEmpty()) return false

        val fresh = sessions[player] == null || centreOf(player) != Triple(x, y, plane)
        val session = Session(x, y, plane)
        sessions[player] = session

        sendRows(player, session, rows)
        if (fresh) {
            try {
                player.interfaces.open(
                    id = LOOT_IFACE, parent = LOOT_PARENT, component = LOOT_DOCK,
                    walkable = true, native949 = true
                )
                player.client.write(RunClientScript(SCRIPT_SHOW, arrayOf(WINDOW_ID, 1)))
                player.client.write(RunClientScript(SCRIPT_LAYOUT, arrayOf(WINDOW_ID, 0)))
                player.interfaces.events(
                    id = LOOT_IFACE, component = SLOT_LAYER, from = 0, to = LOOT_SLOTS - 1,
                    mask = SLOT_EVENT_MASK
                )
                player.client.write(RunClientScript(SCRIPT_REFRESH, emptyArray()))
            } catch (t: Throwable) {
                sessions.remove(player)
                logger.error(t) {
                    "loot ${player.name}: could not mount $LOOT_IFACE at $LOOT_PARENT:$LOOT_DOCK; " +
                        "no window is open and nothing was taken."
                }
                return false
            }
        }
        logger.info {
            "loot ${player.name}: window ${if (fresh) "opened" else "re-centred"} on ($x,$y,p$plane) " +
                "with ${rows.size} row(s): " + rows.joinToString(", ") { "${it.name} x${it.quantity}" }
        }
        return true
    }

    fun close(player: WorldPlayer, reason: String) {
        if (sessions.remove(player) == null) return
        player.interfaces.close(id = LOOT_PARENT, component = LOOT_DOCK, native949 = true)
        logger.info { "loot ${player.name}: window closed ($reason)" }
    }

    fun refresh(player: WorldPlayer) {
        val session = sessions[player] ?: return
        val here = player.entity.location
        if (here.plane != session.plane ||
            maxOf(Math.abs(here.x - session.x), Math.abs(here.y - session.y)) > CLOSE_RADIUS
        ) {
            close(player, "the player left the pile")
            return
        }
        val rows = rowsAt(player, session.x, session.y, session.plane)
        if (rows.isEmpty()) {
            close(player, "the pile is empty")
            return
        }
        if (rows.map { it.itemId to it.quantity } != session.sent) sendRows(player, session, rows)
    }

    private fun sendRows(player: WorldPlayer, session: Session, rows: List<Row>) {
        val slots: List<InvEntry?> = rows.map { InvEntry(it.itemId, it.quantity) }
        player.client.write(UpdateInvFull(inv = LOOT_INV, slots = slots, flags = 0))
        session.sent = rows.map { it.itemId to it.quantity }
    }

    internal fun invPacketFor(rows: List<Row>): UpdateInvFull =
        UpdateInvFull(inv = LOOT_INV, slots = rows.map { InvEntry(it.itemId, it.quantity) }, flags = 0)

    fun handleButton(player: WorldPlayer, packet: IfButtonN): Boolean {
        if (!enabled) return false
        if (packet.interfaceId != LOOT_IFACE) return false
        when (packet.component) {
            CLOSE_BUTTON -> {
                close(player, "the player clicked the window's Close")
                return true
            }
            LOOT_ALL_BUTTON -> {
                lootAll(player)
                return true
            }
            SLOT_LAYER -> {
                takeSlot(player, packet.buttonOp, packet.mid, packet.arg2)
                return true
            }
        }
        return false
    }

    internal fun takeSlot(player: WorldPlayer, op: Int, itemId: Int, slot: Int) {
        val session = sessions[player] ?: return
        val row = ItemOps.rowForOp(player, LOOT_IFACE, SLOT_LAYER, op)
        if (row != 0) {
            logger.info {
                "loot ${player.name}: refused op $op on the grid - it maps to row " +
                    "${row?.plus(1) ?: "nothing"} under the armed mask, and only row 1 (take) is routed."
            }
            return
        }
        val rows = rowsAt(player, session.x, session.y, session.plane)
        val target = rows.getOrNull(slot)
        if (target == null || target.itemId != itemId) {
            logger.info {
                "loot ${player.name}: refused a take - the client says slot $slot holds item $itemId, " +
                    "the server's pile has ${target?.itemId ?: "nothing"} there."
            }
            refresh(player)
            return
        }
        takeRow(player, target)
        refresh(player)
    }

    internal fun lootAll(player: WorldPlayer) {
        val session = sessions[player] ?: return
        var taken = 0
        for (row in rowsAt(player, session.x, session.y, session.plane)) {
            val result = takeRow(player, row, sendInventory = false)
            if (result == Outcome.TAKEN) taken++
            if (result == Outcome.BLOCKED) break
        }
        if (taken > 0) PlayerInventory.sendBackpack(player)
        logger.info { "loot ${player.name}: Loot All took $taken row(s)" }
        refresh(player)
    }

    internal enum class Outcome { TAKEN, SKIPPED, BLOCKED }

    internal fun takeRow(player: WorldPlayer, row: Row, sendInventory: Boolean = true): Outcome {
        val world = runCatching { OpenNXT.world }.getOrNull() ?: return Outcome.BLOCKED
        val backpack = PlayerInventory.backpackOf(player)
        var moved = false
        var blocked = false
        for (item in row.items) {
            val before = item.quantity
            when (val result = world.groundItems.pickup(player.entity.location, item, backpack, player.name)) {
                is PickupResult.PickedUp -> {
                    moved = true
                    logger.info { "loot ${player.name}: took ${item.itemName} x$before (obj ${item.itemId})" }
                }
                is PickupResult.Partial -> {
                    moved = true
                    blocked = true
                    logger.info {
                        "loot ${player.name}: took ${result.add.added} of ${item.itemName} x$before; " +
                            "${result.add.remaining} left on the ground (no room for the rest)"
                    }
                }
                is PickupResult.ContainerFull -> {
                    blocked = true
                    logger.info { "loot ${player.name}: backpack full; ${item.itemName} stays on the ground" }
                }
                is PickupResult.TooFar -> {
                    blocked = true
                    logger.info {
                        "loot ${player.name}: ${result.distance} tile(s) from ${item.itemName} " +
                            "(max ${result.allowed}); walking to it instead of taking it"
                    }
                    com.opennxt.net.game.handlers.MoveGameClickHandler
                        .walk(player, item.tile.x, item.tile.y, "loot-window")
                }
                is PickupResult.NotOnGround ->
                    logger.info { "loot ${player.name}: ${item.itemName} is no longer on the ground" }
                is PickupResult.NotYours -> {
                    blocked = true
                    logger.info {
                        "loot ${player.name}: ${item.itemName} belongs to ${result.owner} for another " +
                            "${result.ticksUntilPublic} tick(s). Refused."
                    }
                }
            }
        }
        if (moved) {
            if (sendInventory) PlayerInventory.sendBackpack(player)
            player.client.write(SynthSound(PICKUP_SOUND, 1, 0, 100, 256))
        }
        return when {
            blocked -> Outcome.BLOCKED
            moved -> Outcome.TAKEN
            else -> Outcome.SKIPPED
        }
    }
}

package com.opennxt.content.impl

import com.opennxt.content.ActionSlot
import com.opennxt.model.world.TileLocation
import com.opennxt.model.world.WorldPlayer
import com.opennxt.net.game.clientprot.IfButtonN
import com.opennxt.resources.sqlite.RsDatabase
import com.opennxt.resources.sqlite.SqliteItemCodec
import mu.KotlinLogging

object Teleports {
    private val logger = KotlinLogging.logger { }

    val enabled: Boolean get() = System.getProperty("opennxt.experiment.teleports") != "false"

    val DEFAULT_ORDER: List<String> =
        listOf("REBUILD_NORMAL", "PLAYER_INFO", "UPDATE_ZONE_FULL_FOLLOWS", "CAM_FORCEANGLE")

    const val KNOWN_TELEPORTS = 14
    const val CROSSINGS = 3
    const val MIN_TELEPORT_ZONES = 159
    const val MAX_CROSSING_ZONES = 52

    const val CORPUS_TELEPORTS = 20
    const val CORPUS_CROSSINGS = 6

    val CAMERA_PACKETS: List<String> = listOf("CAM_FORCEANGLE", "CAM_RESET")

    const val MAP_FLAG_TELEPORTS = 13

    const val SEQUENCE_TICKS = 1

    @Volatile
    var teleports: Int = 0
        private set

    @Volatile
    var refused: Int = 0
        private set

    internal fun resetCounters() { teleports = 0; refused = 0; uncancelledActions = 0 }

    fun teleport(player: WorldPlayer, destination: TileLocation, reason: String = "teleport"): Boolean {
        if (!enabled) { refused++; return false }
        if (destination.plane !in 0..3) {
            refused++
            logger.warn { "teleport: refusing $reason for ${player.name} to $destination - plane is not 0..3" }
            return false
        }

        val cancelled = cancelActions(player)
        if (!cancelled) uncancelledActions++

        MapFlag.clear(player)

        player.viewport.moveToRegion(destination, player.viewport.mapSize, sendUpdate = true)

        player.entity.movement.teleport(destination)

        teleports++
        logger.info {
            "teleport: ${player.name} -> (${destination.x},${destination.y},plane ${destination.plane}) " +
                "for '$reason'"
        }
        return true
    }

    @Volatile
    var actionCanceller: ((WorldPlayer) -> Boolean)? = null

    @Volatile
    var uncancelledActions: Int = 0
        internal set

    private fun cancelActions(player: WorldPlayer): Boolean = actionCanceller?.invoke(player) ?: false

    private data class Pending(
        val destination: TileLocation,
        val reason: String,
        var ticksLeft: Int
    )

    private val pending = java.util.Collections.synchronizedMap(
        java.util.WeakHashMap<WorldPlayer, Pending>()
    )

    @Volatile
    var scheduled: Int = 0
        private set

    @Volatile
    var scheduleRefused: Int = 0
        private set

    @Volatile
    var droppedOnDisconnect: Int = 0
        private set

    @Volatile
    var lastWorldTick: Long = -1L
        private set

    fun pendingTicks(player: WorldPlayer): Int? = pending[player]?.ticksLeft

    fun pendingDestination(player: WorldPlayer): TileLocation? = pending[player]?.destination

    fun pendingCount(): Int = synchronized(pending) { pending.size }

    internal fun resetPending() {
        synchronized(pending) { pending.clear() }
        scheduled = 0; scheduleRefused = 0; droppedOnDisconnect = 0; lastWorldTick = -1L
    }

    fun schedule(
        player: WorldPlayer,
        destination: TileLocation,
        delayTicks: Int,
        reason: String
    ): Boolean {
        if (!enabled) { refused++; return false }
        if (destination.plane !in 0..3) {
            refused++
            logger.warn { "teleport: refusing to schedule $reason for ${player.name} to $destination - plane is not 0..3" }
            return false
        }
        if (delayTicks <= 0) return teleport(player, destination, reason)
        val already = synchronized(pending) { pending[player] }
        if (already != null && already.destination != destination) {
            scheduleRefused++
            logger.info {
                "teleport: refused '$reason' for ${player.name}; '${already.reason}' lands in " +
                    "${already.ticksLeft} tick(s)"
            }
            return false
        }
        synchronized(pending) { pending[player] = Pending(destination, reason, delayTicks) }
        scheduled++
        logger.info {
            "teleport: ${player.name} scheduled '$reason' -> (${destination.x},${destination.y}," +
                "plane ${destination.plane}) in $delayTicks tick(s)"
        }
        return true
    }

    fun tick(): Int {
        val world = runCatching { com.opennxt.OpenNXT.world }.getOrNull()
        val now = world?.currentTick ?: -1L
        if (now >= 0) {
            if (now == lastWorldTick) return 0
            lastWorldTick = now
        }
        if (!enabled) return 0
        val ready = ArrayList<Pair<WorldPlayer, Pending>>()
        synchronized(pending) {
            val it = pending.entries.iterator()
            while (it.hasNext()) {
                val (player, p) = it.next()
                if (!player.client.channel.isActive) {
                    droppedOnDisconnect++
                    it.remove()
                    continue
                }
                if (--p.ticksLeft > 0) continue
                ready.add(player to p)
                it.remove()
            }
        }
        var done = 0
        for ((player, p) in ready) if (teleport(player, p.destination, p.reason)) done++
        return done
    }

    const val RING_ITEM = 39812

    const val RING_WORN_SLOT = 12

    const val RING_ROW = 2

    const val RING_WORN_MASK = 10749950

    const val RING_PARAM = 529

    val RING_DESTINATION = TileLocation(3163, 3464, 0)

    val RING_TICKS: Int
        get() = System.getProperty("opennxt.teleport.ring.ticks")?.toIntOrNull()?.takeIf { it in 0..200 } ?: 5

    val ringEnabled: Boolean get() = System.getProperty("opennxt.experiment.teleports.ring") != "false"

    val WORN_OPTION_PARAMS = listOf(528, 529, 530, 531, 1211)

    fun wornOptionsOf(itemId: Int): Map<Int, String> {
        if (!RsDatabase.available) return emptyMap()
        val blob = RsDatabase.queryAll(
            "SELECT value FROM items_attr WHERE id = ? AND field = 'extra'", itemId
        ) { it.getString("value") }.firstOrNull() ?: return emptyMap()
        val out = LinkedHashMap<Int, String>()
        Regex("\\{\"prop\":(\\d+),\"intvalue\":(?:null|-?\\d+),\"stringvalue\":(?:null|\"([^\"]*)\")\\}")
            .findAll(blob).forEach { m ->
                val s = m.groupValues[2]
                if (s.isNotEmpty()) out[m.groupValues[1].toInt()] = s
            }
        return out
    }

    @Volatile
    var ringAccepted: Int = 0
        private set

    @Volatile
    var ringRefusedRow: Int = 0
        private set

    internal fun resetRingCounters() { ringAccepted = 0; ringRefusedRow = 0 }

    fun handleWornButton(player: WorldPlayer, packet: IfButtonN): Boolean {
        if (!enabled || !ringEnabled) return false
        if (packet.interfaceId != ItemOps.WORN_IFACE || packet.component != ItemOps.WORN_ITEM_LAYER) return false
        if (packet.mid != RING_ITEM) return false

        val row = ItemOps.rowForOp(player, ItemOps.WORN_IFACE, ItemOps.WORN_ITEM_LAYER, packet.buttonOp)
        val options = wornOptionsOf(RING_ITEM)
        val param = if (row != null && row in 1..WORN_OPTION_PARAMS.size) WORN_OPTION_PARAMS[row - 1] else null
        if (row != RING_ROW) {
            ringRefusedRow++
            logger.info {
                "teleport: ${player.name} used ${SqliteItemCodec.load(RING_ITEM)?.name ?: RING_ITEM} " +
                    "op ${packet.buttonOp}, which resolves to row index $row" +
                    (if (param != null) " = param $param '${options[param] ?: "?"}'" else "") +
                    "; not implemented"
            }
            player.client.write(
                com.opennxt.net.game.serverprot.MessageGame(
                    0, "Nothing interesting happens."
                )
            )
            return true
        }

        val held = runCatching { player.worn[packet.arg2] }.getOrNull()
        if (held == null || held.id != RING_ITEM) {
            logger.info {
                "teleport: refused ring teleport for ${player.name}; worn slot ${packet.arg2} " +
                    "holds ${held?.id ?: "nothing"}, not $RING_ITEM"
            }
            return true
        }
        val armed = schedule(
            player, RING_DESTINATION, RING_TICKS,
            "${SqliteItemCodec.load(RING_ITEM)?.name ?: "ring"} '${options[RING_PARAM] ?: "row $RING_ROW"}'"
        )
        if (armed) ringAccepted++
        return true
    }

    fun installActionSlotCanceller() {
        if (System.getProperty("opennxt.teleport.cancelactions") == "false") {
            actionCanceller = null
            return
        }
        actionCanceller = { player ->
            val content = player.contentPlayer
            val owner = ActionSlot.holderOf(content)
            if (owner != null) {
                runCatching { owner.cancelSlot(content, "teleported away") }.onFailure { t ->
                    logger.error(t) {
                        "teleport: cancelling ${owner.actionName} failed for ${player.name}; teleporting anyway"
                    }
                }
                ActionSlot.clearFor(content)
                logger.info { "teleport: ${player.name}'s ${owner.actionName} was stopped by the teleport" }
            }
            true
        }
    }

    init {
        installActionSlotCanceller()
    }

    fun describe(): String =
        "teleports: delays lodestone ${Lodestones.TELEPORT_TICKS} tick(s), ring $RING_TICKS tick(s); " +
            "cancels actions ${if (actionCanceller == null) "no" else "yes"}; ring item $RING_ITEM " +
            "'${wornOptionsOf(RING_ITEM)[RING_PARAM] ?: "?"}' -> (${RING_DESTINATION.x},${RING_DESTINATION.y})"
}

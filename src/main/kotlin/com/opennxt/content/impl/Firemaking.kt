package com.opennxt.content.impl

import com.opennxt.api.stat.Stat
import com.opennxt.content.ActionSlot
import com.opennxt.content.ContentPlayer
import com.opennxt.model.entity.rendering.blocks.PlayerFaceDirectionBlock
import com.opennxt.model.items.ItemContainer
import com.opennxt.model.world.LocChanges
import com.opennxt.model.world.TileLocation
import mu.KotlinLogging
import java.util.Collections
import java.util.WeakHashMap

object Firemaking {
    private val logger = KotlinLogging.logger { }

    val enabled: Boolean = System.getProperty("opennxt.experiment.firemaking") != "false"

    const val LIGHT_ACTION = "Light"

    const val FIRE_LOC = 70755

    const val FIRE_SHAPE = 10

    const val FIRE_ROTATION = 1

    const val LOGS_XP_TENTHS = 400

    const val LOGS_ITEM = 1511

    val LIGHT_ANIMATION: IntArray = intArrayOf(25600, 25600, 25600, 25600)

    val STOP_ANIMATION: IntArray = intArrayOf(-1, -1, -1, -1)

    const val CATCH_TICK_ANIMATIONS = 0

    const val CATCH_TAIL_TICKS_SWEPT = 7

    const val CATCH_TAIL_SAMPLES = 7

    const val FIRE_LOC_ANIMATION = 16704

    const val FIRST_ANIMATION_GAP = 3
    const val ANIMATION_GAP = 4

    val CATCH_PERCENT: Int =
        System.getProperty("opennxt.firemaking.catchPercent")?.toIntOrNull()?.coerceIn(1, 100) ?: 10

    val MAX_WAIT_TICKS: Int =
        System.getProperty("opennxt.firemaking.maxWaitTicks")?.toIntOrNull()?.coerceIn(1, 10_000) ?: 100

    val FIRE_TICKS: Int =
        System.getProperty("opennxt.firemaking.fireTicks")?.toIntOrNull()?.coerceIn(1, 100_000) ?: 200

    const val MIN_FIRE_TICKS = 175

    val expireEnabled: Boolean get() = System.getProperty("opennxt.firemaking.expire") != "off"

    const val ATTEMPT_MESSAGE = "You attempt to light the logs."
    const val CATCH_MESSAGE = "The fire catches and the logs begin to burn."

    val STEP_ORDER: List<Pair<Int, Int>> =
        listOf(-1 to 0, 1 to 0, 0 to -1, 0 to 1, -1 to -1, -1 to 1, 1 to -1, 1 to 1)

    data class Requirement(val level: Int, val xpTenths: Int, val levelSource: String, val xpSource: String)

    val DEFAULT_REQUIREMENT = Requirement(1, LOGS_XP_TENTHS, "DEFAULT", "DEFAULT")

    private val requirementMemo = java.util.concurrent.ConcurrentHashMap<String, Requirement>()

    internal fun clearRequirementMemo() = requirementMemo.clear()

    fun requirementFor(itemName: String): Requirement = requirementMemo.computeIfAbsent(itemName) { name ->
        if (name == "Logs") {
            Requirement(1, LOGS_XP_TENTHS, "AUTHORED", "FIXED")
        } else {
            val row = if (SkillXpTable.enabled) SkillXpTable.rows("Firemaking", name).firstOrNull() else null
            if (row == null) DEFAULT_REQUIREMENT
            else Requirement(row.level ?: 1, row.xpTenths, "TABLE ${row.source}", "TABLE ${row.source}")
        }
    }

    @Volatile
    var containerSupplier: (ContentPlayer) -> ItemContainer = { it.inventory }

    @Volatile
    var levelSupplier: (ContentPlayer, Stat) -> Int = { _, _ -> 1 }

    @Volatile
    var xpSink: (ContentPlayer, Stat, Double) -> Unit = { _, _, _ -> }

    @Volatile
    var messageSink: (ContentPlayer, String) -> Unit = { _, _ -> }

    @Volatile
    var animationSink: (ContentPlayer, IntArray) -> Unit = { _, _ -> }

    @Volatile
    var inventoryResend: (ContentPlayer) -> Boolean = { false }

    @Volatile
    var tileSupplier: (ContentPlayer) -> TileLocation = { it.location }

    @Volatile
    var canStep: (TileLocation, Int, Int) -> Boolean = { _, _, _ -> true }

    @Volatile
    var stepSink: (ContentPlayer, TileLocation) -> Boolean = { _, _ -> false }

    @Volatile
    var groundAdd: (ContentPlayer, Int, String, TileLocation) -> Any? = { _, _, _, _ -> null }

    @Volatile
    var groundRemove: (Any) -> Boolean = { false }

    @Volatile
    var faceSink: (ContentPlayer, Int) -> Unit = { _, _ -> }

    data class Pending(
        val player: ContentPlayer,
        val itemId: Int,
        val itemName: String,
        val slot: Int,
        val tile: TileLocation,
        val requirement: Requirement,
        val startTick: Long,
        val catchTick: Long,
        val ground: Any?,
        var nextAnimationTick: Long
    ) {
        val delay: Int get() = (catchTick - startTick).toInt()
    }

    data class Fire(
        val key: LocChanges.Key,
        val tile: TileLocation,
        val litByTick: Long,
        val expiresAtTick: Long
    )

    private val pending = Collections.synchronizedMap(java.util.IdentityHashMap<ContentPlayer, Pending>())
    private val fires = LinkedHashMap<LocChanges.Key, Fire>()

    @Volatile
    private var tickCount: Long = 0

    fun ticks(): Long = tickCount
    fun pendingCount(): Int = pending.size
    fun pendingFor(player: ContentPlayer): Pending? = pending[player]
    fun fireCount(): Int = synchronized(fires) { fires.size }
    fun fireAt(plane: Int, x: Int, z: Int): Fire? =
        synchronized(fires) { fires[LocChanges.Key(plane, x, z, FIRE_SHAPE)] }

    internal object SLOT : ActionSlot.Owner {
        override val actionName = "firemaking"
        override fun cancelSlot(player: ContentPlayer, why: String) { cancelFor(player, why) }
    }

    fun cancelFor(player: ContentPlayer, why: String): Boolean {
        ActionSlot.release(player, SLOT)
        val was = pending.remove(player) ?: return false
        cancelled++
        var restored = false
        var groundLeft = false
        val ground = was.ground
        if (ground != null) {
            val container = runCatching { containerSupplier(player) }.getOrNull()
            val add = if (container == null) null else runCatching { container.add(was.itemId, 1) }.getOrNull()
            if (container != null && add != null && add.added >= 1) {
                if (runCatching { groundRemove(ground) }.getOrDefault(false)) {
                    restored = true
                    runCatching { inventoryResend(player) }
                } else {
                    container.remove(was.itemId, add.added)
                    groundLeft = true
                }
            } else {
                groundLeft = true
            }
        }
        runCatching { animationSink(player, STOP_ANIMATION) }; animationsSent++
        if (restored) cancelsRestored++ else if (groundLeft) cancelsLeftOnGround++
        logger.info {
            "firemaking: ${player.name}'s light cancelled - $why; " + when {
                restored -> "${was.itemName} returned to the backpack"
                groundLeft -> "${was.itemName} left on the ground at (${was.tile.x},${was.tile.y},${was.tile.plane})"
                else -> "no ground item to return"
            }
        }
        return true
    }

    @Volatile private var messagesSent = 0
    @Volatile private var animationsSent = 0
    @Volatile private var attempts = 0
    @Volatile private var catches = 0
    @Volatile private var cancelled = 0
    @Volatile private var stepsTaken = 0
    @Volatile private var stepsRefused = 0
    @Volatile private var expired = 0
    @Volatile private var locChangeAttempts = 0
    @Volatile private var locChangeApplied = 0
    @Volatile private var locChangeFailures = 0
    @Volatile private var locChangeWarned = false

    @Volatile private var escrowLost = 0

    @Volatile private var cancelsRestored = 0
    @Volatile private var cancelsLeftOnGround = 0

    @Volatile private var containedFailures = 0

    @Volatile private var facesSent = 0

    fun messagesSent(): Int = messagesSent
    fun animationsSent(): Int = animationsSent
    fun facesSent(): Int = facesSent
    fun attempts(): Int = attempts
    fun catches(): Int = catches
    fun cancelled(): Int = cancelled
    fun stepsTaken(): Int = stepsTaken
    fun stepsRefused(): Int = stepsRefused
    fun expired(): Int = expired
    fun locChangeAttempts(): Int = locChangeAttempts
    fun locChangeApplied(): Int = locChangeApplied
    fun locChangeFailures(): Int = locChangeFailures
    fun escrowLost(): Int = escrowLost
    fun cancelsRestored(): Int = cancelsRestored
    fun cancelsLeftOnGround(): Int = cancelsLeftOnGround
    fun containedFailures(): Int = containedFailures

    private val awarded: MutableMap<ContentPlayer, Int> = Collections.synchronizedMap(WeakHashMap())

    fun xpAwarded(player: ContentPlayer): Int = synchronized(awarded) { awarded[player] ?: 0 }

    internal fun reset() {
        pending.clear()
        synchronized(fires) { fires.clear() }
        synchronized(awarded) { awarded.clear() }
        tickCount = 0
        messagesSent = 0; animationsSent = 0; attempts = 0; catches = 0; cancelled = 0
        stepsTaken = 0; stepsRefused = 0; expired = 0
        locChangeAttempts = 0; locChangeApplied = 0; locChangeFailures = 0; locChangeWarned = false
        escrowLost = 0; cancelsRestored = 0; cancelsLeftOnGround = 0; containedFailures = 0
        facesSent = 0
        random = java.util.Random(SEED)
    }

    private const val SEED = 0x46495245L

    @Volatile
    var random: java.util.Random = java.util.Random(SEED)

    fun rollCatchDelay(): Int {
        var d = 1
        while (d < MAX_WAIT_TICKS && random.nextInt(100) >= CATCH_PERCENT) d++
        return d
    }

    enum class Outcome {
        LIT,
        NOT_MINE,
        STALE_CLICK,
        ALREADY_LIGHTING,
        TILE_OCCUPIED,
        LEVEL,

        NO_GROUND
    }

    data class Result(val outcome: Outcome, val detail: String, val pending: Pending? = null)

    fun light(player: ContentPlayer, itemId: Int, itemName: String, slot: Int, action: String): Result {
        if (!enabled) return Result(Outcome.NOT_MINE, "firemaking is switched off")
        if (!action.equals(LIGHT_ACTION, ignoreCase = true)) return Result(Outcome.NOT_MINE, "action '$action' is not $LIGHT_ACTION")

        if (pending.containsKey(player)) {
            logger.info { "firemaking: ${player.name} is already lighting a fire" }
            return Result(Outcome.ALREADY_LIGHTING, "a light is already in flight")
        }

        val container = containerSupplier(player)
        val held = container[slot]
        if (held == null || held.id != itemId) {
            logger.info {
                "firemaking: ${player.name} stale click: slot $slot holds ${held?.id ?: "nothing"}, not $itemId"
            }
            return Result(Outcome.STALE_CLICK, "slot $slot holds ${held?.id ?: "nothing"}, not $itemId")
        }

        val tile = tileSupplier(player)
        val pendingHere = synchronized(pending) {
            pending.values.any { it.player !== player && it.tile.x == tile.x && it.tile.y == tile.y && it.tile.plane == tile.plane }
        }
        if (fireAt(tile.plane, tile.x, tile.y) != null || pendingHere) {
            messageSink(player, "You can't light a fire here."); messagesSent++
            logger.info { "firemaking: ${player.name} rejected, tile (${tile.x},${tile.y},${tile.plane}) already has a fire" }
            return Result(Outcome.TILE_OCCUPIED, "a fire is already on this tile")
        }

        val requirement = requirementFor(itemName)
        val level = levelSupplier(player, Stat.FIREMAKING)
        if (level < requirement.level) {
            messageSink(player, "You need a Firemaking level of ${requirement.level} to light this."); messagesSent++
            logger.info {
                "firemaking: ${player.name} rejected, level $level < ${requirement.level} for $itemName"
            }
            return Result(Outcome.LEVEL, "level $level < ${requirement.level}")
        }

        val consumed = held.minus(1)
        if (consumed == null) container.removeSlot(slot) else container[slot] = consumed
        runCatching { inventoryResend(player) }
        val ground = runCatching { groundAdd(player, itemId, itemName, tile) }.getOrNull()
        if (ground == null) {
            val back = container.add(itemId, 1)
            if (back.added < 1) {
                logger.error {
                    "firemaking: lost one $itemName ($itemId) for ${player.name}: ground spawn and restore both failed ($back)"
                }
            }
            runCatching { inventoryResend(player) }
            logger.warn {
                "firemaking: ${player.name} could not place $itemName on the ground at " +
                    "(${tile.x},${tile.y},${tile.plane}); returned to backpack"
            }
            return Result(Outcome.NO_GROUND, "the ground escrow could not be created")
        }
        messageSink(player, ATTEMPT_MESSAGE); messagesSent++

        val delay = rollCatchDelay()
        val catchTick = tickCount + delay
        if (delay >= 2) { runCatching { animationSink(player, LIGHT_ANIMATION) }; animationsSent++ }

        val p = Pending(
            player = player, itemId = itemId, itemName = itemName, slot = slot, tile = tile,
            requirement = requirement, startTick = tickCount, catchTick = catchTick, ground = ground,
            nextAnimationTick = tickCount + FIRST_ANIMATION_GAP
        )
        pending[player] = p
        ActionSlot.claim(player, SLOT)
        attempts++
        if (delay == 1) {
            runCatching { animationSink(player, STOP_ANIMATION) }; animationsSent++
            stepOff(p)
        }
        logger.info {
            "firemaking: ${player.name} lit $itemName ($itemId) from slot $slot at (${tile.x},${tile.y},${tile.plane}); " +
                "catch in $delay tick(s) (${CATCH_PERCENT}%/tick); xp ${requirement.xpTenths / 10.0} " +
                "(${requirement.xpSource}); level ${requirement.level} (${requirement.levelSource})"
        }
        return Result(Outcome.LIT, "catch in $delay tick(s)", p)
    }

    fun tick(): Int {
        tickCount++
        var caught = 0

        if (pending.isNotEmpty()) {
            for (p in ArrayList(pending.values)) {
                try {
                    when {
                        tickCount == p.catchTick -> { if (catchFire(p)) caught++ }
                        tickCount == p.catchTick - 1 -> {
                            runCatching { animationSink(p.player, STOP_ANIMATION) }; animationsSent++
                            stepOff(p)
                        }
                        tickCount < p.catchTick -> {
                            if (tickCount >= p.nextAnimationTick) {
                                runCatching { animationSink(p.player, LIGHT_ANIMATION) }; animationsSent++
                                p.nextAnimationTick = tickCount + ANIMATION_GAP
                            }
                        }
                        else -> {
                            logger.error { "firemaking: ${p.player.name}'s fire passed its catch tick (${p.catchTick} < $tickCount), forcing it" }
                            if (catchFire(p)) caught++
                        }
                    }
                } catch (t: Throwable) {
                    containedFailures++
                    pending.remove(p.player)
                    ActionSlot.release(p.player, SLOT)
                    logger.error(t) {
                        "firemaking: tick failed for ${p.player.name}; action dropped, ${p.itemName} left on the ground at " +
                            "(${p.tile.x},${p.tile.y},${p.tile.plane})"
                    }
                }
            }
        }

        val due = synchronized(fires) {
            fires.values.filter { it.expiresAtTick <= tickCount }.also { list -> list.forEach { fires.remove(it.key) } }
        }
        for (f in due) {
            expired++
            if (expireEnabled) {
                sendLocChange(f.key, FIRE_LOC, -1, "burnt out")
                logger.info {
                    "firemaking: fire at (${f.tile.x},${f.tile.y},${f.tile.plane}) burnt out after $FIRE_TICKS ticks"
                }
            } else {
                logger.info { "firemaking: fire at (${f.tile.x},${f.tile.y}) expired; kept because opennxt.firemaking.expire=off" }
            }
        }
        return caught
    }

    private fun catchFire(p: Pending): Boolean {
        pending.remove(p.player)
        ActionSlot.release(p.player, SLOT)
        val escrow = p.ground
        val stillEscrowed = escrow == null || runCatching { groundRemove(escrow) }.getOrDefault(false)
        if (!stillEscrowed) {
            escrowLost++
            runCatching { animationSink(p.player, STOP_ANIMATION) }; animationsSent++
            logger.warn {
                "firemaking: ${p.player.name}'s ${p.itemName} did not catch at " +
                    "(${p.tile.x},${p.tile.y},${p.tile.plane}); the logs were no longer on the ground"
            }
            return false
        }
        catches++
        messageSink(p.player, CATCH_MESSAGE); messagesSent++

        val key = LocChanges.Key(p.tile.plane, p.tile.x, p.tile.y, FIRE_SHAPE)
        synchronized(fires) {
            fires[key] = Fire(key, p.tile, tickCount, tickCount + FIRE_TICKS)
        }
        sendLocChange(key, FIRE_LOC, FIRE_LOC, "lit")

        faceFire(p)

        val tenths = p.requirement.xpTenths
        val paid = runCatching { xpSink(p.player, Stat.FIREMAKING, tenths / 10.0) }
            .onFailure { logger.warn(it) { "firemaking: failed to award ${tenths / 10.0} Firemaking xp to ${p.player.name}" } }
            .isSuccess
        if (paid) synchronized(awarded) { awarded[p.player] = (awarded[p.player] ?: 0) + tenths }
        logger.info {
            "firemaking: ${p.player.name}'s ${p.itemName} caught after ${p.delay} tick(s) at " +
                "(${p.tile.x},${p.tile.y},${p.tile.plane}); +${tenths / 10.0} Firemaking xp (${p.requirement.xpSource})"
        }
        return true
    }

    private fun faceFire(p: Pending): Int? {
        val from = runCatching { tileSupplier(p.player) }.getOrNull() ?: return null
        if (from.x == p.tile.x && from.y == p.tile.y) return null
        val angle = PlayerFaceDirectionBlock.towards(from.x, from.y, p.tile.x, p.tile.y, 1, 1)
        runCatching { faceSink(p.player, angle) }
        facesSent++
        return angle
    }

    private fun stepOff(p: Pending) {
        val from = tileSupplier(p.player)
        if (from.x != p.tile.x || from.y != p.tile.y || from.plane != p.tile.plane) {
            return
        }
        for ((dx, dz) in STEP_ORDER) {
            if (!runCatching { canStep(from, dx, dz) }.getOrDefault(false)) continue
            val to = TileLocation(from.x + dx, from.y + dz, from.plane)
            if (runCatching { stepSink(p.player, to) }.getOrDefault(false)) {
                stepsTaken++
                logger.info { "firemaking: ${p.player.name} stepped ($dx,$dz) off (${from.x},${from.y}) one tick before the fire" }
                return
            }
        }
        stepsRefused++
        logger.warn {
            "firemaking: ${p.player.name} could not step off (${from.x},${from.y}); all ${STEP_ORDER.size} directions blocked"
        }
    }

    private fun sendLocChange(key: LocChanges.Key, originalId: Int, newId: Int, why: String) {
        locChangeAttempts++
        runCatching {
            LocChanges.change(
                plane = key.plane, x = key.x, y = key.y, shape = key.shape,
                rotation = FIRE_ROTATION, originalId = originalId, newId = newId
            )
        }.onFailure {
            locChangeFailures++
            if (!locChangeWarned) {
                locChangeWarned = true
                logger.warn {
                    "firemaking: could not send LOC_ADD_CHANGE ($why) for fire at $key: " +
                        "${it::class.simpleName}: ${it.message} (logged once)"
                }
            }
        }.onSuccess { change -> if (change != null) locChangeApplied++ }
    }

    fun lightableItemCount(): Int =
        com.opennxt.resources.sqlite.RsDatabase.queryAll(
            "SELECT COUNT(*) FROM items WHERE LOWER(widget_actions_1) = 'light'"
        ) { it.getInt(1) }.firstOrNull() ?: 0

    fun lightableItemIds(): List<Int> =
        com.opennxt.resources.sqlite.RsDatabase.queryAll(
            "SELECT id FROM items WHERE LOWER(widget_actions_1) = 'light' ORDER BY id"
        ) { it.getInt(1) }
}

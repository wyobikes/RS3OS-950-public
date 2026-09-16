package com.opennxt.content.impl

import com.opennxt.api.stat.Stat
import com.opennxt.content.ActionSlot
import com.opennxt.content.ContentPlayer
import com.opennxt.content.ContentRegistry
import com.opennxt.content.NpcContext
import com.opennxt.model.items.ItemContainer
import com.opennxt.model.world.TileLocation
import com.opennxt.resources.sqlite.RsDatabase
import mu.KotlinLogging
import java.util.Collections
import java.util.WeakHashMap

object Fishing {
    private val logger = KotlinLogging.logger { }

    val enabled: Boolean = System.getProperty("opennxt.experiment.fishing") != "false"

    data class Method(
        val key: String,
        val npcId: Int,
        val action: String,
        val toolItemId: Int,
        val toolOnBelt: Boolean,
        val itemId: Int,
        val itemName: String,
        val animation: Int,
        val startMessage: String,
        val yieldMessage: String,
        val firstCycleTicks: Int,
        val provenance: String
    )

    val CRAYFISH = Method(
        key = "crayfish",
        npcId = 14907,
        action = "Cage",
        toolItemId = 13431,
        toolOnBelt = true,
        itemId = 13435,
        itemName = "Raw crayfish",
        animation = 24931,
        startMessage = "You attempt to catch a crayfish.",
        yieldMessage = "You catch a crayfish.",
        firstCycleTicks = 3,
        provenance = "fixed crayfish rates (success chance 6/50 per cycle)"
    )

    val METHODS: List<Method> = listOf(CRAYFISH)

    fun methodOf(npcId: Int, action: String): Method? =
        METHODS.firstOrNull { it.npcId == npcId && it.action == action }

    val SPOT_FLAG_TILES: List<TileLocation> = listOf(
        TileLocation(2899, 3467, 0),
        TileLocation(2900, 3469, 0),
        TileLocation(2899, 3467, 0)
    )

    val CYCLE_TICKS: Int = System.getProperty("opennxt.fishing.cycleTicks")?.toIntOrNull() ?: 4

    val ARRIVE_RANGE: Int = System.getProperty("opennxt.fishing.arriveRange")?.toIntOrNull() ?: 2

    val ARRIVE_TIMEOUT: Int = System.getProperty("opennxt.fishing.arriveTimeout")?.toIntOrNull() ?: 20

    fun animationFor(method: Method): IntArray =
        intArrayOf(method.animation, method.animation, method.animation, method.animation)

    val FIXED_XP_TENTHS: Map<String, Int> = mapOf("Raw crayfish" to 100)

    val DEFAULT_XP_TENTHS: Int = System.getProperty("opennxt.fishing.defaultXpTenths")?.toIntOrNull() ?: 100
    val DEFAULT_LEVEL: Int = System.getProperty("opennxt.fishing.defaultLevel")?.toIntOrNull() ?: 1

    data class Requirement(val level: Int, val xpTenths: Int, val levelSource: String, val xpSource: String)

    fun requirementFor(method: Method): Requirement {
        val refEntry = if (SkillXpTable.enabled) SkillXpTable.rows("Fishing", method.itemName).firstOrNull() else null
        val fixedValue = FIXED_XP_TENTHS[method.itemName]
        val (level, levelSource) = when {
            refEntry?.level != null -> refEntry.level to "TABLE"
            else -> DEFAULT_LEVEL to "AUTHORED-DEFAULT"
        }
        val (xp, xpSource) = when {
            fixedValue != null -> fixedValue to "FIXED"
            refEntry != null -> refEntry.xpTenths to "TABLE"
            else -> DEFAULT_XP_TENTHS to "DEFAULT"
        }
        return Requirement(level, xp, levelSource, xpSource)
    }

    data class SuccessContext(val method: Method, val itemName: String, val level: Int, val toolId: Int)

    val CHANCE_OVERRIDES: Map<String, Double> = mapOf("Raw crayfish" to 6.0 / 50.0)

    val DEFAULT_SUCCESS_CHANCE: (SuccessContext) -> Double = { c ->
        when (System.getProperty("opennxt.fishing.chance")) {
            "off" -> 1.0
            "table" -> SkillXpTable.fishingChance(c.itemName, c.level) ?: 1.0
            else -> CHANCE_OVERRIDES[c.itemName] ?: SkillXpTable.fishingChance(c.itemName, c.level) ?: 1.0
        }
    }
    var successChance: (SuccessContext) -> Double = DEFAULT_SUCCESS_CHANCE

    var random: java.util.Random = java.util.Random(0x46495348L)

    @Volatile
    var containerSupplier: (ContentPlayer) -> ItemContainer = { it.inventory }

    @Volatile
    var wornSupplier: (ContentPlayer) -> ItemContainer? = { null }

    val DEFAULT_TOOLBELT: Boolean = System.getProperty("opennxt.fishing.toolbelt") != "off"

    @Volatile
    var toolbelt: Boolean = DEFAULT_TOOLBELT

    @Volatile
    var levelSupplier: (ContentPlayer, Stat) -> Int = { _, _ -> 1 }

    @Volatile
    var xpSink: (ContentPlayer, Stat, Double) -> Unit = { _, _, _ -> }

    @Volatile
    var inventoryResend: (ContentPlayer) -> Boolean = { false }

    var animationSink: (ContentPlayer, IntArray) -> Unit = { _, _ -> }
    var messageSink: (ContentPlayer, String) -> Unit = { _, _ -> }

    @Volatile
    var spotAlive: (Int) -> Boolean = { true }

    @Volatile
    var spotTile: (Int) -> IntArray? = { _ -> null }

    private var animationsSent = 0
    private var messagesSent = 0
    fun animationsSent(): Int = animationsSent
    fun messagesSent(): Int = messagesSent

    enum class Outcome {
        STARTED,

        REPEATING,

        CAUGHT,

        MISSED,

        NO_TOOL,

        LEVEL_TOO_LOW,

        NO_SPACE,

        NO_DATABASE,

        NO_METHOD,

        SPOT_GONE,

        NEVER_ARRIVED
    }

    data class Catch(
        val outcome: Outcome,
        val npcId: Int,
        val npcName: String?,
        val method: Method?,
        val itemId: Int? = null,
        val itemName: String? = null,
        val amount: Int = 0,
        val xpTenths: Int = 0,
        val chance: Double? = null,
        val levelSource: String? = null,
        val xpSource: String? = null,
        val levelRequired: Int = 1,
        val playerLevel: Int = 1,
        val toolId: Int? = null,
        val toolSource: String? = null,
        val inventorySent: Boolean = false,
        val detail: String = ""
    ) {
        val caught: Boolean get() = outcome == Outcome.CAUGHT
        override fun toString() = buildString {
            append("Catch(").append(outcome).append(" npc ").append(npcId).append(" '").append(npcName).append("'")
            if (method != null) append(" via ").append(method.key)
            if (itemId != null) append(" -> ").append(amount).append("x ").append(itemId).append(" '").append(itemName).append("'")
            if (xpTenths > 0) append(" +").append(xpTenths / 10.0).append(" Fishing xp")
            append(" level ").append(playerLevel).append("/").append(levelRequired)
            append(" [level ").append(levelSource ?: "?").append("]")
            if (xpSource != null) append(" [xp ").append(xpSource).append("]")
            if (chance != null) append(" [chance %.3f]".format(chance))
            if (toolSource != null) append(" [tool ").append(toolSource).append("]")
            if (detail.isNotEmpty()) append(" ").append(detail)
            append(")")
        }
    }

    data class Active(
        val ctx: NpcContext,
        val method: Method,
        val spotIndex: Int,
        val clickedAtTick: Long,
        val carriedCadence: Boolean,
        var startTile: TileLocation? = null,
        var due: Long = -1L,
        var cycles: Int = 0
    )

    private val active = Collections.synchronizedMap(java.util.IdentityHashMap<ContentPlayer, Active>())

    private val lastCycle: MutableMap<ContentPlayer, Long> = Collections.synchronizedMap(WeakHashMap<ContentPlayer, Long>())

    fun lastCycleOf(player: ContentPlayer): Long? = lastCycle[player]
    fun activeCount(): Int = active.size
    fun activeFor(player: ContentPlayer): Active? = active[player]

    fun stopFor(player: ContentPlayer, why: String) = stop(player, why)

    internal fun clearActions() {
        active.clear(); lastCycle.clear(); tickCount = 0; containedFailures = 0
    }

    private var cyclesPaid = 0
    private var cyclesMissed = 0
    private var actionsStopped = 0

    @Volatile private var containedFailures = 0

    fun cyclesPaid(): Int = cyclesPaid
    fun cyclesMissed(): Int = cyclesMissed
    fun actionsStopped(): Int = actionsStopped
    fun containedFailures(): Int = containedFailures

    internal object SLOT : ActionSlot.Owner {
        override val actionName = "fishing"
        override fun cancelSlot(player: ContentPlayer, why: String) { stop(player, why) }
    }

    private fun stop(player: ContentPlayer, why: String) {
        ActionSlot.release(player, SLOT)
        if (active.remove(player) != null) {
            actionsStopped++
            logger.info { "fishing: ${player.name}'s action stopped - $why" }
        }
    }

    private fun whereIs(player: ContentPlayer): TileLocation =
        SkillingWiring.ownerOf(player)?.entity?.location?.let { TileLocation(it.x, it.y, it.plane) } ?: player.location

    @Volatile
    var standingStill: (ContentPlayer) -> Boolean = { true }

    @Volatile
    private var tickCount: Long = 0

    fun ticks(): Long = tickCount

    private val awarded: MutableMap<ContentPlayer, Int> = Collections.synchronizedMap(WeakHashMap())

    fun xpAwarded(player: ContentPlayer): Int = synchronized(awarded) { awarded[player] ?: 0 }

    internal fun clearAwards() = synchronized(awarded) { awarded.clear() }

    fun click(ctx: NpcContext): Catch {
        val npcId = ctx.npcId
        val npcName = ctx.definition.name
        if (!RsDatabase.available) return Catch(Outcome.NO_DATABASE, npcId, npcName, null, detail = "no rs3.sqlite")

        val method = methodOf(npcId, ctx.action)
            ?: return Catch(
                Outcome.NO_METHOD, npcId, npcName, null,
                detail = "no Fishing.METHODS row for (npc $npcId, '${ctx.action}')"
            )

        val running = active[ctx.player]
        val wasArrived = running?.startTile != null
        if (running != null && running.spotIndex == ctx.npcIndex && (running.due < 0 || tickCount < running.due)) {
            return Catch(
                Outcome.REPEATING, npcId, npcName, method,
                itemId = method.itemId, itemName = method.itemName,
                detail = if (running.due < 0) "already walking to this spot"
                else "already fishing here; next cycle in ${running.due - tickCount} tick(s)"
            )
        }

        val container = containerSupplier(ctx.player)
        val level = levelSupplier(ctx.player, Stat.FISHING)
        val requirement = requirementFor(method)

        val tool = toolFor(container, wornSupplier(ctx.player), method)
        if (tool == null) {
            return Catch(
                Outcome.NO_TOOL, npcId, npcName, method,
                itemId = method.itemId, itemName = method.itemName,
                levelRequired = requirement.level, playerLevel = level,
                levelSource = requirement.levelSource, xpSource = requirement.xpSource,
                detail = "no '${Skilling.itemNameOf(method.toolItemId) ?: method.toolItemId}' in the backpack or worn" +
                    (if (toolbelt) " (and the tool belt carries none for this method)" else " (tool belt OFF)")
            )
        }
        if (level < requirement.level) {
            return Catch(
                Outcome.LEVEL_TOO_LOW, npcId, npcName, method,
                itemId = method.itemId, itemName = method.itemName,
                levelRequired = requirement.level, playerLevel = level,
                levelSource = requirement.levelSource, xpSource = requirement.xpSource,
                toolId = tool.first, toolSource = tool.second,
                detail = "requirement is ${requirement.levelSource}"
            )
        }
        if (noRoomFor(container, method)) {
            return Catch(
                Outcome.NO_SPACE, npcId, npcName, method,
                itemId = method.itemId, itemName = method.itemName,
                levelRequired = requirement.level, playerLevel = level,
                levelSource = requirement.levelSource, xpSource = requirement.xpSource,
                toolId = tool.first, toolSource = tool.second,
                detail = "container full (${container.usedSlots()}/${container.size})"
            )
        }

        if (running != null) stop(ctx.player, "re-clicked (a different spot, or the cycle was due)")
        active[ctx.player] = Active(ctx, method, ctx.npcIndex, tickCount, wasArrived)
        ActionSlot.claim(ctx.player, SLOT)
        return Catch(
            Outcome.STARTED, npcId, npcName, method,
            itemId = method.itemId, itemName = method.itemName,
            levelRequired = requirement.level, playerLevel = level,
            levelSource = requirement.levelSource, xpSource = requirement.xpSource,
            toolId = tool.first, toolSource = tool.second,
            detail = "walking to the spot; the first cycle is ${method.firstCycleTicks} tick(s) after arrival" +
                (if (wasArrived) ", or the running cadence if that is later" else "")
        )
    }

    fun onFish(ctx: NpcContext): Any = click(ctx)

    fun tick(): Int {
        tickCount++
        if (active.isEmpty()) return 0
        var paid = 0
        for ((player, a) in ArrayList(active.entries).map { it.key to it.value }) {
            var advanced = false
            try {
                if (!spotAlive(a.spotIndex)) {
                    stop(player, "the spot (npc ${a.ctx.npcId}, index ${a.spotIndex}) is no longer there")
                    continue
                }
                val here = whereIs(player)
                if (a.startTile == null) {
                    if (runCatching { standingStill(player) }.getOrDefault(true) && withinRange(here, a)) {
                        a.startTile = here
                        val last = lastCycle[player]
                        val floor = maxOf(tickCount + a.method.firstCycleTicks, (last ?: (tickCount - CYCLE_TICKS)) + CYCLE_TICKS)
                        a.due = if (a.carriedCadence && last != null) nextOnCadence(last, floor) else floor
                        runCatching { messageSink(player, a.method.startMessage) }; messagesSent++
                        if (tickCount < a.due) {
                            runCatching { animationSink(player, animationFor(a.method)) }; animationsSent++
                        }
                    } else if (tickCount - a.clickedAtTick >= ARRIVE_TIMEOUT) {
                        stop(player, "never arrived within $ARRIVE_TIMEOUT tick(s) of the click")
                    }
                    if (a.startTile == null || tickCount < a.due) continue
                } else {
                    val start = a.startTile!!
                    if (here.x != start.x || here.y != start.y || here.plane != start.plane) {
                        stop(player, "moved from (${start.x},${start.y}) to (${here.x},${here.y})")
                        continue
                    }
                    if (tickCount < a.due) continue
                }
                runCatching { animationSink(player, animationFor(a.method)) }; animationsSent++
                val result = cycle(player, a)
                when (result.outcome) {
                    Outcome.CAUGHT -> { paid++; a.due = tickCount + CYCLE_TICKS; a.cycles++; advanced = true }
                    Outcome.MISSED -> { a.due = tickCount + CYCLE_TICKS; a.cycles++; advanced = true }
                    else -> { stop(player, "cycle refused: ${result.outcome} ${result.detail}"); advanced = true }
                }
            } catch (t: Throwable) {
                containedFailures++
                logger.error(t) {
                    "fishing: ${player.name}'s fishing action failed (failures: $containedFailures)"
                }
            } finally {
                if (!advanced && a.startTile != null && a.due >= 0 && tickCount >= a.due) a.due = tickCount + CYCLE_TICKS
            }
        }
        return paid
    }

    private fun withinRange(here: TileLocation, a: Active): Boolean {
        val tile = spotTile(a.spotIndex)
        val x = tile?.getOrNull(0) ?: a.ctx.x
        val z = tile?.getOrNull(1) ?: a.ctx.z
        val plane = tile?.getOrNull(2) ?: a.ctx.plane
        if (here.plane != plane) return false
        return maxOf(Math.abs(here.x - x), Math.abs(here.y - z)) <= ARRIVE_RANGE
    }

    private fun nextOnCadence(last: Long, from: Long): Long {
        var due = last + CYCLE_TICKS
        while (due < from) due += CYCLE_TICKS
        return due
    }

    private fun cycle(player: ContentPlayer, a: Active): Catch {
        val method = a.method
        val npcId = a.ctx.npcId
        val npcName = a.ctx.definition.name
        val container = containerSupplier(player)
        val level = levelSupplier(player, Stat.FISHING)
        val requirement = requirementFor(method)

        val tool = toolFor(container, wornSupplier(player), method)
            ?: return Catch(
                Outcome.NO_TOOL, npcId, npcName, method,
                itemId = method.itemId, itemName = method.itemName,
                levelRequired = requirement.level, playerLevel = level,
                levelSource = requirement.levelSource, xpSource = requirement.xpSource,
                detail = "the tool is gone"
            )
        if (level < requirement.level) {
            return Catch(
                Outcome.LEVEL_TOO_LOW, npcId, npcName, method,
                itemId = method.itemId, itemName = method.itemName,
                levelRequired = requirement.level, playerLevel = level,
                levelSource = requirement.levelSource, xpSource = requirement.xpSource,
                toolId = tool.first, toolSource = tool.second
            )
        }
        if (noRoomFor(container, method)) {
            return Catch(
                Outcome.NO_SPACE, npcId, npcName, method,
                itemId = method.itemId, itemName = method.itemName,
                levelRequired = requirement.level, playerLevel = level,
                levelSource = requirement.levelSource, xpSource = requirement.xpSource,
                toolId = tool.first, toolSource = tool.second,
                detail = "container full (${container.usedSlots()}/${container.size})"
            )
        }

        lastCycle[player] = tickCount
        val chance = successChance(SuccessContext(method, method.itemName, level, tool.first)).coerceIn(0.0, 1.0)
        if (random.nextDouble() >= chance) {
            cyclesMissed++
            return Catch(
                Outcome.MISSED, npcId, npcName, method,
                itemId = method.itemId, itemName = method.itemName, chance = chance,
                levelRequired = requirement.level, playerLevel = level,
                levelSource = requirement.levelSource, xpSource = requirement.xpSource,
                toolId = tool.first, toolSource = tool.second,
                detail = "cycle missed at chance %.3f".format(chance)
            )
        }

        val add = container.add(method.itemId, 1)
        if (add.added == 0) {
            return Catch(
                Outcome.NO_SPACE, npcId, npcName, method,
                itemId = method.itemId, itemName = method.itemName,
                levelRequired = requirement.level, playerLevel = level,
                levelSource = requirement.levelSource, xpSource = requirement.xpSource,
                toolId = tool.first, toolSource = tool.second,
                detail = "container full (${container.usedSlots()}/${container.size})"
            )
        }
        val delivered = runCatching { xpSink(player, Stat.FISHING, requirement.xpTenths / 10.0) }
            .onFailure {
                logger.warn(it) {
                    "fishing: failed to award ${requirement.xpTenths / 10.0} Fishing xp to ${player.name}"
                }
            }
            .isSuccess
        if (delivered) synchronized(awarded) { awarded[player] = (awarded[player] ?: 0) + requirement.xpTenths }
        val sent = runCatching { inventoryResend(player) }.getOrDefault(false)
        runCatching { messageSink(player, method.yieldMessage) }; messagesSent++
        cyclesPaid++
        val result = Catch(
            Outcome.CAUGHT, npcId, npcName, method,
            itemId = method.itemId, itemName = method.itemName, amount = add.added,
            xpTenths = requirement.xpTenths, chance = chance,
            levelRequired = requirement.level, playerLevel = level,
            levelSource = requirement.levelSource, xpSource = requirement.xpSource,
            toolId = tool.first, toolSource = tool.second, inventorySent = sent
        )
        logger.info { "fishing: $result" }
        return result
    }

    fun toolFor(container: ItemContainer, worn: ItemContainer?, method: Method): Pair<Int, String>? {
        val wanted = Skilling.itemNameOf(method.toolItemId) ?: return if (toolbelt && method.toolOnBelt) {
            method.toolItemId to "TOOLBELT (no name in the database to match on)"
        } else null
        for (item in container.items()) if (Skilling.itemNameOf(item.id) == wanted) return item.id to "backpack"
        for (item in worn?.items() ?: emptyList()) if (Skilling.itemNameOf(item.id) == wanted) return item.id to "worn"
        if (toolbelt && method.toolOnBelt) return method.toolItemId to "TOOLBELT"
        return null
    }

    private fun noRoomFor(container: ItemContainer, method: Method): Boolean =
        container.isFull() && !(container.stacks(method.itemId) && container.count(method.itemId) > 0)

    fun install(): Int {
        if (!enabled) {
            logger.warn { "fishing: disabled (-Dopennxt.experiment.fishing=false)" }
            return 0
        }
        var bound = 0
        for (m in METHODS) {
            try {
                ContentRegistry.onNpc(m.npcId, m.action, ::onFish)
                bound++
                val r = requirementFor(m)
                logger.info {
                    "fishing: bound '${m.action}' on npc ${m.npcId} -> ${m.itemName} (${m.itemId}), " +
                        "level ${r.level} [${r.levelSource}], ${r.xpTenths / 10.0} xp [${r.xpSource}], " +
                        "animation ${m.animation}, cycle $CYCLE_TICKS ticks"
                }
            } catch (e: IllegalArgumentException) {
                logger.warn { "fishing: method '${m.key}' did not bind - ${e.message}" }
            }
        }
        logger.info {
            "fishing: $bound of ${METHODS.size} method(s) bound (requires -D$NPC_DISPATCH_SWITCH=true)"
        }
        return bound
    }

    const val NPC_DISPATCH_SWITCH = "opennxt.experiment.npc.dispatch"
}

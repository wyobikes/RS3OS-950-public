package com.opennxt.content.impl

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.opennxt.Constants
import com.opennxt.api.stat.Stat
import com.opennxt.content.ActionLock
import com.opennxt.content.ActionSlot
import com.opennxt.content.ContentPlayer
import com.opennxt.content.ContentRegistry
import com.opennxt.content.NpcContext
import com.opennxt.content.SqliteDefinitions
import com.opennxt.model.drops.DropData
import com.opennxt.model.items.ItemContainer
import com.opennxt.model.world.TileLocation
import com.opennxt.resources.sqlite.RsDatabase
import mu.KotlinLogging
import java.nio.file.Files
import java.util.Collections
import java.util.WeakHashMap

object Thieving {
    private val logger = KotlinLogging.logger { }

    val enabled: Boolean = System.getProperty("opennxt.experiment.thieving") != "false"

    const val ACTION = "Pickpocket"

    val STAT: Stat = Stat.THIEVING

    const val NPC_DISPATCH_SWITCH = "opennxt.experiment.npc.dispatch"

    fun successLine(npcName: String?): String = "You pick the ${noun(npcName)}'s pocket."
    fun failLine(npcName: String?): String = "You fail to pick the ${noun(npcName)}'s pocket."
    const val STUN_LINE = "You've been stunned!"

    const val NPC_LINE = "What do you think you're doing?"

    const val TOO_LATE_LINE = "Too late; they're dead."

    const val AWARE_LINE = "Your pickpocket target becomes aware of your presence."

    fun levelLine(level: Int): String = "You need a Thieving level of $level to pick this pocket."

    const val FULL_LINE = "Your inventory is too full to hold any more loot."

    private fun noun(npcName: String?): String = (npcName ?: "npc").lowercase()

    val ANIMATION: Int? = when (val raw = System.getProperty("opennxt.thieving.anim")) {
        null, "" -> 881
        "off" -> null
        else -> raw.toIntOrNull() ?: 881
    }

    fun animationBlock(): IntArray? = ANIMATION?.let { intArrayOf(it, it, it, it) }

    val seedEnabled: Boolean get() = System.getProperty("opennxt.seed.thieving") != "off"

    data class Drop(val item: String, val minAmount: Int, val maxAmount: Int, val probability: Double, val itemIds: List<Int>) {
        val itemId: Int? get() = DropData.chosenItemId(item, itemIds)
    }

    data class Target(
        val name: String,
        val page: String,
        val revid: Int,
        val level: Int?,
        val xpTenths: Int?,
        val stunTicks: Int?,
        val damageFlat: Int?,
        val damagePercentOfBase: Double?,
        val coin: Int?,
        val drops: List<Drop>,
        val npcIds: List<Int>
    )

    class Seed(val targets: List<Target>, val byNpcId: Map<Int, Target>, val byName: Map<String, Target>, val counts: Map<String, Int>)

    private val seedPath = Constants.DATA_PATH.resolve("seed").resolve("thieving.json")

    val seed: Seed by lazy { loadSeed() }

    @Volatile
    private var unresolvedLootRows = 0

    fun unresolvedLootRows(): Int { seed; return unresolvedLootRows }

    private fun loadSeed(): Seed {
        if (!Files.isRegularFile(seedPath)) {
            logger.warn { "thieving: ${seedPath.fileName} not found; using default pickpocket values" }
            return Seed(emptyList(), emptyMap(), emptyMap(), emptyMap())
        }
        val root = JsonParser().parse(Files.newBufferedReader(seedPath)).asJsonObject
        val targets = ArrayList<Target>()
        for (e in root.getAsJsonArray("targets") ?: com.google.gson.JsonArray()) {
            val o = e as? JsonObject ?: continue
            val drops = ArrayList<Drop>()
            for (d in o.getAsJsonArray("drops") ?: com.google.gson.JsonArray()) {
                val dd = d as? JsonObject ?: continue
                val num = numOrNull(dd, "rarity_num") ?: continue
                val den = numOrNull(dd, "rarity_den") ?: continue
                if (den <= 0.0) continue
                val (lo, hi) = quantityRange(dd.get("quantity")?.takeIf { it.isJsonPrimitive }?.asString ?: "1")
                val ids = (dd.getAsJsonArray("item_ids") ?: com.google.gson.JsonArray()).mapNotNull { runCatching { it.asInt }.getOrNull() }
                if (ids.isEmpty()) {
                    unresolvedLootRows++
                    continue
                }
                drops += Drop(dd.get("item").asString, lo, hi, num / den, ids)
            }
            val ids = (o.getAsJsonArray("npc_ids") ?: com.google.gson.JsonArray()).mapNotNull { runCatching { it.asInt }.getOrNull() }
            targets += Target(
                name = o.get("name").asString,
                page = o.get("page")?.takeIf { it.isJsonPrimitive }?.asString ?: o.get("name").asString,
                revid = o.get("revid")?.takeIf { it.isJsonPrimitive }?.asInt ?: 0,
                level = numOrNull(o, "level")?.toInt(),
                xpTenths = numOrNull(o, "xp")?.let { Math.round(it * 10).toInt() },
                stunTicks = numOrNull(o, "stun_ticks")?.toInt(),
                damageFlat = numOrNull(o, "damage_flat")?.toInt(),
                damagePercentOfBase = numOrNull(o, "damage_percent_of_base"),
                coin = numOrNull(o, "coin")?.toInt(),
                drops = drops,
                npcIds = ids
            )
        }
        val byId = HashMap<Int, Target>()
        val byName = HashMap<String, Target>()
        for (t in targets) {
            for (id in t.npcIds) byId.putIfAbsent(id, t)
            byName.putIfAbsent(t.name, t)
        }
        val counts = LinkedHashMap<String, Int>()
        root.getAsJsonObject("counts")?.entrySet()?.forEach { (k, v) -> runCatching { counts[k] = v.asInt } }
        logger.info {
            "thieving: ${targets.size} pickpocket targets over ${byId.size} npc ids, " +
                "${targets.count { it.drops.isNotEmpty() }} with loot tables"
        }
        return Seed(targets, byId, byName, counts)
    }

    private fun numOrNull(o: JsonObject, key: String): Double? =
        o.get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asDouble

    private val QUANTITY_RE = Regex("""\s*(\d+)(?:\s*[-\u2013]\s*(\d+))?\s*""")

    fun quantityRange(quantity: String): Pair<Int, Int> {
        val m = QUANTITY_RE.matchEntire(quantity) ?: return 1 to 1
        val lo = m.groupValues[1].toIntOrNull() ?: 1
        val hi = m.groupValues[2].toIntOrNull() ?: lo
        return minOf(lo, hi) to maxOf(lo, hi)
    }

    val DEFAULT_TARGET = Target(
        name = "AUTHORED-DEFAULT", page = "", revid = 0, level = 1, xpTenths = 80, stunTicks = 5,
        damageFlat = 10, damagePercentOfBase = 3.0, coin = 3, drops = emptyList(), npcIds = emptyList()
    )

    const val DEFAULT_STUN_TICKS = 5

    data class Requirement(
        val level: Int,
        val xpTenths: Int,
        val stunTicks: Int,
        val damageFlat: Int,
        val damagePercentOfBase: Double,
        val loot: List<Drop>,
        val levelSource: String,
        val xpSource: String,
        val stunSource: String,
        val damageSource: String,
        val lootSource: String
    )

    private val requirementMemo = java.util.concurrent.ConcurrentHashMap<Int, Requirement>()

    internal fun clearRequirementMemo() = requirementMemo.clear()

    fun requirementFor(npcId: Int, npcName: String?): Requirement = requirementMemo.computeIfAbsent(npcId) {
        val target = if (seedEnabled) (seed.byNpcId[npcId] ?: npcName?.let { seed.byName[it] }) else null
        val targetTag = target?.let { "TABLE (${it.page})" }
        val xpRow = if (SkillXpTable.enabled && npcName != null)
            SkillXpTable.rows("Thieving", npcName).firstOrNull { it.category == THIEVING_XP_CATEGORY } else null

        val (level, levelSource) = when {
            target?.level != null -> target.level to targetTag!!
            xpRow?.level != null -> xpRow.level to "TABLE (skill_xp $THIEVING_XP_CATEGORY)"
            else -> DEFAULT_TARGET.level!! to "AUTHORED-DEFAULT"
        }
        val (xp, xpSource) = when {
            target?.xpTenths != null -> target.xpTenths to targetTag!!
            xpRow != null -> xpRow.xpTenths to "TABLE (skill_xp $THIEVING_XP_CATEGORY)"
            else -> DEFAULT_TARGET.xpTenths!! to "AUTHORED-DEFAULT"
        }
        val (stun, stunSource) = when {
            target?.stunTicks != null -> target.stunTicks to targetTag!!
            else -> DEFAULT_STUN_TICKS to "TABLE (general rule)"
        }
        val (flat, pct, damageSource) = when {
            target != null && (target.damageFlat != null || target.damagePercentOfBase != null) ->
                Triple(target.damageFlat ?: 0, target.damagePercentOfBase ?: 0.0, targetTag!!)
            else -> Triple(DEFAULT_TARGET.damageFlat!!, DEFAULT_TARGET.damagePercentOfBase!!, "AUTHORED-DEFAULT")
        }
        val (loot, lootSource) = when {
            target != null && target.drops.isNotEmpty() -> target.drops to "$targetTag drop table x${target.drops.size}"
            target?.coin != null -> listOf(Drop("Coins", target.coin, target.coin, 1.0, listOf(COINS))) to "$targetTag coin field only (rate note unparsed)"
            else -> listOf(Drop("Coins", DEFAULT_TARGET.coin!!, DEFAULT_TARGET.coin!!, 1.0, listOf(COINS))) to "AUTHORED-DEFAULT (3 coins)"
        }
        Requirement(level, xp, stun, flat, pct, loot, levelSource, xpSource, stunSource, damageSource, lootSource)
    }

    const val THIEVING_XP_CATEGORY = "Pickpocket"

    const val COINS = 995

    fun damageFor(r: Requirement, baseLifepoints: Int): Int =
        (r.damageFlat + Math.round(r.damagePercentOfBase / 100.0 * baseLifepoints)).toInt().coerceAtLeast(0)

    fun levelBoost(req: Int): Int = when {
        req <= 30 -> 15
        req <= 50 -> 20
        else -> 25
    }

    fun bufferAttempts(req: Int): Int = when {
        req <= 30 -> 10
        req <= 50 -> 10
        req <= 99 -> 15
        else -> 25
    }

    const val DECAY_PER_ATTEMPT = 0.0025

    fun refChance(level: Int, req: Int, attempt: Int): Double {
        val r = req.coerceAtLeast(1)
        val baseBp = 7500L + 100L * (level + levelBoost(r) - r)
        val buffer = bufferAttempts(r)
        val decayBp = if (attempt <= buffer) 0L else DECAY_BP_PER_ATTEMPT * (attempt - buffer)
        return (baseBp - decayBp).coerceIn(0L, 10000L) / 10000.0
    }

    private const val DECAY_BP_PER_ATTEMPT = 25L

    data class SuccessContext(val player: ContentPlayer, val npcId: Int, val level: Int, val requirement: Requirement, val attempt: Int)

    val DEFAULT_SUCCESS_CHANCE: (SuccessContext) -> Double = { c ->
        if (System.getProperty("opennxt.thieving.chance") == "off") 1.0
        else refChance(c.level, c.requirement.level, c.attempt)
    }

    @Volatile
    var successChance: (SuccessContext) -> Double = DEFAULT_SUCCESS_CHANCE

    var random: java.util.Random = java.util.Random(0x54484946L)

    val ARRIVE_RANGE: Int = System.getProperty("opennxt.thieving.arriveRange")?.toIntOrNull() ?: 2

    val ARRIVE_TIMEOUT: Int = System.getProperty("opennxt.thieving.arriveTimeout")?.toIntOrNull() ?: 20

    val repeatEnabled: Boolean get() = System.getProperty("opennxt.thieving.repeat") != "off"

    const val REPEAT_TICKS = 3

    @Volatile var containerSupplier: (ContentPlayer) -> ItemContainer = { it.inventory }
    @Volatile var levelSupplier: (ContentPlayer, Stat) -> Int = { _, _ -> 1 }
    @Volatile var baseLevelSupplier: (ContentPlayer, Stat) -> Int = { _, _ -> 1 }
    @Volatile var xpSink: (ContentPlayer, Stat, Double) -> Unit = { _, _, _ -> }
    @Volatile var inventoryResend: (ContentPlayer) -> Boolean = { false }
    @Volatile var animationSink: (ContentPlayer, IntArray) -> Unit = { _, _ -> }
    @Volatile var messageSink: (ContentPlayer, String) -> Unit = { _, _ -> }
    @Volatile var npcSaySink: (Int, String) -> Unit = { _, _ -> }
    @Volatile var faceSink: (ContentPlayer, Int) -> Unit = { _, _ -> }
    @Volatile var faceReleaseSink: (ContentPlayer) -> Unit = { _ -> }
    @Volatile var damageSink: (ContentPlayer, Int) -> Boolean = { _, _ -> false }
    @Volatile var holdSink: (ContentPlayer) -> Unit = { _ -> }
    @Volatile var stunIconSink: (ContentPlayer, Boolean) -> Unit = { _, _ -> }
    @Volatile var spotAlive: (Int) -> Boolean = { true }
    @Volatile var spotTile: (Int) -> IntArray? = { _ -> null }
    @Volatile var standingStill: (ContentPlayer) -> Boolean = { true }

    fun resetSeams() {
        containerSupplier = { it.inventory }
        levelSupplier = { _, _ -> 1 }
        baseLevelSupplier = { _, _ -> 1 }
        xpSink = { _, _, _ -> }
        inventoryResend = { false }
        animationSink = { _, _ -> }
        messageSink = { _, _ -> }
        npcSaySink = { _, _ -> }
        faceSink = { _, _ -> }
        faceReleaseSink = { _ -> }
        damageSink = { _, _ -> false }
        holdSink = { _ -> }
        spotAlive = { true }
        spotTile = { _ -> null }
        standingStill = { true }
        successChance = DEFAULT_SUCCESS_CHANCE
    }

    enum class Outcome {
        STARTED,
        REPEATING,
        STUNNED,
        LEVEL_TOO_LOW,
        NO_SPACE,
        NO_DATABASE,
        SUCCESS,
        CAUGHT,
        TARGET_GONE,
        NEVER_ARRIVED
    }

    data class Attempt(
        val outcome: Outcome,
        val npcId: Int,
        val npcName: String?,
        val requirement: Requirement? = null,
        val playerLevel: Int = 1,
        val attempt: Int = 0,
        val chance: Double? = null,
        val itemId: Int? = null,
        val itemName: String? = null,
        val amount: Int = 0,
        val xpTenths: Int = 0,
        val damage: Int = 0,
        val stunTicks: Int = 0,
        val died: Boolean = false,
        val detail: String = ""
    ) {
        override fun toString() = buildString {
            append("Attempt(").append(outcome).append(" npc ").append(npcId).append(" '").append(npcName).append("'")
            if (requirement != null) append(" level ").append(playerLevel).append("/").append(requirement.level).append(" [").append(requirement.levelSource).append("]")
            if (attempt > 0) append(" attempt #").append(attempt)
            if (chance != null) append(" [chance %.4f]".format(chance))
            if (itemId != null) append(" -> ").append(amount).append("x ").append(itemId).append(" '").append(itemName).append("'")
            if (xpTenths > 0) append(" +").append(xpTenths / 10.0).append(" Thieving xp [").append(requirement?.xpSource).append("]")
            if (damage > 0) append(" damage ").append(damage).append(" [").append(requirement?.damageSource).append("]")
            if (stunTicks > 0) append(" stun ").append(stunTicks).append(" tick(s)")
            if (died) append(" DIED")
            if (detail.isNotEmpty()) append(" ").append(detail)
            append(")")
        }
    }

    data class Active(val ctx: NpcContext, val npcIndex: Int, val clickedAtTick: Long, val armedAt: TileLocation? = null)

    private val active = Collections.synchronizedMap(java.util.IdentityHashMap<ContentPlayer, Active>())

    private val stunnedUntil: MutableMap<ContentPlayer, Long> = Collections.synchronizedMap(WeakHashMap())

    private val streak: MutableMap<ContentPlayer, Pair<Int, Int>> = Collections.synchronizedMap(WeakHashMap())

    private val awarded: MutableMap<ContentPlayer, Int> = Collections.synchronizedMap(WeakHashMap())

    private val nextAttemptAt: MutableMap<ContentPlayer, Long> = Collections.synchronizedMap(WeakHashMap())

    @Volatile private var tickCount: Long = 0
    private var successes = 0
    private var caught = 0
    private var actionsStopped = 0
    @Volatile private var containedFailures = 0
    private var messagesSent = 0
    private var animationsSent = 0

    fun ticks(): Long = tickCount
    fun activeCount(): Int = active.size
    fun activeFor(player: ContentPlayer): Active? = active[player]
    fun stunnedUntil(player: ContentPlayer): Long? = stunnedUntil[player]
    fun isStunned(player: ContentPlayer): Boolean = (stunnedUntil[player] ?: -1L) > tickCount
    fun streakOf(player: ContentPlayer): Pair<Int, Int>? = streak[player]
    fun xpAwarded(player: ContentPlayer): Int = synchronized(awarded) { awarded[player] ?: 0 }
    fun successes(): Int = successes
    fun caught(): Int = caught
    fun actionsStopped(): Int = actionsStopped
    fun containedFailures(): Int = containedFailures
    fun messagesSent(): Int = messagesSent
    fun animationsSent(): Int = animationsSent
    fun nextAttemptAtFor(player: ContentPlayer): Long? = nextAttemptAt[player]

    fun stunTicksLeft(player: ContentPlayer): Long = ((stunnedUntil[player] ?: tickCount) - tickCount).coerceAtLeast(0L)

    fun clearStun(player: ContentPlayer, why: String): Boolean {
        val until = stunnedUntil.remove(player) ?: return false
        val wasLive = until > tickCount
        if (wasLive) {
            logger.info { "thieving: ${player.name}'s stun (to tick $until) cleared at tick $tickCount - $why" }
            runCatching { stunIconSink(player, false) }
        }
        return wasLive
    }

    private val STUN_LOCK = object : ActionLock.Source {
        override val stun: Boolean get() = true
        override fun lockReason(player: ContentPlayer): String? =
            if (isStunned(player)) "stunned by a failed pickpocket (${stunTicksLeft(player)} more tick(s))" else null
    }

    init {
        ActionLock.register(STUN_LOCK)
    }

    internal fun clearAll() {
        active.clear(); stunnedUntil.clear(); streak.clear(); nextAttemptAt.clear()
        synchronized(awarded) { awarded.clear() }
        tickCount = 0; successes = 0; caught = 0; actionsStopped = 0; containedFailures = 0
        messagesSent = 0; animationsSent = 0
    }

    internal object SLOT : ActionSlot.Owner {
        override val actionName = "thieving"
        override fun cancelSlot(player: ContentPlayer, why: String) { stop(player, why) }
    }

    private fun stop(player: ContentPlayer, why: String) {
        ActionSlot.release(player, SLOT)
        if (active.remove(player) != null) {
            actionsStopped++
            logger.info { "thieving: ${player.name}'s attempt stopped - $why" }
        }
    }

    fun stopFor(player: ContentPlayer, why: String) = stop(player, why)

    fun cull(player: ContentPlayer, why: String) {
        stop(player, why)
        stunnedUntil.remove(player)
        streak.remove(player)
        nextAttemptAt.remove(player)
    }

    private fun whereIs(player: ContentPlayer): TileLocation =
        SkillingWiring.ownerOf(player)?.entity?.location?.let { TileLocation(it.x, it.y, it.plane) } ?: player.location

    private fun say(player: ContentPlayer, line: String) {
        runCatching { messageSink(player, line) }; messagesSent++
    }

    fun click(ctx: NpcContext): Attempt {
        val npcId = ctx.npcId
        val npcName = ctx.definition.name
        if (!RsDatabase.available) return Attempt(Outcome.NO_DATABASE, npcId, npcName, detail = "no rs3.sqlite")
        val player = ctx.player

        if (isStunned(player)) {
            val left = (stunnedUntil[player] ?: tickCount) - tickCount
            return Attempt(Outcome.STUNNED, npcId, npcName, detail = "stunned for $left more tick(s)")
        }
        val running = active[player]
        if (running != null && running.npcIndex == ctx.npcIndex && running.ctx.npcId == npcId) {
            if (running.armedAt != null) {
                active[player] = running.copy(clickedAtTick = tickCount, armedAt = null)
                return Attempt(Outcome.REPEATING, npcId, npcName,
                    detail = "already pickpocketing; next attempt at tick ${nextAttemptAt[player] ?: tickCount}")
            }
            return Attempt(Outcome.REPEATING, npcId, npcName, detail = "already walking to this npc")
        }

        val requirement = requirementFor(npcId, npcName)
        val level = levelSupplier(player, STAT)
        if (level < requirement.level) {
            say(player, levelLine(requirement.level))
            return Attempt(Outcome.LEVEL_TOO_LOW, npcId, npcName, requirement, level, detail = "requirement is ${requirement.levelSource}")
        }
        val container = containerSupplier(player)
        if (container.isFull()) {
            say(player, FULL_LINE)
            return Attempt(Outcome.NO_SPACE, npcId, npcName, requirement, level, detail = "container full (${container.usedSlots()}/${container.size})")
        }

        if (running != null) stop(player, "re-clicked a different npc")
        active[player] = Active(ctx, ctx.npcIndex, tickCount)
        ActionSlot.claim(player, SLOT)
        return Attempt(
            Outcome.STARTED, npcId, npcName, requirement, level,
            detail = "walking to the npc"
        )
    }

    fun onPickpocket(ctx: NpcContext): Any = click(ctx)

    fun tick(): Int {
        tickCount++
        if (stunnedUntil.isNotEmpty()) {
            val snapshot = synchronized(stunnedUntil) { stunnedUntil.entries.map { it.key to it.value } }
            for ((p, until) in snapshot) {
                if (until > tickCount) runCatching { holdSink(p) } else { stunnedUntil.remove(p); runCatching { stunIconSink(p, false) } }
            }
        }
        if (active.isEmpty()) return 0
        var resolved = 0
        for ((player, a) in ArrayList(active.entries).map { it.key to it.value }) {
            if (active[player] !== a) continue
            try {
                if (!spotAlive(a.npcIndex)) {
                    say(player, TOO_LATE_LINE)
                    runCatching { faceReleaseSink(player) }
                    stop(player, "the npc (${a.ctx.npcId}, index ${a.npcIndex}) is no longer there")
                    continue
                }
                val here = whereIs(player)
                val still = runCatching { standingStill(player) }.getOrDefault(true)
                val armedAt = a.armedAt
                if (armedAt != null) {
                    val why = when {
                        !still || here.x != armedAt.x || here.y != armedAt.y || here.plane != armedAt.plane ->
                            "the player moved (armed at ${armedAt.x},${armedAt.y}; now ${here.x},${here.y}" +
                                (if (still) ")" else ", walk queued)")
                        !withinRange(here, a) -> "the npc (index ${a.npcIndex}) is out of reach"
                        containerSupplier(player).isFull() -> { say(player, FULL_LINE); "the backpack is full" }
                        else -> null
                    }
                    if (why != null) {
                        stop(player, "repeat ended - $why")
                        continue
                    }
                    if (tickCount < (nextAttemptAt[player] ?: 0L)) continue
                    attempt(player, a, here)
                    resolved++
                } else if (still && withinRange(here, a)) {
                    if (tickCount < (nextAttemptAt[player] ?: 0L)) continue
                    attempt(player, a, here)
                    resolved++
                } else if (tickCount - a.clickedAtTick >= ARRIVE_TIMEOUT) {
                    stop(player, "never arrived within $ARRIVE_TIMEOUT tick(s) of the click")
                }
            } catch (t: Throwable) {
                containedFailures++
                stop(player, "threw: ${t.javaClass.simpleName}")
                logger.error(t) { "thieving: ${player.name}'s attempt threw" }
            }
        }
        return resolved
    }

    private fun attempt(player: ContentPlayer, a: Active, here: TileLocation) {
        val result = resolve(player, a)
        if (!repeatEnabled) {
            nextAttemptAt.remove(player)
            stop(player, "resolved (one attempt per click)")
            return
        }
        nextAttemptAt[player] = tickCount + REPEAT_TICKS
        if (active[player] !== a) return
        if (result.outcome == Outcome.SUCCESS) {
            if (a.armedAt == null) logger.info {
                "thieving: ${player.name} repeating on npc ${a.ctx.npcId} (index ${a.npcIndex}) every $REPEAT_TICKS tick(s)"
            }
            active[player] = a.copy(armedAt = TileLocation(here.x, here.y, here.plane))
        } else {
            stop(player, "${result.outcome} at the attempt - the repeat ends")
        }
    }

    private fun withinRange(here: TileLocation, a: Active): Boolean {
        val tile = spotTile(a.npcIndex)
        val x = tile?.getOrNull(0) ?: a.ctx.x
        val z = tile?.getOrNull(1) ?: a.ctx.z
        val plane = tile?.getOrNull(2) ?: a.ctx.plane
        if (here.plane != plane) return false
        return maxOf(Math.abs(here.x - x), Math.abs(here.y - z)) <= ARRIVE_RANGE
    }

    private val lastAttempt: MutableMap<ContentPlayer, Attempt> = Collections.synchronizedMap(WeakHashMap())

    fun lastAttemptOf(player: ContentPlayer): Attempt? = lastAttempt[player]

    private fun resolve(player: ContentPlayer, a: Active): Attempt {
        val npcId = a.ctx.npcId
        val npcName = a.ctx.definition.name
        val requirement = requirementFor(npcId, npcName)
        val level = levelSupplier(player, STAT)
        val container = containerSupplier(player)

        if (level < requirement.level) {
            say(player, levelLine(requirement.level))
            return Attempt(Outcome.LEVEL_TOO_LOW, npcId, npcName, requirement, level).also { lastAttempt[player] = it }
        }
        if (container.isFull()) {
            say(player, FULL_LINE)
            return Attempt(Outcome.NO_SPACE, npcId, npcName, requirement, level).also { lastAttempt[player] = it }
        }

        val prev = streak[player]
        val attempt = if (prev != null && prev.first == npcId) prev.second + 1 else 1
        streak[player] = npcId to attempt

        runCatching { faceSink(player, a.npcIndex) }
        animationBlock()?.let { runCatching { animationSink(player, it) }; animationsSent++ }

        val chance = successChance(SuccessContext(player, npcId, level, requirement, attempt)).coerceIn(0.0, 1.0)
        if (chance < 1.0 && attempt > 1 && refChance(level, requirement.level, attempt - 1) >= 1.0) say(player, AWARE_LINE)

        if (random.nextDouble() >= chance) {
            caught++
            streak.remove(player)
            say(player, failLine(npcName))
            runCatching { npcSaySink(a.npcIndex, NPC_LINE) }
            val base = com.opennxt.model.combat.Lifepoints.forConstitutionLevel(baseLevelSupplier(player, Stat.CONSTITUTION))
            val damage = damageFor(requirement, base)
            val died = runCatching { damageSink(player, damage) }.getOrDefault(false)
            if (died) {
                stunnedUntil.remove(player)
            } else {
                stunnedUntil[player] = tickCount + requirement.stunTicks
                runCatching { stunIconSink(player, true) }
                say(player, STUN_LINE)
                runCatching { holdSink(player) }
            }
            val result = Attempt(
                Outcome.CAUGHT, npcId, npcName, requirement, level, attempt, chance,
                damage = damage, stunTicks = if (died) 0 else requirement.stunTicks, died = died,
                detail = "[stun ${requirement.stunSource}]"
            )
            lastAttempt[player] = result
            logger.info { "thieving: $result" }
            return result
        }

        val drop = rollLoot(requirement.loot)
        var itemId: Int? = null
        var itemName: String? = null
        var amount = 0
        if (drop != null) {
            val id = drop.itemId
            if (id != null) {
                val qty = if (drop.maxAmount > drop.minAmount) drop.minAmount + random.nextInt(drop.maxAmount - drop.minAmount + 1) else drop.minAmount
                val add = container.add(id, qty)
                if (add.added > 0) { itemId = id; itemName = drop.item; amount = add.added }
            } else {
                logger.warn { "thieving: loot row '${drop.item}' for npc $npcId has no item id; nothing given" }
            }
        }
        say(player, successLine(npcName))
        val delivered = runCatching { xpSink(player, STAT, requirement.xpTenths / 10.0) }
            .onFailure { logger.warn(it) { "thieving: failed to award ${requirement.xpTenths / 10.0} xp to ${player.name}" } }
            .isSuccess
        if (delivered) synchronized(awarded) { awarded[player] = (awarded[player] ?: 0) + requirement.xpTenths }
        if (amount > 0) runCatching { inventoryResend(player) }
        successes++
        val result = Attempt(
            Outcome.SUCCESS, npcId, npcName, requirement, level, attempt, chance,
            itemId = itemId, itemName = itemName, amount = amount, xpTenths = requirement.xpTenths,
            detail = "[loot ${requirement.lootSource}]"
        )
        lastAttempt[player] = result
        logger.info { "thieving: $result" }
        return result
    }

    fun rollLoot(loot: List<Drop>): Drop? {
        if (loot.isEmpty()) return null
        val total = loot.sumOf { it.probability }
        val scale = if (total > 1.0) 1.0 / total else 1.0
        var r = random.nextDouble()
        for (d in loot) {
            r -= d.probability * scale
            if (r < 0) return d
        }
        return null
    }

    fun population(): Set<Int> = SqliteDefinitions.npcsDeclaring(ACTION)

    fun install(): Int {
        if (!enabled) {
            logger.warn { "thieving is disabled (-Dopennxt.experiment.thieving=false)" }
            return 0
        }
        val n = try {
            ContentRegistry.onNpcAction(ACTION, ::onPickpocket)
        } catch (e: IllegalArgumentException) {
            logger.warn { "thieving: '$ACTION' did not bind - ${e.message}" }
            return 0
        }
        val farmer = requirementFor(7, SqliteDefinitions.npc(7)?.name)
        logger.info {
            "thieving: bound '$ACTION' on $n npc ids, ${seed.byNpcId.size} seeded " +
                "(farmer: level ${farmer.level}, ${farmer.xpTenths / 10.0} xp); needs -D$NPC_DISPATCH_SWITCH=true"
        }
        return n
    }
}

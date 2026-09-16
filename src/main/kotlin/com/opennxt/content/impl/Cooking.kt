package com.opennxt.content.impl

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.opennxt.Constants
import com.opennxt.api.stat.Stat
import com.opennxt.content.ActionSlot
import com.opennxt.content.ContentPlayer
import com.opennxt.model.entity.rendering.blocks.PlayerFaceDirectionBlock
import com.opennxt.model.items.ItemContainer
import com.opennxt.model.map.LocInteraction
import com.opennxt.model.world.TileLocation
import com.opennxt.resources.sqlite.RsDatabase
import mu.KotlinLogging
import java.nio.file.Files
import java.util.Collections
import java.util.WeakHashMap

object Cooking {
    private val logger = KotlinLogging.logger { }

    val enabled: Boolean = System.getProperty("opennxt.experiment.cooking") != "false"

    val FIRE_LOCS: List<Int> =
        System.getProperty("opennxt.cooking.fireLocs")
            ?.split(',')?.mapNotNull { it.trim().toIntOrNull() }?.takeIf { it.isNotEmpty() }
            ?: (70755..70771).toList()

    const val COOKING_FIRE_LOC = 70755

    const val USE_ACTION = "Use"

    val CYCLE_TICKS: Int = System.getProperty("opennxt.cooking.cycleTicks")?.toIntOrNull() ?: 4

    val FIRST_CYCLE_TICKS: Int = System.getProperty("opennxt.cooking.firstCycle")?.toIntOrNull() ?: 1

    val ANIMATION: IntArray = intArrayOf(897, 897, 897, 897)

    const val ANIMATION_LEAD_TICKS = 1

    val FIXED_XP_TENTHS: Map<Int, Int> = mapOf(13435 to 330, 3226 to 330)

    val FIRE_XP_BONUS_PERCENT: Int =
        System.getProperty("opennxt.cooking.fireXpPercent")?.toIntOrNull() ?: 110

    val BURNT_OVERRIDES: Map<Int, Int> = mapOf(3226 to 2146)

    val derivedBurnt: Boolean = System.getProperty("opennxt.cooking.derivedBurnt") != "off"

    fun successMessage(category: String?, rawName: String): String =
        if (category == "Fish") "You successfully cook some ${noun(rawName)}."
        else "You cook the ${noun(rawName)}."

    fun burnMessage(rawName: String): String = "You accidentally burn the ${noun(rawName)}."

    internal fun noun(rawName: String): String = rawName.removePrefix("Raw ").lowercase()

    data class Recipe(
        val rawId: Int,
        val rawName: String,
        val cookedId: Int,
        val cookedName: String,
        val burntId: Int,
        val burntName: String?,
        val level: Int,
        val xpTenths: Int,
        val stopBurnLevel: Int?,
        val category: String?,
        val levelSource: String,
        val xpSource: String,
        val burntSource: String
    )

    private fun cacheRecipes(): Map<Int, Triple<Int, Int?, Int?>> {
        if (!RsDatabase.available) return emptyMap()
        val out = HashMap<Int, Triple<Int, Int?, Int?>>()
        RsDatabase.queryAll(
            "SELECT id, value FROM items_attr WHERE field = 'extra' AND value LIKE '%\"prop\":2640,\"intvalue\":16%'"
        ) { rs -> rs.getInt("id") to rs.getString("value") }.forEach { (id, json) ->
            var raw: Int? = null; var level: Int? = null; var xp: Int? = null
            runCatching {
                for (el in JsonParser().parse(json).asJsonArray) {
                    val o = el.asJsonObject
                    if (o.get("intvalue").isJsonNull) continue
                    when (o.get("prop").asInt) {
                        2655 -> raw = o.get("intvalue").asInt
                        2645 -> level = o.get("intvalue").asInt
                        2697 -> xp = o.get("intvalue").asInt
                    }
                }
            }
            val r = raw ?: return@forEach
            out[id] = Triple(r, level, xp)
        }
        return out
    }

    class CookingSeed(val byRaw: Map<Int, JsonObject>, val foods: Int, val withStopFire: Int)

    private val seedPath = Constants.DATA_PATH.resolve("seed").resolve("cooking.json")

    val refEntry: CookingSeed by lazy { loadRef() }

    private fun loadRef(): CookingSeed {
        if (!Files.exists(seedPath)) {
            logger.warn { "cooking: $seedPath not found; burn levels unavailable" }
            return CookingSeed(emptyMap(), 0, 0)
        }
        val root = runCatching {
            JsonParser().parse(Files.newBufferedReader(seedPath)).asJsonObject
        }.getOrElse {
            logger.error(it) { "cooking: could not read $seedPath; burn levels unavailable" }
            return CookingSeed(emptyMap(), 0, 0)
        }
        val byRaw = LinkedHashMap<Int, JsonObject>()
        var withStop = 0
        var n = 0
        for (el in root.getAsJsonArray("foods")) {
            val o = el.asJsonObject
            n++
            if (o.get("raw_id").isJsonNull) continue
            val rawId = o.get("raw_id").asInt
            val level = if (o.get("level").isJsonNull) Int.MAX_VALUE else o.get("level").asInt
            val prev = byRaw[rawId]
            val prevLevel = prev?.get("level")?.takeIf { !it.isJsonNull }?.asInt ?: Int.MAX_VALUE
            if (prev == null || level < prevLevel) byRaw[rawId] = o
        }
        for (o in byRaw.values) if (!o.get("stop_burn_fire").isJsonNull) withStop++
        logger.info { "cooking: seed holds $n rows, ${byRaw.size} raw foods, $withStop with a fire stop level" }
        return CookingSeed(byRaw, n, withStop)
    }

    private fun intOrNull(o: JsonObject?, key: String): Int? =
        o?.get(key)?.takeIf { !it.isJsonNull }?.asInt

    internal fun burntItemOf(rawId: Int, refRow: JsonObject?): Pair<Int?, String> {
        BURNT_OVERRIDES[rawId]?.let { return it to "FIXED" }
        if (!derivedBurnt) return null to "NONE (derivedBurnt=off)"
        val cands = refRow?.getAsJsonArray("burnt_candidates") ?: return null to "NONE"
        if (cands.size() == 0) return null to "NONE"
        return cands[0].asJsonObject.get("id").asInt to "DERIVED"
    }

    val recipes: Map<Int, Recipe> by lazy { buildRecipes() }

    private fun buildRecipes(): Map<Int, Recipe> {
        val out = LinkedHashMap<Int, Recipe>()
        val cache = cacheRecipes()
        val seed = refEntry.byRaw
        val byRaw = LinkedHashMap<Int, Pair<Int, Triple<Int, Int?, Int?>>>()
        for ((cookedId, t) in cache) {
            val prev = byRaw[t.first]
            val prevLevel = prev?.second?.second ?: Int.MAX_VALUE
            if (prev == null || (t.second ?: Int.MAX_VALUE) < prevLevel) byRaw[t.first] = cookedId to t
        }
        val rawIds = LinkedHashSet<Int>().apply { addAll(byRaw.keys); addAll(seed.keys) }
        for (rawId in rawIds) {
            val cacheRow = byRaw[rawId]
            val refRow = seed[rawId]
            val rawName = Skilling.itemNameOf(rawId) ?: continue
            val cookedId = cacheRow?.first ?: intOrNull(refRow, "product_id") ?: continue
            val cookedName = Skilling.itemNameOf(cookedId) ?: continue
            val level = cacheRow?.second?.second
            val xpFromCache = cacheRow?.second?.third
            val refLevel = intOrNull(refRow, "level")
            val refXp = intOrNull(refRow, "xp_tenths")
            val resolvedLevel = level ?: refLevel ?: continue
            val baseXp = xpFromCache ?: refXp ?: continue
            val fixedValue = FIXED_XP_TENTHS[rawId]
            val xpTenths = fixedValue ?: (baseXp * FIRE_XP_BONUS_PERCENT / 100)
            val (burntId, burntSource) = burntItemOf(rawId, refRow)
            if (burntId == null) continue
            out[rawId] = Recipe(
                rawId = rawId, rawName = rawName,
                cookedId = cookedId, cookedName = cookedName,
                burntId = burntId, burntName = Skilling.itemNameOf(burntId),
                level = resolvedLevel,
                xpTenths = xpTenths,
                stopBurnLevel = intOrNull(refRow, "stop_burn_fire"),
                category = refRow?.get("category")?.takeIf { !it.isJsonNull }?.asString,
                levelSource = if (level != null) "CACHE" else "TABLE",
                xpSource = if (fixedValue != null) "FIXED" else if (xpFromCache != null) "CACHE" else "TABLE",
                burntSource = burntSource
            )
        }
        logger.info {
            "cooking: ${out.size} fire recipes (cache ${cache.size} cooked items, seed ${seed.size} raw foods); " +
                "${out.count { it.value.stopBurnLevel != null }} carry a stop-burning level"
        }
        return out
    }

    val BURN_AT_REQUIREMENT: Double =
        System.getProperty("opennxt.cooking.burnAtRequirement")?.toDoubleOrNull() ?: 0.4018

    val DEFAULT_BURN_CHANCE: (Recipe, Int) -> Double = { recipe, level ->
        val stop = recipe.stopBurnLevel
        when {
            stop == null -> BURN_AT_REQUIREMENT
            level >= stop -> 0.0
            level <= recipe.level -> BURN_AT_REQUIREMENT
            else -> BURN_AT_REQUIREMENT * (stop - level).toDouble() / (stop - recipe.level).toDouble()
        }
    }

    var burnChance: (Recipe, Int) -> Double = DEFAULT_BURN_CHANCE

    val stopOnMove: Boolean = System.getProperty("opennxt.cooking.stopOnMove") != "off"

    var random: java.util.Random = java.util.Random(0x434f4f4bL)

    @Volatile var containerSupplier: (ContentPlayer) -> ItemContainer = { it.inventory }
    @Volatile var levelSupplier: (ContentPlayer, Stat) -> Int = { _, _ -> 1 }
    @Volatile var xpSink: (ContentPlayer, Stat, Double) -> Unit = { _, _, _ -> }
    @Volatile var inventoryResend: (ContentPlayer) -> Boolean = { false }
    @Volatile var animationSink: (ContentPlayer, IntArray) -> Unit = { _, _ -> }
    @Volatile var messageSink: (ContentPlayer, String) -> Unit = { _, _ -> }

    @Volatile var faceSink: (ContentPlayer, Int) -> Unit = { _, _ -> }

    @Volatile var onlineCheck: (ContentPlayer) -> Boolean = { true }

    @Volatile var locationSupplier: (ContentPlayer) -> TileLocation = { it.location }

    @Volatile var firePresent: (Int, Int, Int, Int) -> Boolean = { locId, x, z, plane ->
        defaultFirePresent(locId, x, z, plane)
    }

    fun defaultFirePresent(locId: Int, x: Int, z: Int, plane: Int): Boolean {
        if (runCatching { Firemaking.fireAt(plane, x, z) }.getOrNull() != null) return true
        return runCatching {
            LocInteraction.placementOf(locId, x, z, plane) != null ||
                LocInteraction.placementsCovering(x, z, plane).any { it.locId == locId }
        }.getOrDefault(false)
    }

    enum class Outcome {
        STARTED, COOKED, BURNT, NO_RECIPE, NEED_LEVEL, NOT_HELD, OUT_OF_RAW, DISABLED, FULL,

        NO_FIRE
    }

    data class Result(
        val outcome: Outcome,
        val recipe: Recipe? = null,
        val itemId: Int? = null,
        val xpTenths: Int = 0,
        val message: String? = null,
        val chance: Double = 0.0,
        val detail: String = ""
    ) {
        val produced: Boolean get() = outcome == Outcome.COOKED || outcome == Outcome.BURNT
    }

    data class Active(
        val recipe: Recipe,
        val locId: Int,
        val startTile: TileLocation,
        val fireTile: TileLocation,
        var nextTick: Long,
        var cycles: Int = 0,
        var cooked: Int = 0,
        var burnt: Int = 0
    )

    private val active: MutableMap<ContentPlayer, Active> =
        Collections.synchronizedMap(WeakHashMap<ContentPlayer, Active>())

    private val lastCycle: MutableMap<ContentPlayer, Long> =
        Collections.synchronizedMap(WeakHashMap<ContentPlayer, Long>())

    private var tickCount: Long = 0
    private var cyclesPaid = 0
    private var actionsStarted = 0
    private var actionsStopped = 0
    private var animationsSent = 0
    private var messagesSent = 0
    private var facesSent = 0

    @Volatile private var containedFailures = 0
    fun containedFailures(): Int = containedFailures

    fun ticks(): Long = tickCount
    fun activeCount(): Int = active.size
    fun activeFor(player: ContentPlayer): Active? = active[player]
    fun lastCycleOf(player: ContentPlayer): Long? = lastCycle[player]
    fun cyclesPaid(): Int = cyclesPaid
    fun actionsStarted(): Int = actionsStarted
    fun actionsStopped(): Int = actionsStopped
    fun animationsSent(): Int = animationsSent
    fun messagesSent(): Int = messagesSent
    fun facesSent(): Int = facesSent

    internal fun clearActions() {
        active.clear(); lastCycle.clear(); tickCount = 0
        cyclesPaid = 0; actionsStarted = 0; actionsStopped = 0; animationsSent = 0; messagesSent = 0
        facesSent = 0
        containedFailures = 0
        awarded.clear()
    }

    private val awarded: MutableMap<ContentPlayer, Int> = Collections.synchronizedMap(WeakHashMap())
    fun xpTenthsAwarded(player: ContentPlayer): Int = awarded[player] ?: 0

    internal object SLOT : ActionSlot.Owner {
        override val actionName = "cooking"
        override fun cancelSlot(player: ContentPlayer, why: String) = stopFor(player, why)
    }

    fun stopFor(player: ContentPlayer, why: String) {
        val was = active.remove(player)
        ActionSlot.release(player, SLOT)
        if (was != null) {
            actionsStopped++
            logger.info {
                "cooking: ${player.name} stopped after ${was.cycles} cycles " +
                    "(${was.cooked} cooked, ${was.burnt} burnt) - $why"
            }
        }
    }

    private fun animate(player: ContentPlayer) {
        runCatching { animationSink(player, ANIMATION) }; animationsSent++
    }

    private fun say(player: ContentPlayer, message: String) {
        runCatching { messageSink(player, message) }; messagesSent++
    }

    private fun face(player: ContentPlayer, from: TileLocation, fireTile: TileLocation, locId: Int): Int? {
        val placed = runCatching {
            LocInteraction.placementOf(locId, fireTile.x, fireTile.y, fireTile.plane)
        }.getOrNull()
        val ox = placed?.originX ?: fireTile.x
        val oz = placed?.originZ ?: fireTile.y
        val sx = placed?.dx ?: 1
        val sz = placed?.dz ?: 1
        if (2 * from.x + 1 == 2 * ox + sx && 2 * from.y + 1 == 2 * oz + sz) return null
        val angle = PlayerFaceDirectionBlock.towards(from.x, from.y, ox, oz, sx, sz)
        runCatching { faceSink(player, angle) }
        facesSent++
        return angle
    }

    fun start(player: ContentPlayer, rawId: Int, locId: Int, tile: TileLocation, fireTile: TileLocation): Result {
        if (!enabled) return Result(Outcome.DISABLED, detail = "-Dopennxt.experiment.cooking=false")
        val recipe = recipes[rawId]
            ?: return Result(Outcome.NO_RECIPE, detail = "item $rawId is not a fire recipe")
        if (!runCatching { firePresent(locId, fireTile.x, fireTile.y, fireTile.plane) }.getOrDefault(false)) {
            logger.info {
                "cooking: ${player.name} rejected, no fire for loc $locId at (${fireTile.x},${fireTile.y},${fireTile.plane})"
            }
            return Result(Outcome.NO_FIRE, recipe, detail = "no fire at (${fireTile.x},${fireTile.y},${fireTile.plane})")
        }
        val container = containerSupplier(player)
        if (!container.contains(rawId))
            return Result(Outcome.NOT_HELD, recipe, detail = "${recipe.rawName} is not in the backpack")
        val level = levelSupplier(player, Stat.COOKING)
        if (level < recipe.level)
            return Result(Outcome.NEED_LEVEL, recipe, detail = "Cooking $level < ${recipe.level}")

        face(player, tile, fireTile, locId)

        val last = lastCycle[player]
        val due = if (last != null && tickCount - last < CYCLE_TICKS) last + CYCLE_TICKS
        else tickCount + FIRST_CYCLE_TICKS
        active[player] = Active(recipe, locId, tile, fireTile, due)
        ActionSlot.claim(player, SLOT)
        actionsStarted++
        if (due == tickCount + FIRST_CYCLE_TICKS && FIRST_CYCLE_TICKS == ANIMATION_LEAD_TICKS) animate(player)
        logger.info {
            "cooking: ${player.name} started ${recipe.rawName} -> ${recipe.cookedName} on loc $locId " +
                "(level ${recipe.level}/${recipe.levelSource}, ${recipe.xpTenths / 10.0} xp/${recipe.xpSource}, " +
                "burnt ${recipe.burntName}/${recipe.burntSource}, stop ${recipe.stopBurnLevel}); first result at tick $due"
        }
        return Result(Outcome.STARTED, recipe, detail = "first result at tick $due")
    }

    private fun cycle(player: ContentPlayer, a: Active): Result {
        val recipe = a.recipe
        if (!runCatching { firePresent(a.locId, a.fireTile.x, a.fireTile.y, a.fireTile.plane) }.getOrDefault(false)) {
            return Result(
                Outcome.NO_FIRE, recipe,
                detail = "the fire at (${a.fireTile.x},${a.fireTile.y},${a.fireTile.plane}) is gone"
            )
        }
        val container = containerSupplier(player)
        val slot = container.slotOf(recipe.rawId)
        if (slot < 0) return Result(Outcome.OUT_OF_RAW, recipe)

        val level = levelSupplier(player, Stat.COOKING)
        val chance = burnChance(recipe, level).coerceIn(0.0, 1.0)
        val burns = random.nextDouble() < chance
        val product = if (burns) recipe.burntId else recipe.cookedId

        val held = container[slot]
        if (held == null || held.id != recipe.rawId) {
            return Result(Outcome.OUT_OF_RAW, recipe, detail = "slot $slot no longer held ${recipe.rawName}")
        }
        val remainder = held.minus(1)
        if (remainder == null) container.removeSlot(slot) else container[slot] = remainder
        val add = container.add(product, 1)
        if (add.added < 1) {
            container[slot] = held
            return Result(Outcome.FULL, recipe, detail = "no room for $product; one ${recipe.rawName} restored")
        }

        val message = if (burns) burnMessage(recipe.rawName) else successMessage(recipe.category, recipe.rawName)
        say(player, message)
        inventoryResend(player)
        val xp = if (burns) 0 else recipe.xpTenths
        if (xp > 0) {
            val paid = runCatching { xpSink(player, Stat.COOKING, xp / 10.0) }
                .onFailure { logger.warn(it) { "cooking: failed to award ${xp / 10.0} Cooking xp to ${player.name}" } }
                .isSuccess
            if (paid) synchronized(awarded) { awarded[player] = (awarded[player] ?: 0) + xp }
        }
        if (burns) a.burnt++ else a.cooked++
        return Result(
            if (burns) Outcome.BURNT else Outcome.COOKED,
            recipe, itemId = product, xpTenths = xp, message = message, chance = chance
        )
    }

    fun tick(): Int {
        tickCount++
        if (active.isEmpty()) return 0
        var paid = 0
        for ((player, a) in ArrayList(active.entries).map { it.key to it.value }) {
            var advanced = false
            try {
                if (!onlineCheck(player)) { stopFor(player, "no longer online"); continue }
                val here = locationSupplier(player)
                if (stopOnMove &&
                    (here.x != a.startTile.x || here.y != a.startTile.y || here.plane != a.startTile.plane)) {
                    stopFor(player, "moved from (${a.startTile.x},${a.startTile.y}) to (${here.x},${here.y})")
                    continue
                }
                if (tickCount == a.nextTick - ANIMATION_LEAD_TICKS) animate(player)
                if (tickCount < a.nextTick) continue
                val r = cycle(player, a)
                lastCycle[player] = tickCount
                a.cycles++
                when (r.outcome) {
                    Outcome.COOKED, Outcome.BURNT -> {
                        paid++; cyclesPaid++
                        a.nextTick = tickCount + CYCLE_TICKS
                        advanced = true
                        if (containerSupplier(player).slotOf(a.recipe.rawId) < 0)
                            stopFor(player, "out of ${a.recipe.rawName} after ${a.cooked} cooked, ${a.burnt} burnt")
                    }
                    else -> { stopFor(player, "cycle refused: ${r.outcome} ${r.detail}"); advanced = true }
                }
            } catch (t: Throwable) {
                containedFailures++
                logger.error(t) {
                    "cooking: tick failed for ${player.name}"
                }
            } finally {
                if (!advanced && tickCount >= a.nextTick) a.nextTick = tickCount + CYCLE_TICKS
            }
        }
        return paid
    }

    fun install(): Int {
        if (!enabled) {
            logger.warn { "cooking: disabled by -Dopennxt.experiment.cooking=false; no fire is bound" }
            return 0
        }
        var bound = 0
        for (loc in FIRE_LOCS) {
            val ok = runCatching {
                com.opennxt.content.ContentRegistry.onItemOnLoc(itemId = null, locId = loc, action = USE_ACTION) { ctx ->
                    start(
                        ctx.player, ctx.itemId, ctx.locId, locationSupplier(ctx.player),
                        TileLocation(ctx.x, ctx.z, ctx.plane)
                    )
                }
            }.isSuccess
            if (ok) bound++ else logger.warn { "cooking: loc $loc is not bindable (no definition, or it does not declare '$USE_ACTION')" }
        }
        logger.info { "cooking: bound $bound fire loc(s) for '$USE_ACTION'; ${recipes.size} recipes" }
        return bound
    }
}

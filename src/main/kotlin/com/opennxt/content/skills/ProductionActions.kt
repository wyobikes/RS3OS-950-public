package com.opennxt.content.skills

import com.opennxt.api.stat.Stat
import com.opennxt.content.ActionSlot
import com.opennxt.content.ContentPlayer
import com.opennxt.model.items.Item
import com.opennxt.model.items.ItemContainer
import com.opennxt.model.world.TileLocation
import mu.KotlinLogging
import java.util.Collections
import java.util.IdentityHashMap

object ProductionActions {
    private val logger = KotlinLogging.logger { }

    val enabled: Boolean get() = System.getProperty("opennxt.experiment.production") != "false"

    val DEDICATED: Set<Stat> = setOf(Stat.SMITHING, Stat.COOKING)

    const val CYCLE_TICKS = 3

    const val MESSAGE_TYPE = 109

    const val MAX_MAKES_PER_CLICK = 10_000

    val BACKPACK_VERBS: Set<String> = setOf(
        "clean", "craft", "fletch", "string", "grind", "mix", "crush", "weave", "spin", "cut",
        "imbue", "combine", "assemble", "feather", "tip", "bless", "transmute", "restore"
    )

    val TOOLBELT_TOOLS: Set<Int> = setOf(
        2347,
        946,
        1755,
        1733,
        590,
        233,
        1785,
        8794,
        1735,
        5325,
        952,
        5341,
        5343,
        5329,
        1592,
        1597,
        1595,
        11065,
        5523,
        4,
        17754
    )

    val toolbeltEnabled: Boolean get() = System.getProperty("opennxt.production.toolbelt") != "off"

    fun animationFor(stat: Stat): Int? =
        System.getProperty("opennxt.production.anim.${stat.name.lowercase()}")?.toIntOrNull()

    fun makeLine(recipe: SkillRecipes.Recipe): String {
        val n = recipe.productCount
        val name = (recipe.productName ?: "item ${recipe.productId}").lowercase()
        return if (n > 1) "You make $n $name." else "You make ${article(name)} $name."
    }

    private fun article(name: String): String {
        val first = name.firstOrNull()?.lowercaseChar() ?: return "a"
        return if (first in "aeiou") "an" else "a"
    }

    @Volatile var containerSupplier: (ContentPlayer) -> ItemContainer = { it.inventory }
    @Volatile var wornSupplier: (ContentPlayer) -> Collection<Int> = { p -> p.equipment.worn().values.map { it.id } }
    @Volatile var levelSupplier: (ContentPlayer, Stat) -> Int = { _, _ -> 1 }
    @Volatile var xpSink: (ContentPlayer, Stat, Double) -> Unit = { _, _, _ -> }
    @Volatile var messageSink: (ContentPlayer, Int, String) -> Unit = { _, _, _ -> }
    @Volatile var animationSink: (ContentPlayer, IntArray) -> Unit = { _, _ -> }
    @Volatile var inventoryResend: (ContentPlayer) -> Boolean = { false }
    @Volatile var tileSupplier: (ContentPlayer) -> TileLocation = { it.location }
    @Volatile var locStillThere: (ContentPlayer, Int, Int, Int, Int) -> Boolean = { _, _, _, _, _ -> true }

    fun resetSeams() {
        containerSupplier = { it.inventory }
        wornSupplier = { p -> p.equipment.worn().values.map { it.id } }
        levelSupplier = { _, _ -> 1 }
        xpSink = { _, _, _ -> }
        messageSink = { _, _, _ -> }
        animationSink = { _, _ -> }
        inventoryResend = { false }
        tileSupplier = { it.location }
        locStillThere = { _, _, _, _, _ -> true }
    }

    enum class Outcome {
        NOT_MINE,
        NO_RECIPE,
        LEVEL_TOO_LOW,
        NO_TOOL,
        NO_MATERIALS,
        GONE,
        STARTED,
        MADE,
        NO_SPACE,
        STOPPED
    }

    data class Result(val outcome: Outcome, val recipe: SkillRecipes.Recipe? = null, val detail: String = "")

    fun hasTool(player: ContentPlayer, toolId: Int): Boolean =
        (toolbeltEnabled && toolId in TOOLBELT_TOOLS) ||
            containerSupplier(player).contains(toolId) ||
            wornSupplier(player).contains(toolId)

    fun makeable(container: ItemContainer, recipe: SkillRecipes.Recipe): Int {
        if (recipe.materials.isEmpty()) return 0
        var n = Long.MAX_VALUE
        for (m in recipe.materials) n = minOf(n, container.count(m.itemId) / m.count)
        return n.coerceIn(0L, MAX_MAKES_PER_CLICK.toLong()).toInt()
    }

    fun ownedByDedicated(recipe: SkillRecipes.Recipe): Boolean =
        recipe.stat in DEDICATED ||
            (recipe.stat == Stat.FLETCHING && recipe.materials.any { m -> nameResolverForMaterials(m.itemId)?.endsWith("logs", ignoreCase = true) == true })

    @Volatile var nameResolverForMaterials: (Int) -> String? = { null }

    fun refusalFor(player: ContentPlayer, recipe: SkillRecipes.Recipe): Result? {
        if (ownedByDedicated(recipe)) return Result(Outcome.NOT_MINE, recipe, "owned by a dedicated module")
        if (recipe.progress != null) return Result(Outcome.NOT_MINE, recipe, "a progress (forge) recipe")
        val lvl = levelSupplier(player, recipe.stat)
        if (lvl < recipe.level)
            return Result(Outcome.LEVEL_TOO_LOW, recipe, "You need a ${SkillRecipes.refName(recipe.stat)} level of ${recipe.level} to make that.")
        for ((s, l) in recipe.extraSkillLevels) {
            if (levelSupplier(player, s) < l)
                return Result(Outcome.LEVEL_TOO_LOW, recipe, "You need a ${SkillRecipes.refName(s)} level of $l to make that.")
        }
        val missingTool = recipe.tools.firstOrNull { !hasTool(player, it) }
        if (missingTool != null) return Result(Outcome.NO_TOOL, recipe, "You need a ${nameOf(missingTool)} to make that.")
        if (makeable(containerSupplier(player), recipe) <= 0)
            return Result(Outcome.NO_MATERIALS, recipe, "You don't have the materials to make that.")
        return null
    }

    @Volatile var nameResolver: (Int) -> String? = { id -> SkillRecipes.recipeFor(id)?.productName }
    private fun nameOf(id: Int): String = (nameResolver(id) ?: "tool").lowercase()

    fun choose(player: ContentPlayer, candidates: List<SkillRecipes.Recipe>): Result {
        val usable = candidates.filter { !ownedByDedicated(it) && it.progress == null && it.materials.isNotEmpty() }
        if (usable.isEmpty()) return Result(Outcome.NO_RECIPE)
        val ok = usable.filter { refusalFor(player, it) == null }
        if (ok.isNotEmpty()) return Result(Outcome.STARTED, ok.maxWith(compareBy<SkillRecipes.Recipe> { it.level }.thenBy { -it.productId }))
        val order = listOf(Outcome.NO_TOOL, Outcome.LEVEL_TOO_LOW, Outcome.NO_MATERIALS)
        val refusals = usable.mapNotNull { refusalFor(player, it) }
        return refusals.minByOrNull { r -> order.indexOf(r.outcome).let { if (it < 0) 99 else it } }
            ?: Result(Outcome.NO_RECIPE)
    }

    data class Active(
        val recipe: SkillRecipes.Recipe,
        var remaining: Int,
        val startTile: TileLocation,
        val loc: IntArray? = null
    )

    private val active: MutableMap<ContentPlayer, Active> = Collections.synchronizedMap(IdentityHashMap())

    private val nextMakeAt: MutableMap<ContentPlayer, Long> = Collections.synchronizedMap(java.util.WeakHashMap())

    @Volatile private var clock = 0L
    @Volatile private var makes = 0
    @Volatile private var starts = 0

    fun clockTick(): Long = clock
    fun makesCount(): Int = makes
    fun startsCount(): Int = starts
    fun activeFor(player: ContentPlayer): Active? = active[player]
    internal fun resetState() { active.clear(); nextMakeAt.clear(); clock = 0; makes = 0; starts = 0 }

    private val owners = java.util.concurrent.ConcurrentHashMap<Stat, ActionSlot.Owner>()

    fun ownerFor(stat: Stat): ActionSlot.Owner = owners.computeIfAbsent(stat) { s ->
        object : ActionSlot.Owner {
            override val actionName: String = "production:${s.name.lowercase()}"
            override fun cancelSlot(player: ContentPlayer, why: String) { stop(player, why) }
        }
    }

    fun stop(player: ContentPlayer, why: String): Boolean {
        val a = active.remove(player) ?: return false
        ActionSlot.release(player, ownerFor(a.recipe.stat))
        logger.info { "production ${player.name}: stopped ${a.recipe.productName} ($why)" }
        return true
    }

    fun cull(player: ContentPlayer, why: String) {
        stop(player, why)
        nextMakeAt.remove(player)
    }

    fun onBackpackOption(player: ContentPlayer, action: String, itemId: Int, slot: Int): Result {
        if (!enabled || !SkillRecipes.enabled) return Result(Outcome.NOT_MINE)
        if (action.lowercase() !in BACKPACK_VERBS) return Result(Outcome.NOT_MINE)
        val c = containerSupplier(player)
        if (slot !in 0 until c.size || c[slot]?.id != itemId) return Result(Outcome.GONE)
        val candidates = SkillRecipes.recipesUsing(itemId)
        if (candidates.all { ownedByDedicated(it) }) return Result(Outcome.NOT_MINE)
        return begin(player, candidates, null, "backpack '$action' on $itemId")
    }

    fun onItemOnItem(player: ContentPlayer, usedId: Int, usedSlot: Int, targetId: Int, targetSlot: Int): Result {
        if (!enabled || !SkillRecipes.enabled) return Result(Outcome.NOT_MINE)
        val c = containerSupplier(player)
        if (usedSlot !in 0 until c.size || targetSlot !in 0 until c.size) return Result(Outcome.GONE, detail = "slot out of range")
        if (usedSlot == targetSlot) return Result(Outcome.GONE, detail = "same slot")
        if (c[usedSlot]?.id != usedId || c[targetSlot]?.id != targetId) return Result(Outcome.GONE, detail = "slots do not hold those items")
        val candidates = SkillRecipes.recipesUsingBoth(usedId, targetId)
        if (candidates.isEmpty()) {
            messageSink(player, 0, "Nothing interesting happens.")
            return Result(Outcome.NO_RECIPE)
        }
        return begin(player, candidates, null, "item $usedId on item $targetId")
    }

    fun onLoc(player: ContentPlayer, stat: Stat, locId: Int, x: Int, z: Int, plane: Int, filter: (SkillRecipes.Recipe) -> Boolean): Result {
        if (!enabled || !SkillRecipes.enabled) return Result(Outcome.NOT_MINE)
        val candidates = SkillRecipes.recipesFor(stat).filter(filter)
        if (candidates.isEmpty()) return Result(Outcome.NO_RECIPE)
        return begin(player, candidates, intArrayOf(locId, x, z, plane), "loc $locId")
    }

    private fun begin(player: ContentPlayer, candidates: List<SkillRecipes.Recipe>, loc: IntArray?, via: String): Result {
        val choice = choose(player, candidates)
        if (choice.outcome != Outcome.STARTED) {
            if (choice.detail.isNotEmpty()) messageSink(player, 0, choice.detail)
            logger.info { "production ${player.name}: $via refused - ${choice.outcome} ${choice.recipe ?: ""}" }
            return choice
        }
        val recipe = choice.recipe!!
        val count = makeable(containerSupplier(player), recipe)
        ActionSlot.claim(player, ownerFor(recipe.stat))
        active[player] = Active(recipe, count, tileSupplier(player), loc)
        val due = clock + CYCLE_TICKS
        nextMakeAt.merge(player, due) { old, new -> maxOf(old, new) }
        starts++
        logger.info {
            "production ${player.name}: $via -> $recipe x$count, " +
                "${SkillRecipes.xpOf(recipe)} ${recipe.xpStat.name} xp each [/${SkillRecipes.divisorFor(recipe.xpStat)}], first make at clock ${nextMakeAt[player]}"
        }
        return Result(Outcome.STARTED, recipe, "x$count")
    }

    fun tick() {
        clock++
        if (active.isEmpty()) return
        val snapshot = synchronized(active) { active.entries.map { it.key to it.value } }
        for ((player, a) in snapshot) {
            try {
                if (active[player] !== a) continue
                val tile = tileSupplier(player)
                if (tile.x != a.startTile.x || tile.y != a.startTile.y || tile.plane != a.startTile.plane) { stop(player, "moved"); continue }
                val loc = a.loc
                if (loc != null && !locStillThere(player, loc[0], loc[1], loc[2], loc[3])) { stop(player, "the loc is gone"); continue }
                if ((nextMakeAt[player] ?: 0L) > clock) continue
                nextMakeAt[player] = clock + CYCLE_TICKS
                val r = makeOnce(player, a.recipe)
                when (r.outcome) {
                    Outcome.MADE -> { a.remaining--; if (a.remaining <= 0) stop(player, "made all") }
                    else -> {
                        if (r.detail.isNotEmpty()) messageSink(player, 0, r.detail)
                        stop(player, r.outcome.name)
                    }
                }
            } catch (t: Throwable) {
                logger.error(t) { "production ${player.name}: the make threw - CONTAINED, the action is stopped" }
                runCatching { stop(player, "threw") }
            }
        }
    }

    fun makeOnce(player: ContentPlayer, recipe: SkillRecipes.Recipe): Result {
        refusalFor(player, recipe)?.let { return it }
        val c = containerSupplier(player)
        val snapshot = c.toArray()
        for (m in recipe.materials) {
            val removed = c.remove(m.itemId, m.count)
            if (removed.removed != m.count) { restore(c, snapshot); return Result(Outcome.NO_MATERIALS, recipe, "You don't have the materials to make that.") }
        }
        val added = c.add(recipe.productId, recipe.productCount)
        if (added.remaining > 0) {
            restore(c, snapshot)
            return Result(Outcome.NO_SPACE, recipe, "You don't have enough inventory space.")
        }
        makes++
        inventoryResend(player)
        animationFor(recipe.stat)?.let { animationSink(player, intArrayOf(it, it, it, it)) }
        messageSink(player, MESSAGE_TYPE, makeLine(recipe))
        val xp = SkillRecipes.xpOf(recipe)
        if (xp > 0) runCatching { xpSink(player, recipe.xpStat, xp) }
            .onFailure { logger.error(it) { "production ${player.name}: xp sink threw after ${recipe.productName} was made - the product stands" } }
        return Result(Outcome.MADE, recipe)
    }

    private fun restore(c: ItemContainer, snapshot: Array<Item?>) {
        for (i in snapshot.indices) c[i] = snapshot[i]
    }
}

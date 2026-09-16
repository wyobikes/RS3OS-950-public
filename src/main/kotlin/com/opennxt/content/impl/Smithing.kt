package com.opennxt.content.impl

import com.google.gson.JsonParser
import com.opennxt.api.stat.Stat
import com.opennxt.content.ActionSlot
import com.opennxt.content.ContentPlayer
import com.opennxt.model.items.ItemContainer
import com.opennxt.model.world.TileLocation
import com.opennxt.resources.sqlite.RsDatabase
import mu.KotlinLogging
import java.util.Collections
import java.util.WeakHashMap

object Smithing {
    private val logger = KotlinLogging.logger { }

    val enabled: Boolean = System.getProperty("opennxt.experiment.smithing") != "false"

    const val SMELT_ACTION = "Smelt"

    const val HEAT_ACTION = "Heat"

    const val SMITH_ACTION = "Smith"

    const val OPEN_INTERFACE_ACTION = "Open smithing interface"

    const val FURNACE_LOC = 67465

    const val FORGE_LOC = 113259

    const val ANVIL_LOC = 113258

    const val PANEL_INTERFACE = 37
    const val PANEL_BEGIN_COMPONENT = 163
    const val PANEL_RECIPE_COMPONENT = 103
    const val PANEL_PARENT = 1477
    const val PANEL_PARENT_COMPONENT = 726

    const val METAL_BANK_INTERFACE = 487

    val SMELT_CYCLE_TICKS: Int = System.getProperty("opennxt.smithing.smeltCycle")?.toIntOrNull() ?: 4

    val SMELT_FIRST_CYCLE_TICKS: Int =
        System.getProperty("opennxt.smithing.smeltFirstCycle")?.toIntOrNull() ?: 4

    val SMITH_CYCLE_TICKS: Int = System.getProperty("opennxt.smithing.smithCycle")?.toIntOrNull() ?: 2

    val SMITH_FIRST_CYCLE_TICKS: Int =
        System.getProperty("opennxt.smithing.smithFirstCycle")?.toIntOrNull() ?: 3

    val SMELT_ANIMATION: IntArray = intArrayOf(32626, 32626, 32626, 32626)

    val SMELT_ANIMATION_LEAD_TICKS: Int get() = SMELT_CYCLE_TICKS

    val HEAT_ANIMATION: IntArray = intArrayOf(32627, 32627, 32627, 32627)

    val SMITH_ANIMATION: IntArray = intArrayOf(32622, 32622, 32622, 32622)

    val STOP_ANIMATION: IntArray = intArrayOf(-1, -1, -1, -1)

    const val UNFINISHED_ITEM = 47068

    const val CREATE_HEAT = 10

    const val HEAT_PER_SWING = 10

    fun heatMax(level: Int): Int = 312 + 3 * level.coerceAtLeast(1)

    val HEAT_GAIN_PER_TICK: Int = System.getProperty("opennxt.smithing.heatGain")?.toIntOrNull() ?: 55

    fun heatStage(heat: Int, max: Int): Int = when {
        heat <= 0 -> 3
        heat * 3 > max * 2 -> 0
        heat * 3 > max -> 1
        else -> 2
    }

    const val BASE_PROGRESS_PER_SWING = 20

    val HEAT_DECAY_FACTOR: Double =
        System.getProperty("opennxt.smithing.heatDecayFactor")?.toDoubleOrNull() ?: 0.8

    fun progressPerSwing(stage: Int): Int =
        Math.round(BASE_PROGRESS_PER_SWING * Math.pow(HEAT_DECAY_FACTOR, stage.toDouble())).toInt()

    fun baseXpTenthsPerSwing(recipe: SmithRecipe): Int =
        Math.round(BASE_PROGRESS_PER_SWING.toDouble() * recipe.xpTenths / recipe.progressTotal).toInt()

    fun xpTenthsPerSwing(recipe: SmithRecipe, stage: Int): Int =
        Math.round(baseXpTenthsPerSwing(recipe) * Math.pow(HEAT_DECAY_FACTOR, stage.toDouble())).toInt()

    fun createAtForgeMessage(name: String) = "You create an unfinished item and begin to heat it: $name."

    fun createAtAnvilMessage(name: String) = "You create an unfinished item and begin to work it: $name."

    fun fullHeatMessage(name: String) = "Your unfinished item is at full heat: $name. Use the anvil."

    const val COOLED_SLIGHTLY =
        "<col=FF0000>Your item has cooled down slightly. It will be slightly harder to work.</col>"

    const val COOLED_SIGNIFICANTLY =
        "<col=FF0000>Your item has cooled down significantly. It's a lot slower to work.</col>"

    const val RAN_OUT_OF_HEAT =
        "<col=EB2F2F>Your item has run out of heat. It's very slow to work. Heat it at a forge."

    fun finishMessage(name: String) = "You finish smithing: $name."

    const val DEPOSIT_BARS =
        "You should deposit your bars in your metal bank (via a furnace, forge or anvil)."

    data class Material(val itemId: Int, val name: String?, val count: Int)

    data class SmeltRecipe(
        val barId: Int,
        val barName: String,
        val level: Int,
        val xpTenths: Int,
        val toolId: Int?,
        val materials: List<Material>
    )

    data class SmithRecipe(
        val productId: Int,
        val productName: String,
        val level: Int,
        val xpTenths: Int,
        val progressTotal: Int,
        val toolId: Int?,
        val materials: List<Material>
    )

    private data class StructProp(val structId: Int, val prop: Int, val intValue: Int?, val stringValue: String?)

    const val STRUCT_ITEM = 2656
    const val STRUCT_ITEM_ALT = 7763
    const val STRUCT_COUNT = 2665
    const val STRUCT_COUNT_ALT = 2666
    const val STRUCT_NAME = 7762

    internal fun materialStructs(): Map<Int, Material> {
        if (!RsDatabase.available) return emptyMap()
        val rows = RsDatabase.queryAll(
            "SELECT struct_id, prop, intvalue, stringvalue FROM struct_param WHERE prop IN " +
                "($STRUCT_ITEM, $STRUCT_ITEM_ALT, $STRUCT_COUNT, $STRUCT_COUNT_ALT, $STRUCT_NAME)"
        ) { rs ->
            val v = rs.getInt("intvalue")
            StructProp(rs.getInt("struct_id"), rs.getInt("prop"), if (rs.wasNull()) null else v,
                rs.getString("stringvalue"))
        }
        val item = HashMap<Int, Int>()
        val itemAlt = HashMap<Int, Int>()
        val count = HashMap<Int, Int>()
        val countAlt = HashMap<Int, Int>()
        val name = HashMap<Int, String>()
        for (r in rows) when (r.prop) {
            STRUCT_ITEM -> r.intValue?.let { item[r.structId] = it }
            STRUCT_ITEM_ALT -> r.intValue?.let { itemAlt[r.structId] = it }
            STRUCT_COUNT -> r.intValue?.let { count[r.structId] = it }
            STRUCT_COUNT_ALT -> r.intValue?.let { countAlt[r.structId] = it }
            STRUCT_NAME -> r.stringValue?.let { name[r.structId] = it }
        }
        val out = HashMap<Int, Material>()
        for (id in (item.keys + itemAlt.keys)) {
            val i = item[id] ?: itemAlt[id] ?: continue
            val n = count[id] ?: countAlt[id] ?: continue
            if (i <= 0 || n <= 0) continue
            out[id] = Material(i, name[id] ?: Skilling.itemNameOf(i), n)
        }
        return out
    }

    internal fun countPropAgreement(): Pair<Int, Int> {
        if (!RsDatabase.available) return 0 to 0
        val a = HashMap<Int, Int>()
        val b = HashMap<Int, Int>()
        RsDatabase.queryAll(
            "SELECT struct_id, prop, intvalue FROM struct_param WHERE prop IN ($STRUCT_COUNT, $STRUCT_COUNT_ALT)"
        ) { rs -> Triple(rs.getInt("struct_id"), rs.getInt("prop"), rs.getInt("intvalue")) }
            .forEach { (id, prop, v) -> if (prop == STRUCT_COUNT) a[id] = v else b[id] = v }
        val both = a.keys.intersect(b.keys)
        return both.size to both.count { a[it] != b[it] }
    }

    private fun categoryRows(): Map<Int, Map<Int, Int>> {
        if (!RsDatabase.available) return emptyMap()
        val out = LinkedHashMap<Int, Map<Int, Int>>()
        RsDatabase.queryAll(
            "SELECT id, value FROM items_attr WHERE field = 'extra' AND value LIKE '%\"prop\":2640,\"intvalue\":14%'"
        ) { rs -> rs.getInt("id") to rs.getString("value") }.forEach { (id, json) ->
            val props = HashMap<Int, Int>()
            runCatching {
                for (el in JsonParser().parse(json).asJsonArray) {
                    val o = el.asJsonObject
                    if (o.get("intvalue").isJsonNull) continue
                    props[o.get("prop").asInt] = o.get("intvalue").asInt
                }
            }
            if (props[PROP_CATEGORY] == SMITHING_CATEGORY) out[id] = props
        }
        return out
    }

    const val PROP_CATEGORY = 2640
    const val SMITHING_CATEGORY = 14
    const val PROP_LEVEL = 2645
    const val PROP_XP_TENTHS = 2697
    const val PROP_PROGRESS = 7801
    const val PROP_TOOL = 2650
    val PROP_MATERIALS = intArrayOf(2675, 2676, 2677)

    private fun materialsOf(props: Map<Int, Int>, structs: Map<Int, Material>): List<Material>? {
        val out = ArrayList<Material>(3)
        for (p in PROP_MATERIALS) {
            val structId = props[p] ?: continue
            out += structs[structId] ?: return null
        }
        return if (out.isEmpty()) null else out
    }

    val smeltRecipes: Map<Int, SmeltRecipe> by lazy { buildSmelt() }

    val smithRecipes: Map<Int, SmithRecipe> by lazy { buildSmith() }

    private fun buildSmelt(): Map<Int, SmeltRecipe> {
        val structs = materialStructs()
        val out = LinkedHashMap<Int, SmeltRecipe>()
        for ((id, props) in categoryRows()) {
            if (props.containsKey(PROP_PROGRESS)) continue
            val level = props[PROP_LEVEL] ?: continue
            val xp = props[PROP_XP_TENTHS] ?: continue
            val mats = materialsOf(props, structs) ?: continue
            val name = Skilling.itemNameOf(id) ?: continue
            out[id] = SmeltRecipe(id, name, level, xp, props[PROP_TOOL], mats)
        }
        logger.info { "smithing: ${out.size} smelt recipes from the cache" }
        return out
    }

    private fun buildSmith(): Map<Int, SmithRecipe> {
        val structs = materialStructs()
        val out = LinkedHashMap<Int, SmithRecipe>()
        for ((id, props) in categoryRows()) {
            val progress = props[PROP_PROGRESS] ?: continue
            if (progress <= 0) continue
            val level = props[PROP_LEVEL] ?: continue
            val xp = props[PROP_XP_TENTHS] ?: continue
            val mats = materialsOf(props, structs) ?: continue
            val name = Skilling.itemNameOf(id) ?: continue
            out[id] = SmithRecipe(id, name, level, xp, progress, props[PROP_TOOL], mats)
        }
        logger.info { "smithing: ${out.size} smith recipes from the cache" }
        return out
    }

    val TOOLBELT: Set<Int> = setOf(2347)

    val toolbelt: Boolean = System.getProperty("opennxt.smithing.toolbelt") != "off"

    fun hasTool(container: ItemContainer, toolId: Int?): Boolean =
        toolId == null || (toolbelt && toolId in TOOLBELT) || container.contains(toolId)

    fun canAfford(container: ItemContainer, materials: List<Material>): Boolean =
        materials.all { container.count(it.itemId) >= it.count.toLong() }

    val DEFAULT_SMELT_CHOICE: (ItemContainer, Int) -> SmeltRecipe? = { container, level ->
        smeltRecipes.values
            .filter { it.level <= level && hasTool(container, it.toolId) && canAfford(container, it.materials) }
            .maxWithOrNull(compareBy({ it.level }, { it.xpTenths }, { -it.barId }))
    }

    val DEFAULT_SMITH_CHOICE: (ItemContainer, Int) -> SmithRecipe? = { container, level ->
        smithRecipes.values
            .filter { it.level <= level && hasTool(container, it.toolId) && canAfford(container, it.materials) }
            .maxWithOrNull(compareBy({ it.level }, { it.xpTenths }, { -it.productId }))
    }

    val autoSelect: Boolean = System.getProperty("opennxt.smithing.autoSelect") != "off"

    @Volatile var smeltChoice: (ItemContainer, Int) -> SmeltRecipe? = DEFAULT_SMELT_CHOICE
    @Volatile var smithChoice: (ItemContainer, Int) -> SmithRecipe? = DEFAULT_SMITH_CHOICE

    private val selected: MutableMap<ContentPlayer, Int> = Collections.synchronizedMap(WeakHashMap())

    @Volatile var panelOpener: ((com.opennxt.content.LocContext) -> Boolean)? = null

    const val PANEL_OPENED = "smithing window opened (interface 37)"

    const val PANEL_NOT_OPENED = "smithing window not opened"

    fun select(player: ContentPlayer, itemId: Int): Boolean {
        if (!smeltRecipes.containsKey(itemId) && !smithRecipes.containsKey(itemId)) return false
        selected[player] = itemId
        return true
    }

    fun selectionOf(player: ContentPlayer): Int? = selected[player]

    fun clearSelection(player: ContentPlayer) { selected.remove(player) }

    @Volatile var containerSupplier: (ContentPlayer) -> ItemContainer = { it.inventory }

    @Volatile var metalBankSupplier: (ContentPlayer) -> ItemContainer? = { null }

    @Volatile var metalBankResend: (ContentPlayer) -> Unit = { }

    fun materialView(player: ContentPlayer, backpack: ItemContainer): ItemContainer {
        val metal = metalBankSupplier(player) ?: return backpack
        val view = ItemContainer(backpack.size + metal.size, stackAll = true)
        backpack.items().forEach { view.add(it.id, it.amount) }
        metal.items().forEach { view.add(it.id, it.amount) }
        return view
    }

    data class Taken(val itemId: Int, val fromMetalBank: Int, val fromBackpack: Int)

    fun takeMaterials(player: ContentPlayer, backpack: ItemContainer, materials: List<Material>): Pair<List<Taken>, Material?> {
        val metal = metalBankSupplier(player)
        val taken = ArrayList<Taken>(materials.size)
        for (m in materials) {
            val fromMetal = metal?.remove(m.itemId, m.count)?.removed ?: 0
            val fromPack = if (fromMetal < m.count) backpack.remove(m.itemId, m.count - fromMetal).removed else 0
            taken += Taken(m.itemId, fromMetal, fromPack)
            if (fromMetal + fromPack < m.count) {
                refundMaterials(player, backpack, taken)
                return taken to m
            }
        }
        if (metal != null && taken.any { it.fromMetalBank > 0 }) metalBankResend(player)
        return taken to null
    }

    fun refundMaterials(player: ContentPlayer, backpack: ItemContainer, taken: List<Taken>) {
        val metal = metalBankSupplier(player)
        for (t in taken) {
            if (t.fromMetalBank > 0) metal?.add(t.itemId, t.fromMetalBank)
            if (t.fromBackpack > 0) backpack.add(t.itemId, t.fromBackpack)
        }
    }
    @Volatile var levelSupplier: (ContentPlayer, Stat) -> Int = { _, _ -> 1 }
    @Volatile var xpSink: (ContentPlayer, Stat, Double) -> Unit = { _, _, _ -> }
    @Volatile var inventoryResend: (ContentPlayer) -> Boolean = { false }
    @Volatile var animationSink: (ContentPlayer, IntArray) -> Unit = { _, _ -> }
    @Volatile var messageSink: (ContentPlayer, String) -> Unit = { _, _ -> }
    @Volatile var onlineCheck: (ContentPlayer) -> Boolean = { true }
    @Volatile var locationSupplier: (ContentPlayer) -> TileLocation = { it.location }

    val stopOnMove: Boolean = System.getProperty("opennxt.smithing.stopOnMove") != "off"

    enum class Mode { SMELT, HEAT, SMITH }

    enum class Outcome {
        STARTED, SMELTED, HEATED, FULL_HEAT, SWUNG, FINISHED,
        DISABLED, NO_RECIPE, NEED_LEVEL, NEED_TOOL, NEED_MATERIALS, NEED_PROJECT, NO_ROOM, STOPPED
    }

    data class Result(
        val outcome: Outcome,
        val itemId: Int? = null,
        val xpTenths: Int = 0,
        val message: String? = null,
        val detail: String = ""
    ) {
        val ok: Boolean get() = outcome !in REFUSALS

        companion object {
            val REFUSALS: Set<Outcome> = java.util.EnumSet.of(
                Outcome.DISABLED, Outcome.NO_RECIPE, Outcome.NEED_LEVEL, Outcome.NEED_TOOL,
                Outcome.NEED_MATERIALS, Outcome.NEED_PROJECT, Outcome.NO_ROOM
            )
        }
    }

    data class Project(
        val recipe: SmithRecipe,
        var progress: Int = 0,
        var xpPaidTenths: Int = 0,
        var heat: Int = CREATE_HEAT,
        var stage: Int = 0
    ) {
        val progressLeft: Int get() = recipe.progressTotal - progress
        val xpLeftTenths: Int get() = recipe.xpTenths - xpPaidTenths
    }

    data class ProjectState(
        val productId: Int,
        val progress: Int,
        val xpPaidTenths: Int,
        val heat: Int,
        val stage: Int,
    )

    fun projectSnapshot(player: ContentPlayer): ProjectState? = projects[player]?.let {
        ProjectState(it.recipe.productId, it.progress, it.xpPaidTenths, it.heat, it.stage)
    }

    fun restoreProject(
        player: ContentPlayer,
        productId: Int,
        progress: Int,
        xpPaidTenths: Int,
        heat: Int,
        stage: Int
    ): Boolean {
        val recipe = smithRecipes[productId]
        if (recipe == null) {
            logger.warn {
                "smithing: not restoring ${player.name}'s project; product $productId is not in the " +
                    "recipe table (${smithRecipes.size} rows)"
            }
            return false
        }
        val clampedProgress = progress.coerceIn(0, recipe.progressTotal)
        val clampedXp = xpPaidTenths.coerceIn(0, recipe.xpTenths)
        if (clampedProgress != progress || clampedXp != xpPaidTenths) {
            logger.warn {
                "smithing: ${player.name}'s ${recipe.productName} project out of range (progress " +
                    "$progress/${recipe.progressTotal}, xp ${xpPaidTenths}/${recipe.xpTenths} tenths); " +
                    "clamped to $clampedProgress and $clampedXp"
            }
        }
        projects[player] = Project(
            recipe = recipe,
            progress = clampedProgress,
            xpPaidTenths = clampedXp,
            heat = heat.coerceAtLeast(0),
            stage = stage.coerceAtLeast(0)
        )
        logger.info {
            "smithing: restored ${player.name}'s unfinished ${recipe.productName} " +
                "($clampedProgress/${recipe.progressTotal} progress, heat $heat)"
        }
        return true
    }

    data class Active(
        val mode: Mode,
        val locId: Int,
        val startTile: TileLocation,
        var nextTick: Long,
        val smelt: SmeltRecipe? = null,
        var cycles: Int = 0
    )

    private val active: MutableMap<ContentPlayer, Active> = Collections.synchronizedMap(WeakHashMap())
    private val projects: MutableMap<ContentPlayer, Project> = Collections.synchronizedMap(WeakHashMap())

    private val lastCycle: MutableMap<ContentPlayer, Long> = Collections.synchronizedMap(WeakHashMap())

    private var tickCount: Long = 0
    private var cyclesPaid = 0
    private var actionsStarted = 0
    private var actionsStopped = 0
    private var animationsSent = 0
    private var messagesSent = 0
    private val awarded: MutableMap<ContentPlayer, Int> = Collections.synchronizedMap(WeakHashMap())

    fun ticks(): Long = tickCount
    fun activeCount(): Int = active.size
    fun activeFor(player: ContentPlayer): Active? = active[player]
    fun projectFor(player: ContentPlayer): Project? = projects[player]
    fun lastCycleOf(player: ContentPlayer): Long? = lastCycle[player]
    fun cyclesPaid(): Int = cyclesPaid
    fun actionsStarted(): Int = actionsStarted
    fun actionsStopped(): Int = actionsStopped
    fun animationsSent(): Int = animationsSent
    fun messagesSent(): Int = messagesSent
    fun xpTenthsAwarded(player: ContentPlayer): Int = awarded[player] ?: 0

    @Volatile private var containedFailures = 0
    fun containedFailures(): Int = containedFailures

    internal fun clearActions() {
        active.clear(); projects.clear(); lastCycle.clear(); selected.clear(); awarded.clear()
        tickCount = 0; cyclesPaid = 0; actionsStarted = 0; actionsStopped = 0
        animationsSent = 0; messagesSent = 0; containedFailures = 0
    }

    internal object SLOT : ActionSlot.Owner {
        override val actionName = "smithing"
        override fun cancelSlot(player: ContentPlayer, why: String) = stopFor(player, why)
    }

    fun stopFor(player: ContentPlayer, why: String) {
        val was = active.remove(player)
        ActionSlot.release(player, SLOT)
        if (was != null) {
            actionsStopped++
            animate(player, STOP_ANIMATION)
            logger.info { "smithing: ${player.name} stopped ${was.mode} after ${was.cycles} cycles - $why" }
        }
    }

    fun cull(player: ContentPlayer, why: String) {
        stopFor(player, why)
        selected.remove(player)
    }

    private fun animate(player: ContentPlayer, ids: IntArray) {
        runCatching { animationSink(player, ids) }; animationsSent++
    }

    private fun say(player: ContentPlayer, message: String) {
        runCatching { messageSink(player, message) }; messagesSent++
    }

    private fun payXp(player: ContentPlayer, tenths: Int) {
        if (tenths <= 0) return
        val paid = runCatching { xpSink(player, Stat.SMITHING, tenths / 10.0) }
            .onFailure { logger.warn(it) { "smithing: failed to award ${tenths / 10.0} xp to ${player.name}" } }
            .isSuccess
        if (paid) synchronized(awarded) { awarded[player] = (awarded[player] ?: 0) + tenths }
    }

    private fun dueTick(player: ContentPlayer, cycle: Int, first: Int): Long {
        val last = lastCycle[player]
        return if (last != null && tickCount - last < cycle) last + cycle else tickCount + first
    }

    fun smelt(player: ContentPlayer, locId: Int, tile: TileLocation): Result {
        if (!enabled) return Result(Outcome.DISABLED, detail = "-Dopennxt.experiment.smithing=false")
        val container = materialView(player, containerSupplier(player))
        val level = levelSupplier(player, Stat.SMITHING)
        val chosen = selectionOf(player)?.let { smeltRecipes[it] }
            ?: (if (autoSelect) smeltChoice(container, level) else null)
            ?: return Result(Outcome.NO_RECIPE, detail = "no smelt recipe chosen and none is affordable")
        if (level < chosen.level)
            return Result(Outcome.NEED_LEVEL, chosen.barId, detail = "Smithing $level < ${chosen.level}")
        if (!hasTool(container, chosen.toolId))
            return Result(Outcome.NEED_TOOL, chosen.barId, detail = "no ${chosen.toolId}")
        if (!canAfford(container, chosen.materials))
            return Result(Outcome.NEED_MATERIALS, chosen.barId, detail = chosen.materials.toString())

        val due = dueTick(player, SMELT_CYCLE_TICKS, SMELT_FIRST_CYCLE_TICKS)
        active[player] = Active(Mode.SMELT, locId, tile, due, smelt = chosen)
        ActionSlot.claim(player, SLOT)
        actionsStarted++
        if (due == tickCount + SMELT_FIRST_CYCLE_TICKS &&
            SMELT_FIRST_CYCLE_TICKS == SMELT_ANIMATION_LEAD_TICKS
        ) animate(player, SMELT_ANIMATION)
        logger.info {
            "smithing: ${player.name} smelting ${chosen.barName} at loc $locId " +
                "(level ${chosen.level}, ${chosen.xpTenths / 10.0} xp, ${chosen.materials}); first bar at tick $due"
        }
        return Result(Outcome.STARTED, chosen.barId, detail = "first bar at tick $due")
    }

    private fun smeltCycle(player: ContentPlayer, a: Active): Result {
        val recipe = a.smelt ?: return Result(Outcome.STOPPED, detail = "no recipe")
        val container = containerSupplier(player)
        if (!canAfford(materialView(player, container), recipe.materials))
            return Result(Outcome.NEED_MATERIALS, recipe.barId, detail = "out of materials")

        val (taken, short) = takeMaterials(player, container, recipe.materials)
        if (short != null)
            return Result(Outcome.NEED_MATERIALS, recipe.barId, detail = "${short.name} short")
        val add = container.add(recipe.barId, 1)
        if (add.added < 1) {
            refundMaterials(player, container, taken)
            return Result(Outcome.NO_ROOM, recipe.barId, detail = "no room for ${recipe.barName}")
        }
        inventoryResend(player)
        payXp(player, recipe.xpTenths)
        return Result(Outcome.SMELTED, recipe.barId, recipe.xpTenths)
    }

    fun heat(player: ContentPlayer, locId: Int, tile: TileLocation): Result {
        if (!enabled) return Result(Outcome.DISABLED, detail = "-Dopennxt.experiment.smithing=false")
        val existing = projects[player]
        if (existing == null) {
            val created = createProject(player, atAnvil = false)
            if (!created.ok) return created
        }
        active[player] = Active(Mode.HEAT, locId, tile, tickCount + 1)
        ActionSlot.claim(player, SLOT)
        actionsStarted++
        return Result(Outcome.STARTED, projects[player]?.recipe?.productId, detail = "heating at loc $locId")
    }

    private fun createProject(player: ContentPlayer, atAnvil: Boolean): Result {
        val container = containerSupplier(player)
        val view = materialView(player, container)
        val level = levelSupplier(player, Stat.SMITHING)
        val recipe = selectionOf(player)?.let { smithRecipes[it] }
            ?: (if (autoSelect) smithChoice(view, level) else null)
            ?: return Result(Outcome.NO_RECIPE, detail = "no smith recipe chosen and none is affordable")
        if (level < recipe.level)
            return Result(Outcome.NEED_LEVEL, recipe.productId, detail = "Smithing $level < ${recipe.level}")
        if (!hasTool(view, recipe.toolId))
            return Result(Outcome.NEED_TOOL, recipe.productId, detail = "no ${recipe.toolId}")
        if (!canAfford(view, recipe.materials))
            return Result(Outcome.NEED_MATERIALS, recipe.productId, detail = recipe.materials.toString())

        val (taken, short) = takeMaterials(player, container, recipe.materials)
        if (short != null)
            return Result(Outcome.NEED_MATERIALS, recipe.productId, detail = "${short.name} short")
        if (container.add(UNFINISHED_ITEM, 1).added < 1) {
            refundMaterials(player, container, taken)
            return Result(Outcome.NO_ROOM, recipe.productId, detail = "no room for the unfinished item")
        }
        projects[player] = Project(recipe)
        inventoryResend(player)
        val msg = if (atAnvil) createAtAnvilMessage(recipe.productName) else createAtForgeMessage(recipe.productName)
        say(player, msg)
        logger.info {
            "smithing: ${player.name} created an unfinished ${recipe.productName} " +
                "(${recipe.progressTotal} progress, ${recipe.xpTenths / 10.0} xp, ${recipe.materials})"
        }
        return Result(Outcome.STARTED, recipe.productId, message = msg)
    }

    private fun heatCycle(player: ContentPlayer): Result {
        val project = projects[player] ?: return Result(Outcome.NEED_PROJECT)
        val max = heatMax(levelSupplier(player, Stat.SMITHING))
        if (project.heat >= max) return Result(Outcome.FULL_HEAT, project.recipe.productId)
        project.heat = (project.heat + HEAT_GAIN_PER_TICK).coerceAtMost(max)
        animate(player, HEAT_ANIMATION)
        if (project.heat >= max) {
            val msg = fullHeatMessage(project.recipe.productName)
            say(player, msg)
            return Result(Outcome.FULL_HEAT, project.recipe.productId, message = msg)
        }
        return Result(Outcome.HEATED, project.recipe.productId)
    }

    fun smith(player: ContentPlayer, locId: Int, tile: TileLocation): Result {
        if (!enabled) return Result(Outcome.DISABLED, detail = "-Dopennxt.experiment.smithing=false")
        if (projects[player] == null) {
            val created = createProject(player, atAnvil = true)
            if (!created.ok) return created
        }
        val container = containerSupplier(player)
        if (!container.contains(UNFINISHED_ITEM)) {
            projects.remove(player)
            return Result(Outcome.NEED_PROJECT, detail = "the unfinished item is no longer held")
        }
        val due = dueTick(player, SMITH_CYCLE_TICKS, SMITH_FIRST_CYCLE_TICKS)
        active[player] = Active(Mode.SMITH, locId, tile, due)
        ActionSlot.claim(player, SLOT)
        actionsStarted++
        logger.info { "smithing: ${player.name} working ${projects[player]?.recipe?.productName} at loc $locId; first swing at tick $due" }
        return Result(Outcome.STARTED, projects[player]?.recipe?.productId, detail = "first swing at tick $due")
    }

    private fun smithCycle(player: ContentPlayer, a: Active): Result {
        val project = projects[player] ?: return Result(Outcome.NEED_PROJECT)
        val container = containerSupplier(player)
        if (!container.contains(UNFINISHED_ITEM)) {
            projects.remove(player)
            return Result(Outcome.NEED_PROJECT, detail = "the unfinished item is no longer held")
        }
        val recipe = project.recipe
        val max = heatMax(levelSupplier(player, Stat.SMITHING))
        val stage = heatStage(project.heat, max)
        project.stage = stage

        val step = progressPerSwing(stage).coerceAtMost(project.progressLeft)
        project.progress += step
        val finishing = project.progress >= recipe.progressTotal
        val xp = if (finishing) project.xpLeftTenths
        else xpTenthsPerSwing(recipe, stage).coerceAtMost(project.xpLeftTenths)
        project.xpPaidTenths += xp
        val heatBefore = project.heat
        project.heat = (project.heat - HEAT_PER_SWING).coerceAtLeast(0)

        animate(player, SMITH_ANIMATION)

        if (finishing) {
            val slot = container.slotOf(UNFINISHED_ITEM)
            if (slot >= 0) container.removeSlot(slot)
            val add = container.add(recipe.productId, 1)
            if (add.added < 1) {
                container.add(UNFINISHED_ITEM, 1)
                project.progress -= step
                project.xpPaidTenths -= xp
                project.heat = heatBefore
                return Result(Outcome.NO_ROOM, recipe.productId, detail = "no room for ${recipe.productName}")
            }
            projects.remove(player)
            clearSelection(player)
            inventoryResend(player)
            payXp(player, xp)
            val msg = finishMessage(recipe.productName)
            say(player, msg)
            return Result(Outcome.FINISHED, recipe.productId, xp, msg)
        }

        inventoryResend(player)
        payXp(player, xp)

        val stageAfter = heatStage(project.heat, max)
        val msg = if (stageAfter > stage) when (stageAfter) {
            1 -> COOLED_SLIGHTLY
            2 -> COOLED_SIGNIFICANTLY
            else -> RAN_OUT_OF_HEAT
        } else null
        if (msg != null) say(player, msg)
        return Result(Outcome.SWUNG, recipe.productId, xp, msg)
    }

    fun tick(): Int {
        tickCount++
        if (active.isEmpty()) return 0
        var paid = 0
        val snapshot = synchronized(active) { ArrayList(active.entries).map { it.key to it.value } }
        for ((player, a) in snapshot) {
            var advanced = false
            try {
                if (!onlineCheck(player)) { stopFor(player, "no longer online"); continue }
                val here = locationSupplier(player)
                if (stopOnMove &&
                    (here.x != a.startTile.x || here.y != a.startTile.y || here.plane != a.startTile.plane)
                ) {
                    stopFor(player, "moved from (${a.startTile.x},${a.startTile.y}) to (${here.x},${here.y})")
                    continue
                }
                when (a.mode) {
                    Mode.HEAT -> {
                        val r = heatCycle(player)
                        a.cycles++
                        advanced = true
                        when (r.outcome) {
                            Outcome.HEATED -> paid++
                            Outcome.FULL_HEAT -> { paid++; stopFor(player, "at full heat") }
                            else -> stopFor(player, "heat refused: ${r.outcome} ${r.detail}")
                        }
                    }
                    Mode.SMELT -> {
                        if (tickCount < a.nextTick) continue
                        val r = smeltCycle(player, a)
                        lastCycle[player] = tickCount
                        a.cycles++
                        if (r.outcome == Outcome.SMELTED) {
                            paid++; cyclesPaid++
                            a.nextTick = tickCount + SMELT_CYCLE_TICKS
                            advanced = true
                            if (!canAfford(materialView(player, containerSupplier(player)), a.smelt!!.materials)) {
                                say(player, DEPOSIT_BARS)
                                stopFor(player, "out of materials for ${a.smelt.barName}")
                            } else {
                                animate(player, SMELT_ANIMATION)
                            }
                        } else { stopFor(player, "smelt refused: ${r.outcome} ${r.detail}"); advanced = true }
                    }
                    Mode.SMITH -> {
                        if (tickCount < a.nextTick) continue
                        val r = smithCycle(player, a)
                        lastCycle[player] = tickCount
                        a.cycles++
                        advanced = true
                        when (r.outcome) {
                            Outcome.SWUNG -> { paid++; cyclesPaid++; a.nextTick = tickCount + SMITH_CYCLE_TICKS }
                            Outcome.FINISHED -> { paid++; cyclesPaid++; stopFor(player, "finished ${r.itemId}") }
                            else -> stopFor(player, "swing refused: ${r.outcome} ${r.detail}")
                        }
                    }
                }
            } catch (t: Throwable) {
                containedFailures++
                logger.error(t) {
                    "smithing: ${player.name}'s ${a.mode} cycle failed"
                }
            } finally {
                if (!advanced && tickCount >= a.nextTick) {
                    a.nextTick = tickCount + when (a.mode) {
                        Mode.SMELT -> SMELT_CYCLE_TICKS
                        Mode.SMITH -> SMITH_CYCLE_TICKS
                        Mode.HEAT -> SMITH_CYCLE_TICKS
                    }
                }
            }
        }
        return paid
    }

    fun install(): Int {
        if (!enabled) {
            logger.warn { "smithing: disabled by -Dopennxt.experiment.smithing=false; nothing is bound" }
            return 0
        }
        var bound = 0
        bound += runCatching {
            com.opennxt.content.ContentRegistry.onLocAction(SMELT_ACTION) { ctx ->
                if (panelOpener?.invoke(ctx) == true) PANEL_OPENED else smelt(ctx.player, ctx.locId, locationSupplier(ctx.player))
            }
        }.getOrElse { logger.warn(it) { "smithing: '$SMELT_ACTION' is not bindable" }; 0 }
        bound += runCatching {
            com.opennxt.content.ContentRegistry.onLocAction(HEAT_ACTION) { ctx ->
                if (panelOpener?.invoke(ctx) == true) PANEL_OPENED else heat(ctx.player, ctx.locId, locationSupplier(ctx.player))
            }
        }.getOrElse { logger.warn(it) { "smithing: '$HEAT_ACTION' is not bindable" }; 0 }
        bound += runCatching {
            com.opennxt.content.ContentRegistry.onLocAction(SMITH_ACTION) { ctx ->
                if (panelOpener?.invoke(ctx) == true) PANEL_OPENED else smith(ctx.player, ctx.locId, locationSupplier(ctx.player))
            }
        }.getOrElse { logger.warn(it) { "smithing: '$SMITH_ACTION' is not bindable" }; 0 }
        bound += runCatching {
            com.opennxt.content.ContentRegistry.onLocAction(OPEN_INTERFACE_ACTION) { ctx ->
                if (panelOpener?.invoke(ctx) == true) PANEL_OPENED else PANEL_NOT_OPENED
            }
        }.getOrElse { logger.warn(it) { "smithing: '$OPEN_INTERFACE_ACTION' is not bindable" }; 0 }
        logger.info {
            "smithing: bound $bound loc id(s) across '$SMELT_ACTION'/'$HEAT_ACTION'/'$SMITH_ACTION'/'$OPEN_INTERFACE_ACTION'; " +
                "${smeltRecipes.size} smelt recipes, ${smithRecipes.size} smith recipes"
        }
        return bound
    }
}

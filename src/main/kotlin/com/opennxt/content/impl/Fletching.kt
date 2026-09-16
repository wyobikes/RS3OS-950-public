package com.opennxt.content.impl

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.opennxt.Constants
import com.opennxt.api.stat.Stat
import com.opennxt.content.ActionSlot
import com.opennxt.content.ContentPlayer
import com.opennxt.model.items.ItemContainer
import com.opennxt.resources.sqlite.CacheEnums
import com.opennxt.resources.sqlite.RsDatabase
import mu.KotlinLogging
import java.nio.file.Files
import java.util.Collections
import java.util.WeakHashMap

object Fletching {
    private val logger = KotlinLogging.logger { }

    val enabled: Boolean get() = System.getProperty("opennxt.experiment.fletching") != "false"

    const val CRAFT_ACTION = "Craft"

    const val LOGS_ITEM = 1511

    const val SHAFT_ITEM = 52

    const val HEADLESS_SHAFT_ITEM = 53

    const val SHAFT_COUNT = 15

    const val LOGS_XP_TENTHS = 50

    val STAT: Stat = Stat.FLETCHING

    const val FLETCH_MESSAGE = "You carefully cut the wood into 15 shafts."

    const val MESSAGE_TYPE = 109

    val CYCLE_TICKS: Int = System.getProperty("opennxt.fletching.cycleTicks")?.toIntOrNull()?.coerceIn(1, 100) ?: 3

    val FIRST_CYCLE_TICKS: Int = System.getProperty("opennxt.fletching.firstCycle")?.toIntOrNull()?.coerceIn(1, 100) ?: 3

    val CUT_ANIMATION: IntArray = intArrayOf(24938, 24938, 24938, 24938)

    const val CUT_ANIMATION_DELAY = 0

    val STOP_ANIMATION: IntArray = intArrayOf(-1, -1, -1, -1)

    const val STOP_ANIMATION_DELAY = 20

    const val XP_SKILL = "Fletching"

    data class Input(val itemId: Int, val name: String, val perBatch: Int)

    data class Product(
        val category: String,
        val name: String,
        val itemId: Int,
        val level: Int,
        val xpTenths: Int,
        val count: Int,
        val ticks: Int,
        val source: String,
        val inputs: List<Input> = emptyList(),
        val gridSlot: Int = -1,
        val categoryIndex: Int = -1
    )

    data class Recipe(
        val logId: Int,
        val logName: String,
        val products: List<Product>,
        val default: Product?
    )

    val KNOWN_RECIPE = Product(
        category = "Unfinished projectiles",
        name = "Shaft",
        itemId = SHAFT_ITEM,
        level = 1,
        xpTenths = LOGS_XP_TENTHS,
        count = SHAFT_COUNT,
        ticks = CYCLE_TICKS,
        source = "FIXED"
    )

    private val seedPath = Constants.DATA_PATH.resolve("seed").resolve("skill_xp.json")

    private val namesToIds: Map<String, Int> by lazy {
        if (!RsDatabase.available) emptyMap()
        else {
            val out = HashMap<String, Int>()
            RsDatabase.queryAll("SELECT id, name FROM items WHERE name IS NOT NULL") {
                it.getInt(1) to it.getString(2)
            }.forEach { (id, name) ->
                val key = name.lowercase()
                val prev = out[key]
                if (prev == null || id < prev) out[key] = id
            }
            out
        }
    }

    internal fun itemIdOf(name: String): Int? = namesToIds[name.lowercase()]

    fun craftableItemIds(): List<Int> = ItemActions.idsWithAction(CRAFT_ACTION)

    fun craftableItemCount(): Int = craftableItemIds().size

    fun isCraftable(itemId: Int): Boolean = ItemActions.slotsWithAction(itemId, CRAFT_ACTION).isNotEmpty()

    @Volatile
    private var recipeTable: Map<Int, Recipe>? = null

    val recipes: Map<Int, Recipe>
        get() = recipeTable ?: buildRecipes().also { recipeTable = it }

    internal fun invalidateRecipes() {
        recipeTable = null
        joinStats = JoinStats(0, 0, 0, 0, 0)
        panelTable = null
        panelRecipeMemo.clear()
        panelStatsValue = PanelStats(0, 0, 0, 0, 0, 0)
    }

    class JoinStats(
        val singleMaterialRows: Int,
        val rejectedNotCraftable: Int,
        val rejectedNoTicks: Int,
        val rejectedUnknownItem: Int,
        val accepted: Int
    )

    @Volatile
    private var joinStats: JoinStats = JoinStats(0, 0, 0, 0, 0)

    fun joinStats(): JoinStats { recipes; return joinStats }

    private fun buildRecipes(): Map<Int, Recipe> {
        if (!SkillXpTable.enabled) {
            logger.warn {
                "fletching: xp table disabled (-Dopennxt.seed.skillxp=off); only Logs -> Shaft is available"
            }
            return fixedOnlyTable()
        }
        if (!Files.exists(seedPath)) {
            logger.warn { "fletching: $seedPath not found; only Logs -> Shaft is available" }
            return fixedOnlyTable()
        }
        val root = runCatching {
            JsonParser().parse(Files.newBufferedReader(seedPath)).asJsonObject
        }.getOrElse {
            logger.error(it) { "fletching: could not read $seedPath; only Logs -> Shaft is available" }
            return fixedOnlyTable()
        }
        val categories = runCatching {
            root.getAsJsonObject("skills").getAsJsonObject(XP_SKILL).getAsJsonObject("categories")
        }.getOrNull()
        if (categories == null) {
            logger.error { "fletching: $seedPath has no $XP_SKILL categories; only Logs -> Shaft is available" }
            return fixedOnlyTable()
        }

        var single = 0
        var notCraftable = 0
        var noTicks = 0
        var unknown = 0
        val byMaterial = LinkedHashMap<Int, MutableList<Product>>()
        val materialNames = HashMap<Int, String>()

        for ((category, arr) in categories.entrySet()) {
            for (el in arr.asJsonArray) {
                val row = el as? JsonObject ?: continue
                val material = row.getAsJsonArray("material") ?: continue
                if (material.size() != 2) continue
                val quantity = runCatching { material[0].asDouble }.getOrNull() ?: continue
                if (quantity != 1.0) continue
                val materialName = runCatching { material[1].asString }.getOrNull() ?: continue
                single++
                val materialId = itemIdOf(materialName)
                if (materialId == null || !isCraftable(materialId)) { notCraftable++; continue }
                val ticks = intOrNull(row, "ticks")
                if (ticks == null) { noTicks++; continue }
                val productName = runCatching { row.get("name").asString }.getOrNull()
                val productId = productName?.let { itemIdOf(it) }
                if (productName == null || productId == null) { unknown++; continue }
                val level = intOrNull(row, "level") ?: 1
                val xpTenths = Math.round((runCatching { row.get("xp").asDouble }.getOrNull() ?: 0.0) * 10.0).toInt()
                val count = intOrNull(row, "multiplier") ?: 1
                byMaterial.getOrPut(materialId) { ArrayList() }.add(
                    Product(category, productName, productId, level, xpTenths, count, ticks,
                        "TABLE ($XP_SKILL, $category)")
                )
                materialNames[materialId] = materialName
            }
        }

        val out = LinkedHashMap<Int, Recipe>()
        for ((materialId, products) in byMaterial.entries.sortedBy { it.key }) {
            val sorted = products.sortedWith(compareBy({ it.level }, { it.itemId }))
            val name = Skilling.itemNameOf(materialId) ?: materialNames[materialId] ?: "item $materialId"
            out[materialId] = Recipe(materialId, name, sorted, defaultProductOf(materialId, sorted))
        }
        out[LOGS_ITEM]?.let { logs ->
            val replaced = logs.products.map { if (it.itemId == SHAFT_ITEM) KNOWN_RECIPE else it }
            out[LOGS_ITEM] = Recipe(logs.logId, logs.logName, replaced, defaultProductOf(LOGS_ITEM, replaced))
        }
        joinStats = JoinStats(single, notCraftable, noTicks, unknown, out.values.sumOf { it.products.size })
        logger.info {
            "fletching: ${out.size} material(s) with ${out.values.sumOf { it.products.size }} product row(s); " +
                "${out.values.count { it.default != null }} with a default product; skipped $notCraftable not craftable, " +
                "$noTicks without ticks, $unknown unknown of $single rows"
        }
        return out
    }

    private fun fixedOnlyTable(): Map<Int, Recipe> {
        joinStats = JoinStats(0, 0, 0, 0, 1)
        val name = Skilling.itemNameOf(LOGS_ITEM) ?: "Logs"
        return mapOf(LOGS_ITEM to Recipe(LOGS_ITEM, name, listOf(KNOWN_RECIPE), KNOWN_RECIPE))
    }

    internal fun defaultProductOf(materialId: Int, products: List<Product>): Product? {
        products.filter { it.itemId == SHAFT_ITEM }.minByOrNull { it.level }?.let { return it }
        return products.filter { it.name.lowercase().contains("shaft") }
            .minWithOrNull(compareBy({ it.level }, { it.itemId }))
            ?: run {
                logger.debug { "fletching: material $materialId has no default product" }
                null
            }
    }

    private fun intOrNull(o: JsonObject?, key: String): Int? =
        o?.get(key)?.takeIf { !it.isJsonNull }?.asInt

    val KNOWN_PANELS: Map<Int, Pair<Int, Int>> = linkedMapOf(
        LOGS_ITEM to (6939 to 6940),
        SHAFT_ITEM to (6943 to 6944),
        HEADLESS_SHAFT_ITEM to (6945 to 6946)
    )

    data class PanelCategory(
        val index: Int,
        val name: String,
        val productEnum: Int,
        val entryCount: Int,
        val materialId: Int,
        val materialName: String,
        val products: List<Product>
    )

    data class Panel(
        val materialId: Int,
        val materialName: String,
        val action: String,
        val categoryEnum: Int,
        val nameEnum: Int,
        val defaultIndex: Int,
        val categories: List<PanelCategory>,
        val fixedValue: Boolean
    ) {
        val products: List<Product> get() = categories.flatMap { it.products }
        fun categoryAt(index: Int): PanelCategory? = categories.getOrNull(index)
    }

    val panelInheritance: Boolean get() = System.getProperty("opennxt.fletching.panelInherit") != "false"

    const val GRID_STRIDE = 4

    fun gridSlotOf(productIndex: Int): Int = GRID_STRIDE * productIndex + 1

    fun productIndexOf(gridSlot: Int): Int =
        if (gridSlot >= 1 && (gridSlot - 1) % GRID_STRIDE == 0) (gridSlot - 1) / GRID_STRIDE else -1

    @Volatile
    private var panelTable: Map<Int, Panel>? = null

    val panels: Map<Int, Panel>
        get() = panelTable ?: buildPanels().also { panelTable = it }

    class PanelStats(
        val panels: Int,
        val knownPanels: Int,
        val inheritedPanels: Int,
        val categories: Int,
        val categoriesWithOwner: Int,
        val products: Int
    )

    @Volatile
    private var panelStatsValue = PanelStats(0, 0, 0, 0, 0, 0)

    fun panelStats(): PanelStats { panels; return panelStatsValue }

    fun actionFor(itemId: Int): String? = ItemActions.actionAt(itemId, 0)

    fun actionOf(itemId: Int): String = panelFor(itemId)?.action ?: CRAFT_ACTION

    fun claims(action: String, itemId: Int): Boolean = action.equals(actionOf(itemId), ignoreCase = true)

    private class XpRow(
        val category: String,
        val name: String,
        val level: Int,
        val xpTenths: Int,
        val count: Int,
        val ticks: Int,
        val inputs: List<Input>
    )

    private fun readXpRows(): List<XpRow> {
        if (!SkillXpTable.enabled || !Files.exists(seedPath)) return emptyList()
        val categories = runCatching {
            JsonParser().parse(Files.newBufferedReader(seedPath)).asJsonObject
                .getAsJsonObject("skills").getAsJsonObject(XP_SKILL).getAsJsonObject("categories")
        }.getOrNull() ?: return emptyList()
        val out = ArrayList<XpRow>()
        for ((category, arr) in categories.entrySet()) {
            for (el in arr.asJsonArray) {
                val row = el as? JsonObject ?: continue
                val material = row.getAsJsonArray("material") ?: continue
                if (material.size() < 2 || material.size() % 2 != 0) continue
                if (material.size() > 4) continue
                val ticks = intOrNull(row, "ticks") ?: continue
                val name = runCatching { row.get("name").asString }.getOrNull() ?: continue
                val count = intOrNull(row, "multiplier") ?: 1
                val inputs = ArrayList<Input>()
                var bad = false
                var i = 0
                while (i + 1 < material.size()) {
                    val qty = runCatching { material[i].asDouble }.getOrNull()
                    val mName = runCatching { material[i + 1].asString }.getOrNull()
                    val mId = mName?.let { itemIdOf(it) }
                    if (qty == null || mName == null || mId == null || qty < 1.0 || qty != Math.floor(qty)) {
                        bad = true; break
                    }
                    inputs.add(Input(mId, mName, qty.toInt()))
                    i += 2
                }
                if (bad || inputs.isEmpty()) continue
                val xpTenths = Math.round((runCatching { row.get("xp").asDouble }.getOrNull() ?: 0.0) * 10.0).toInt()
                out.add(XpRow(category, name, intOrNull(row, "level") ?: 1, xpTenths, count, ticks, inputs))
            }
        }
        return out
    }

    private fun buildPanels(): Map<Int, Panel> {
        val refEntry = readXpRows()
        if (refEntry.isEmpty()) {
            logger.warn { "fletching: no xp table rows; make-X panels disabled" }
            panelStatsValue = PanelStats(0, 0, 0, 0, 0, 0)
            return emptyMap()
        }
        val byName = HashMap<String, MutableList<XpRow>>()
        for (r in refEntry) byName.getOrPut(r.name.lowercase()) { ArrayList() }.add(r)

        val out = LinkedHashMap<Int, Panel>()
        var categories = 0
        var withOwner = 0
        var products = 0

        for ((fixedMaterial, pair) in KNOWN_PANELS) {
            val action = actionFor(fixedMaterial) ?: continue
            val (categoryEnum, nameEnum) = pair
            val catValues = CacheEnums.entries(categoryEnum)
            val catNames = CacheEnums.entries(nameEnum)
            if (catValues.isEmpty()) {
                logger.warn { "fletching: category enum $categoryEnum is empty; no panel for item $fixedMaterial" }
                continue
            }
            val built = ArrayList<PanelCategory>()
            for ((position, entry) in catValues.withIndex()) {
                val index = entry.key.toIntOrNull() ?: position
                val productEnum = entry.value.toIntOrNull() ?: continue
                val name = catNames.getOrNull(position)?.value?.trim('"') ?: "category $index"
                val enumProducts = CacheEnums.entries(productEnum)
                categories++

                val votes = HashMap<Int, Int>()
                for (p in enumProducts) {
                    val pid = p.value.toIntOrNull() ?: continue
                    val pName = Skilling.itemNameOf(pid) ?: continue
                    for (row in byName[pName.lowercase()].orEmpty()) {
                        for (input in row.inputs) {
                            if (actionFor(input.itemId)?.equals(action, ignoreCase = true) != true) continue
                            votes[input.itemId] = (votes[input.itemId] ?: 0) + 1
                        }
                    }
                }
                val top = votes.values.maxOrNull()
                val winners = votes.filterValues { it == top }.keys
                val owner = if (top != null && winners.size == 1) winners.first() else -1

                val bound = ArrayList<Product>()
                if (owner > 0) {
                    withOwner++
                    for ((slotIndex, p) in enumProducts.withIndex()) {
                        val pid = p.value.toIntOrNull() ?: continue
                        val pName = Skilling.itemNameOf(pid) ?: continue
                        val hits = byName[pName.lowercase()].orEmpty()
                            .filter { row -> row.inputs.any { it.itemId == owner } }
                        if (hits.size != 1) continue
                        val row = hits.first()
                        bound.add(
                            Product(
                                category = name,
                                name = pName,
                                itemId = pid,
                                level = row.level,
                                xpTenths = row.xpTenths,
                                count = row.count,
                                ticks = row.ticks,
                                source = "TABLE x CACHE (enum $productEnum slot $slotIndex, $XP_SKILL, ${row.category})",
                                inputs = row.inputs,
                                gridSlot = gridSlotOf(p.key.toIntOrNull() ?: slotIndex),
                                categoryIndex = index
                            )
                        )
                    }
                }
                products += bound.size
                built.add(
                    PanelCategory(
                        index = index,
                        name = name,
                        productEnum = productEnum,
                        entryCount = enumProducts.size,
                        materialId = owner,
                        materialName = if (owner > 0) (Skilling.itemNameOf(owner) ?: "item $owner") else "",
                        products = bound
                    )
                )
            }

            for (category in built) {
                if (category.materialId <= 0 || category.products.isEmpty()) continue
                val isFixed = category.materialId == fixedMaterial
                if (!isFixed && !panelInheritance) continue
                if (out.containsKey(category.materialId)) continue
                val ownAction = actionFor(category.materialId) ?: continue
                out[category.materialId] = Panel(
                    materialId = category.materialId,
                    materialName = category.materialName,
                    action = ownAction,
                    categoryEnum = categoryEnum,
                    nameEnum = nameEnum,
                    defaultIndex = category.index,
                    categories = built,
                    fixedValue = isFixed
                )
            }
        }

        panelStatsValue = PanelStats(
            panels = out.size,
            knownPanels = out.values.count { it.fixedValue },
            inheritedPanels = out.values.count { !it.fixedValue },
            categories = categories,
            categoriesWithOwner = withOwner,
            products = products
        )
        logger.info {
            "fletching: ${out.size} make-X panel(s) (${out.values.count { it.fixedValue }} known, " +
                "${out.values.count { !it.fixedValue }} inherited), $categories categories ($withOwner with a material), " +
                "$products products"
        }
        return out
    }

    fun panelFor(materialId: Int): Panel? = panels[materialId]

    fun recipeFor(materialId: Int): Recipe? = recipes[materialId] ?: panelRecipe(materialId)

    private val panelRecipeMemo = java.util.concurrent.ConcurrentHashMap<Int, Recipe>()

    private fun panelRecipe(materialId: Int): Recipe? {
        val panel = panels[materialId] ?: return null
        return panelRecipeMemo.getOrPut(materialId) {
            val all = panel.products
            Recipe(materialId, panel.materialName, all, defaultPanelProductOf(panel))
        }
    }

    fun defaultPanelProductOf(panel: Panel): Product? =
        panel.categoryAt(panel.defaultIndex)?.products?.minByOrNull { it.gridSlot }
            ?: panel.products.minByOrNull { it.gridSlot }

    fun messageFor(product: Product, count: Int): String {
        val noun = product.name.lowercase()
        val plural = if (count == 1 || noun.endsWith("s")) noun else "${noun}s"
        return "You carefully cut the wood into $count $plural."
    }

    @Volatile
    var containerSupplier: (ContentPlayer) -> ItemContainer = { it.inventory }

    @Volatile
    var levelSupplier: (ContentPlayer, Stat) -> Int = { _, _ -> 1 }

    @Volatile
    var xpSink: (ContentPlayer, Stat, Double) -> Unit = { _, _, _ -> }

    @Volatile
    var messageSink: (ContentPlayer, Int, String) -> Unit = { _, _, _ -> }

    @Volatile
    var animationSink: (ContentPlayer, IntArray, Int) -> Unit = { _, _, _ -> }

    @Volatile
    var inventoryResend: (ContentPlayer) -> Boolean = { false }

    @Volatile
    var onlineCheck: (ContentPlayer) -> Boolean = { true }

    @Volatile
    var panelOpener: (ContentPlayer, Recipe, Product, Int) -> Boolean = { _, _, _, _ -> false }

    fun resetSeams() {
        containerSupplier = { it.inventory }
        levelSupplier = { _, _ -> 1 }
        xpSink = { _, _, _ -> }
        messageSink = { _, _, _ -> }
        animationSink = { _, _, _ -> }
        inventoryResend = { false }
        onlineCheck = { true }
        panelOpener = { _, _, _, _ -> false }
    }

    enum class Outcome {
        STARTED,

        MADE,

        NOT_MINE,

        NOT_CRAFTABLE,

        NO_RECIPE,

        PANEL,

        NO_PRODUCT,

        STALE_CLICK,

        NEED_LEVEL,

        OUT_OF_LOGS,

        FULL,
    }

    data class Result(
        val outcome: Outcome,
        val recipe: Recipe? = null,
        val product: Product? = null,
        val count: Int = 0,
        val xpTenths: Int = 0,
        val message: String? = null,
        val detail: String = ""
    )

    data class Active(
        val recipe: Recipe,
        val product: Product,
        val startSlot: Int,
        var nextTick: Long,
        var cycles: Int = 0,
        var made: Int = 0,
        val limit: Int = Int.MAX_VALUE
    )

    private val active: MutableMap<ContentPlayer, Active> =
        Collections.synchronizedMap(WeakHashMap<ContentPlayer, Active>())

    private val lastCycle: MutableMap<ContentPlayer, Long> =
        Collections.synchronizedMap(WeakHashMap<ContentPlayer, Long>())

    private val awarded: MutableMap<ContentPlayer, Int> = Collections.synchronizedMap(WeakHashMap())

    @Volatile private var tickCount: Long = 0
    @Volatile private var cyclesPaid = 0
    @Volatile private var actionsStarted = 0
    @Volatile private var actionsStopped = 0
    @Volatile private var animationsSent = 0
    @Volatile private var messagesSent = 0

    @Volatile private var containedFailures = 0

    fun ticks(): Long = tickCount
    fun activeCount(): Int = active.size
    fun activeFor(player: ContentPlayer): Active? = active[player]
    fun lastCycleOf(player: ContentPlayer): Long? = lastCycle[player]
    fun cyclesPaid(): Int = cyclesPaid
    fun actionsStarted(): Int = actionsStarted
    fun actionsStopped(): Int = actionsStopped
    fun animationsSent(): Int = animationsSent
    fun messagesSent(): Int = messagesSent
    fun containedFailures(): Int = containedFailures
    fun xpTenthsAwarded(player: ContentPlayer): Int = synchronized(awarded) { awarded[player] ?: 0 }

    internal fun clearActions() {
        active.clear(); lastCycle.clear(); synchronized(awarded) { awarded.clear() }
        tickCount = 0
        cyclesPaid = 0; actionsStarted = 0; actionsStopped = 0
        animationsSent = 0; messagesSent = 0; containedFailures = 0
    }

    internal object SLOT : ActionSlot.Owner {
        override val actionName = "fletching"
        override fun cancelSlot(player: ContentPlayer, why: String) = stopFor(player, why)
    }

    fun stopFor(player: ContentPlayer, why: String) {
        val was = active.remove(player)
        ActionSlot.release(player, SLOT)
        if (was != null) {
            actionsStopped++
            logger.info { "fletching: ${player.name} stopped after ${was.cycles} cycle(s), ${was.made} cut - $why" }
        }
    }

    private fun animate(player: ContentPlayer, ids: IntArray, delay: Int) {
        runCatching { animationSink(player, ids, delay) }; animationsSent++
    }

    private fun say(player: ContentPlayer, message: String) {
        runCatching { messageSink(player, MESSAGE_TYPE, message) }; messagesSent++
    }

    fun craft(player: ContentPlayer, itemId: Int, itemName: String?, slot: Int, action: String): Result {
        if (!enabled) return Result(Outcome.NOT_MINE, detail = "-Dopennxt.experiment.fletching=false")
        val expected = actionOf(itemId)
        if (!action.equals(expected, ignoreCase = true))
            return Result(Outcome.NOT_MINE, detail = "action '$action' is not item $itemId's fletching row ($expected)")
        if (ItemActions.slotsWithAction(itemId, action).isEmpty()) {
            logger.info { "fletching: ${player.name}'s item $itemId has no '$action' option" }
            return Result(Outcome.NOT_CRAFTABLE, detail = "item $itemId has no $action row")
        }

        val panel = panelFor(itemId)
        val recipe = recipeFor(itemId) ?: run {
            logger.info {
                "fletching: ${player.name} clicked '$action' on ${itemName ?: "item $itemId"}, no recipe"
            }
            return Result(Outcome.NO_RECIPE, detail = "no fletching recipe for item $itemId")
        }
        val product = recipe.default ?: panel?.let { defaultPanelProductOf(it) } ?: run {
            logger.info {
                "fletching: ${player.name} clicked '$action' on ${recipe.logName}, no default among " +
                    "${recipe.products.size} product(s)"
            }
            return Result(Outcome.NO_PRODUCT, recipe, detail = "no default product for ${recipe.logName}")
        }

        val container = containerSupplier(player)
        if (slot < 0 || slot >= container.size) {
            logger.info { "fletching: ${player.name}: slot $slot is outside the backpack" }
            return Result(Outcome.STALE_CLICK, recipe, product, detail = "slot $slot is outside the backpack")
        }
        val held = container[slot]
        if (held == null || held.id != itemId) {
            logger.info {
                "fletching: ${player.name}'s '$action' ignored, slot $slot holds ${held?.id ?: "nothing"}, not $itemId"
            }
            return Result(Outcome.STALE_CLICK, recipe, product, detail = "slot $slot holds ${held?.id ?: "nothing"}")
        }

        val level = levelSupplier(player, STAT)
        if (level < product.level) {
            say(player, "You need a Fletching level of ${product.level} to make ${product.name.lowercase()}.")
            logger.info {
                "fletching: ${player.name} level $level < ${product.level} for ${product.name}"
            }
            return Result(Outcome.NEED_LEVEL, recipe, product, detail = "level $level < ${product.level}")
        }

        if (runCatching { panelOpener(player, recipe, product, slot) }
                .onFailure { logger.error(it) { "fletching: failed to open make-X panel for ${player.name}; starting directly" } }
                .getOrDefault(false)
        ) {
            logger.info {
                "fletching: ${player.name} clicked '$action' on ${recipe.logName}, make-X panel opened"
            }
            return Result(Outcome.PANEL, recipe, product, product.count, detail = "make-X panel opened")
        }

        return start(player, recipe, product, slot, Int.MAX_VALUE, "no make-X panel")
    }

    fun startFromPanel(
        player: ContentPlayer,
        materialId: Int,
        productItemId: Int,
        slot: Int,
        count: Int
    ): Result {
        if (!enabled) return Result(Outcome.NOT_MINE, detail = "-Dopennxt.experiment.fletching=false")
        val recipe = recipeFor(materialId)
            ?: return Result(Outcome.NO_RECIPE, detail = "no fletching recipe for item $materialId")
        val product = recipe.products.firstOrNull { it.itemId == productItemId }
            ?: recipe.default?.takeIf { it.itemId == productItemId }
            ?: panelFor(materialId)?.products?.firstOrNull { it.itemId == productItemId }
            ?: return Result(
                Outcome.NO_PRODUCT, recipe,
                detail = "item $productItemId is not a product of ${recipe.logName}"
            )
        val container = containerSupplier(player)
        for (input in inputsOf(recipe, product)) {
            if (container.count(input.itemId) <= 0L)
                return Result(
                    Outcome.OUT_OF_LOGS, recipe, product,
                    detail = "${input.name} (item ${input.itemId}) is gone"
                )
        }
        val level = levelSupplier(player, STAT)
        if (level < product.level) {
            say(player, "You need a Fletching level of ${product.level} to make ${product.name.lowercase()}.")
            return Result(Outcome.NEED_LEVEL, recipe, product, detail = "level $level < ${product.level}")
        }
        val startSlot = slot.takeIf { it in 0 until container.size } ?: container.slotOf(materialId)
        return start(player, recipe, product, startSlot, count.coerceAtLeast(1), "make-X confirm")
    }

    private fun start(
        player: ContentPlayer,
        recipe: Recipe,
        product: Product,
        slot: Int,
        limit: Int,
        why: String
    ): Result {
        val last = lastCycle[player]
        val due = if (last != null && tickCount - last < CYCLE_TICKS) last + CYCLE_TICKS
        else tickCount + FIRST_CYCLE_TICKS
        active[player] = Active(recipe, product, slot, due, limit = limit)
        ActionSlot.claim(player, SLOT)
        actionsStarted++
        if (due == tickCount + FIRST_CYCLE_TICKS) animate(player, CUT_ANIMATION, CUT_ANIMATION_DELAY)
        logger.info {
            "fletching: ${player.name} started ${recipe.logName} -> ${product.count} x ${product.name} " +
                "(level ${product.level}, ${product.xpTenths / 10.0} xp), first cut at tick $due, $why, limit " +
                (if (limit == Int.MAX_VALUE) "none" else "$limit")
        }
        return Result(Outcome.STARTED, recipe, product, product.count, detail = "first cut at tick $due")
    }

    private fun cycle(player: ContentPlayer, a: Active): Result {
        val recipe = a.recipe
        val product = a.product
        val container = containerSupplier(player)
        val inputs = inputsOf(recipe, product)

        val level = levelSupplier(player, STAT)
        if (level < product.level)
            return Result(Outcome.NEED_LEVEL, recipe, product, detail = "Fletching $level < ${product.level}")

        val made = batchSizeFor(container, product, inputs)
        if (made <= 0) {
            val short = inputs.firstOrNull { container.count(it.itemId) <= 0L }
            return Result(
                Outcome.OUT_OF_LOGS, recipe, product,
                detail = "${short?.name ?: recipe.logName} is gone"
            )
        }

        val before = container.toArray()

        for (input in inputs) {
            var need = ceilDiv(input.perBatch * made, product.count)
            if (input.itemId == recipe.logId) {
                val clicked = a.startSlot.takeIf {
                    it in 0 until container.size && container[it]?.id == input.itemId
                }
                if (clicked != null) {
                    val held = container[clicked]!!
                    val take = minOf(need, held.amount)
                    val remainder = held.minus(take)
                    if (remainder == null) container.removeSlot(clicked) else container[clicked] = remainder
                    need -= take
                }
            }
            if (need > 0) {
                val removed = container.remove(input.itemId, need)
                if (removed.removed < need) {
                    for (i in 0 until container.size) container[i] = before[i]
                    return Result(
                        Outcome.OUT_OF_LOGS, recipe, product,
                        detail = "only ${removed.removed} of ${input.name} available; nothing consumed"
                    )
                }
            }
        }

        val add = container.add(product.itemId, made)
        if (add.added < made) {
            for (i in 0 until container.size) container[i] = before[i]
            return Result(
                Outcome.FULL, recipe, product,
                detail = "no room for $made x ${product.itemId}; ingredients restored"
            )
        }

        lineFor(product, made)?.let { say(player, it) }
        runCatching { inventoryResend(player) }
        val awardTenths =
            if (made >= product.count) product.xpTenths
            else Math.round(product.xpTenths.toDouble() * made / product.count).toInt()
        val paid = runCatching { xpSink(player, STAT, awardTenths / 10.0) }
            .onFailure { logger.warn(it) { "fletching: failed to award ${awardTenths / 10.0} xp to ${player.name}" } }
            .isSuccess
        if (paid) synchronized(awarded) { awarded[player] = (awarded[player] ?: 0) + awardTenths }
        a.made++
        return Result(Outcome.MADE, recipe, product, made, awardTenths, lineFor(product, made))
    }

    private fun ceilDiv(a: Int, b: Int): Int = if (b <= 0) 0 else (a + b - 1) / b

    fun inputsOf(recipe: Recipe, product: Product): List<Input> =
        if (product.inputs.isNotEmpty()) product.inputs
        else listOf(Input(recipe.logId, recipe.logName, 1))

    fun batchSizeFor(
        container: ItemContainer,
        product: Product,
        inputs: List<Input>
    ): Int {
        var n = product.count
        for (input in inputs) {
            if (input.perBatch <= 0) continue
            val have = container.count(input.itemId).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            val possible = (have.toLong() * product.count / input.perBatch).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            if (possible < n) n = possible
        }
        return n.coerceAtLeast(0)
    }

    fun cyclesAvailable(container: ItemContainer, recipe: Recipe, product: Product): Int {
        val inputs = inputsOf(recipe, product)
        var total = Int.MAX_VALUE
        for (input in inputs) {
            if (input.perBatch <= 0) continue
            val have = container.count(input.itemId).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            val possible = (have.toLong() * product.count / input.perBatch).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            if (possible < total) total = possible
        }
        if (total == Int.MAX_VALUE || total <= 0) return 0
        return ceilDiv(total, product.count.coerceAtLeast(1))
    }

    fun lineFor(product: Product, count: Int): String? = when {
        product.itemId == HEADLESS_SHAFT_ITEM -> "You attach feathers to $count shafts."
        product.itemId == SHAFT_ITEM || product.name.lowercase().contains("shaft") ->
            messageFor(product, count)
        else -> null
    }

    fun tick(): Int {
        tickCount++
        if (active.isEmpty()) return 0
        var paid = 0
        for ((player, a) in ArrayList(active.entries).map { it.key to it.value }) {
            var advanced = false
            try {
                if (!onlineCheck(player)) { stopFor(player, "no longer online"); continue }
                if (tickCount < a.nextTick) continue
                val r = cycle(player, a)
                lastCycle[player] = tickCount
                a.cycles++
                when (r.outcome) {
                    Outcome.MADE -> {
                        paid++; cyclesPaid++
                        a.nextTick = tickCount + CYCLE_TICKS
                        advanced = true
                        val container = runCatching { containerSupplier(player) }.getOrNull()
                        if (a.made >= a.limit) {
                            animate(player, STOP_ANIMATION, STOP_ANIMATION_DELAY)
                            stopFor(player, "the make-X count of ${a.limit} is made")
                        } else if (container == null ||
                            batchSizeFor(container, a.product, inputsOf(a.recipe, a.product)) <= 0
                        ) {
                            animate(player, STOP_ANIMATION, STOP_ANIMATION_DELAY)
                            stopFor(
                                player,
                                "out of ${inputsOf(a.recipe, a.product).firstOrNull { i ->
                                    container == null || container.count(i.itemId) <= 0L
                                }?.name ?: a.recipe.logName} after ${a.made} cycle(s)"
                            )
                        } else {
                            animate(player, CUT_ANIMATION, CUT_ANIMATION_DELAY)
                        }
                    }
                    else -> {
                        advanced = true
                        animate(player, STOP_ANIMATION, STOP_ANIMATION_DELAY)
                        stopFor(player, "cycle refused: ${r.outcome} ${r.detail}")
                    }
                }
            } catch (t: Throwable) {
                containedFailures++
                logger.error(t) {
                    "fletching: ${player.name}'s cut failed"
                }
            } finally {
                if (!advanced && tickCount >= a.nextTick) a.nextTick = tickCount + CYCLE_TICKS
            }
        }
        return paid
    }
}

package com.opennxt.content.skills

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.opennxt.Constants
import com.opennxt.api.stat.Stat
import com.opennxt.content.ActionSlot
import com.opennxt.content.ContentPlayer
import com.opennxt.content.ContentRegistry
import com.opennxt.content.LocContext
import com.opennxt.content.NpcContext
import com.opennxt.content.impl.Bury
import com.opennxt.content.impl.Obstacles
import com.opennxt.model.items.Item
import com.opennxt.model.world.TileLocation
import com.opennxt.resources.sqlite.RsDatabase
import mu.KotlinLogging
import java.nio.file.Files
import java.util.Collections
import java.util.IdentityHashMap

object SkillInteractions {
    private val logger = KotlinLogging.logger { }

    val enabled: Boolean get() = System.getProperty("opennxt.experiment.skillbones") != "false"

    const val PLACEHOLDER_XP = 5.0

    const val GATHER_CYCLE_TICKS = 4
    const val GATHER_FIRST_TICKS = 2

    const val MAX_GATHERS_PER_CLICK = 1_000

    const val NPC_RANGE = 2

    const val ARRIVE_TIMEOUT = 20

    const val AGILITY_COOLDOWN_TICKS = 4

    val chanceEnabled: Boolean get() = System.getProperty("opennxt.skillbones.chance") != "off"

    fun gatherChance(level: Int, req: Int): Double =
        if (!chanceEnabled) 1.0 else (0.6 + 0.02 * (level - req)).coerceIn(0.05, 1.0)

    var random: java.util.Random = java.util.Random(0x534B4C42L)

    @Volatile var npcAlive: (Int) -> Boolean = { true }
    @Volatile var npcTile: (Int) -> IntArray? = { null }
    @Volatile var messageByName: (String, String) -> Unit = { _, _ -> }
    @Volatile var xpByName: (String, Stat, Double) -> Boolean = { _, _, _ -> false }
    @Volatile var levelByName: (String, Stat) -> Int? = { _, _ -> null }
    @Volatile var tickHook: () -> Unit = { }

    fun resetSeams() {
        npcAlive = { true }
        npcTile = { null }
        messageByName = { _, _ -> }
        xpByName = { _, _, _ -> false }
        levelByName = { _, _ -> null }
        tickHook = { }
    }

    private fun msg(p: ContentPlayer, text: String) = ProductionActions.messageSink(p, 0, text)
    private fun level(p: ContentPlayer, s: Stat) = ProductionActions.levelSupplier(p, s)
    private fun xp(p: ContentPlayer, s: Stat, amount: Double) {
        if (amount > 0) runCatching { ProductionActions.xpSink(p, s, amount) }
            .onFailure { logger.error(it) { "skill bones ${p.name}: xp sink threw for ${s.name}" } }
    }
    private fun backpack(p: ContentPlayer) = ProductionActions.containerSupplier(p)

    private class Names(val byId: Map<Int, String>, val byLowerName: Map<String, Int>)

    private val names: Names by lazy {
        if (!RsDatabase.available) return@lazy Names(emptyMap(), emptyMap())
        val byId = HashMap<Int, String>()
        val byName = HashMap<String, Int>()
        RsDatabase.queryAll("SELECT id, name FROM items WHERE name IS NOT NULL ORDER BY id") { rs -> rs.getInt(1) to rs.getString(2) }
            .forEach { (id, n) -> byId[id] = n; byName.putIfAbsent(n.lowercase(), id) }
        Names(byId, byName)
    }

    fun itemName(id: Int): String? = names.byId[id]

    fun itemIdByName(name: String): Int? = names.byLowerName[name.lowercase()]

    data class XpRow(val skill: String, val category: String, val name: String, val level: Int?, val xp: Double, val product: String?, val level2: Int?)

    private val refEntry: Map<String, Map<String, List<XpRow>>> by lazy { loadRef() }

    private fun loadRef(): Map<String, Map<String, List<XpRow>>> {
        val path = Constants.DATA_PATH.resolve("seed").resolve("skill_xp.json")
        if (!Files.isRegularFile(path)) return emptyMap()
        val root = JsonParser().parse(Files.newBufferedReader(path)).asJsonObject.getAsJsonObject("skills") ?: return emptyMap()
        val out = LinkedHashMap<String, Map<String, List<XpRow>>>()
        for ((skill, v) in root.entrySet()) {
            val cats = LinkedHashMap<String, List<XpRow>>()
            for ((cat, arr) in (v.asJsonObject.getAsJsonObject("categories") ?: JsonObject()).entrySet()) {
                val rows = ArrayList<XpRow>()
                for (e in (arr as? JsonArray ?: continue)) {
                    val o = e as? JsonObject ?: continue
                    val name = o.get("name")?.takeIf { it.isJsonPrimitive }?.asString ?: continue
                    val xp = o.get("xp")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asDouble ?: continue
                    rows += XpRow(skill, cat, name,
                        o.get("level")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asDouble?.toInt(),
                        xp,
                        o.get("product")?.takeIf { it.isJsonPrimitive }?.asString,
                        o.get("level2")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asDouble?.toInt())
                }
                cats[cat] = rows
            }
            out[skill] = cats
        }
        return out
    }

    fun refRows(skill: String, category: String? = null): List<XpRow> {
        val cats = refEntry[skill] ?: return emptyList()
        return if (category != null) cats[category] ?: emptyList() else cats.values.flatten()
    }

    fun refRow(skill: String, name: String, category: String? = null): XpRow? =
        refRows(skill, category).firstOrNull { it.name.equals(name, ignoreCase = true) }

    data class Gather(
        val stat: Stat,
        val label: String,
        val level: Int,
        val xp: Double,
        val productId: Int?,
        val source: String,
        val consumes: Int? = null,
        val tool: Int? = null,
        val successes: Int = MAX_GATHERS_PER_CLICK
    )

    data class Active(val gather: Gather, var startTile: TileLocation?, val npcIndex: Int, var left: Int, val deadline: Long)

    private val active: MutableMap<ContentPlayer, Active> = Collections.synchronizedMap(IdentityHashMap())
    private val nextAt: MutableMap<ContentPlayer, Long> = Collections.synchronizedMap(java.util.WeakHashMap())
    private val lastAgilityAt: MutableMap<ContentPlayer, Long> = Collections.synchronizedMap(java.util.WeakHashMap())

    @Volatile private var clock = 0L
    @Volatile private var gathered = 0
    @Volatile private var interactions = 0

    fun gatheredCount(): Int = gathered
    fun interactionCount(): Int = interactions
    fun activeGather(player: ContentPlayer): Active? = active[player]
    internal fun resetState() {
        active.clear(); nextAt.clear(); lastAgilityAt.clear(); clock = 0; gathered = 0; interactions = 0; slayerTasks.clear()
    }

    private val owners = java.util.concurrent.ConcurrentHashMap<Stat, ActionSlot.Owner>()

    fun ownerFor(stat: Stat): ActionSlot.Owner = owners.computeIfAbsent(stat) { s ->
        object : ActionSlot.Owner {
            override val actionName: String = "gather:${s.name.lowercase()}"
            override fun cancelSlot(player: ContentPlayer, why: String) { stopGather(player, why) }
        }
    }

    fun stopGather(player: ContentPlayer, why: String): Boolean {
        val a = active.remove(player) ?: return false
        ActionSlot.release(player, ownerFor(a.gather.stat))
        logger.info { "skill bones ${player.name}: stopped ${a.gather.label} ($why)" }
        return true
    }

    fun cull(player: ContentPlayer, why: String) {
        stopGather(player, why)
        nextAt.remove(player)
        lastAgilityAt.remove(player)
    }

    fun startGather(player: ContentPlayer, g: Gather, npcIndex: Int = -1): String {
        interactions++
        if (level(player, g.stat) < g.level) {
            msg(player, "You need a ${SkillRecipes.refName(g.stat)} level of ${g.level} to do that.")
            return "level-too-low ${g.stat.name} ${g.level}"
        }
        if (g.tool != null && !ProductionActions.hasTool(player, g.tool)) {
            msg(player, "You need a ${(itemName(g.tool) ?: "tool").lowercase()} to do that.")
            return "no-tool ${g.tool}"
        }
        if (g.consumes != null && !backpack(player).contains(g.consumes)) {
            msg(player, "You need an empty ${(itemName(g.consumes) ?: "container").lowercase()} to do that.")
            return "nothing-to-consume ${g.consumes}"
        }
        if (g.productId != null && g.consumes == null && backpack(player).isFull() && !backpack(player).contains(g.productId)) {
            msg(player, "Your inventory is too full to hold any more.")
            return "no-space"
        }
        ActionSlot.claim(player, ownerFor(g.stat))
        val pending = npcIndex >= 0
        active[player] = Active(g, if (pending) null else ProductionActions.tileSupplier(player), npcIndex, g.successes, clock + ARRIVE_TIMEOUT)
        if (!pending) nextAt.merge(player, clock + GATHER_FIRST_TICKS) { old, new -> maxOf(old, new) }
        logger.info { "skill bones ${player.name}: gather ${g.label}${if (pending) " (pending arrival)" else ""} (${g.stat.name} ${g.level}, ${g.xp} xp, product ${g.productId}) [${g.source}]" }
        return "gathering ${g.label}"
    }

    fun tick() {
        clock++
        runCatching { tickHook() }
        if (active.isEmpty()) return
        val snapshot = synchronized(active) { active.entries.map { it.key to it.value } }
        for ((player, a) in snapshot) {
            try {
                if (active[player] !== a) continue
                val tile = ProductionActions.tileSupplier(player)
                if (a.npcIndex >= 0) {
                    if (!npcAlive(a.npcIndex)) { stopGather(player, "the target is gone"); continue }
                    val t = npcTile(a.npcIndex)
                    val near = t == null || (t[2] == tile.plane && maxOf(Math.abs(t[0] - tile.x), Math.abs(t[1] - tile.y)) <= NPC_RANGE)
                    if (a.startTile == null) {
                        if (!near) { if (clock > a.deadline) stopGather(player, "never arrived"); continue }
                        a.startTile = tile
                        nextAt.merge(player, clock + GATHER_FIRST_TICKS) { old, new -> maxOf(old, new) }
                        continue
                    }
                    if (!near) { stopGather(player, "the target moved away"); continue }
                }
                val start = a.startTile ?: continue
                if (tile.x != start.x || tile.y != start.y || tile.plane != start.plane) { stopGather(player, "moved"); continue }
                if ((nextAt[player] ?: 0L) > clock) continue
                nextAt[player] = clock + GATHER_CYCLE_TICKS
                attemptOnce(player, a)
            } catch (t: Throwable) {
                logger.error(t) { "skill bones ${player.name}: gather failed and was stopped" }
                runCatching { stopGather(player, "threw") }
            }
        }
    }

    private fun attemptOnce(player: ContentPlayer, a: Active) {
        val g = a.gather
        if (g.tool != null && !ProductionActions.hasTool(player, g.tool)) { stopGather(player, "tool gone"); return }
        if (random.nextDouble() >= gatherChance(level(player, g.stat), g.level)) return
        val c = backpack(player)
        val snapshot = c.toArray()
        if (g.consumes != null && c.remove(g.consumes, 1).removed != 1) {
            msg(player, "You need an empty ${(itemName(g.consumes) ?: "container").lowercase()} to do that.")
            stopGather(player, "nothing to consume")
            return
        }
        if (g.productId == null && g.consumes != null) ProductionActions.inventoryResend(player)
        if (g.productId != null) {
            if (c.add(g.productId, 1).added <= 0) {
                for (i in snapshot.indices) c[i] = snapshot[i]
                msg(player, "Your inventory is too full to hold any more.")
                stopGather(player, "backpack full")
                return
            }
            ProductionActions.inventoryResend(player)
            msg(player, "You get some ${(itemName(g.productId) ?: "resources").lowercase()}.")
        }
        gathered++
        xp(player, g.stat, g.xp)
        a.left--
        if (a.left <= 0) stopGather(player, "done")
    }

    fun onBackpackOption(player: ContentPlayer, action: String, itemId: Int, slot: Int): Boolean {
        if (!enabled) return false
        val c = backpack(player)
        val held = if (slot in 0 until c.size) c[slot] else null
        return when (action.lowercase()) {
            "scatter" -> {
                interactions++
                if (held == null || held.id != itemId) return true
                val name = itemName(itemId) ?: return true
                val row = refRow("Prayer", name, "Bones and Ashes")
                val left = held.amount - 1
                if (left > 0) c[slot] = Item(held.id, left) else c.removeSlot(slot)
                ProductionActions.inventoryResend(player)
                msg(player, "You scatter the ashes.")
                xp(player, Stat.PRAYER, row?.xp ?: PLACEHOLDER_XP)
                logger.info { "skill bones ${player.name}: scattered $name for ${row?.xp ?: PLACEHOLDER_XP} Prayer xp [${if (row != null) "TABLE Bones and Ashes" else "placeholder"}]" }
                true
            }
            "summon" -> { interactions++; msg(player, "Summoning familiars is not available on this server yet."); true }
            "disassemble" -> { interactions++; msg(player, "Invention is not available on this server yet - nothing was disassembled."); true }
            "lay" -> { interactions++; msg(player, "Hunter traps are not available on this server yet."); true }
            else -> false
        }
    }

    private fun info(text: String): (LocContext) -> Any? = { ctx -> interactions++; msg(ctx.player, text); "info" }

    private fun prayAt(text: String): (LocContext) -> Any? = { ctx ->
        runCatching { com.opennxt.content.impl.PrayerBook.rechargeByName(ctx.player.name, "altar") }
        info(text)(ctx)
    }

    private val ALTAR_SUFFIX = Regex("""^(.+?) altar$""", RegexOption.IGNORE_CASE)

    val ESSENCE_NAMES: List<String> = listOf("Rune essence", "Pure essence")

    fun onCraftRunes(ctx: LocContext): Any? {
        interactions++
        val altar = ctx.definition.name ?: return "no-name"
        val element = ALTAR_SUFFIX.matchEntire(altar)?.groupValues?.get(1) ?: return "not-an-elemental-altar '$altar'"
        val runeName = "$element rune"
        val runeId = itemIdByName(runeName) ?: return "no item named '$runeName'"
        val row = refRow("Runecrafting", runeName, "Runes")
        val req = row?.level ?: 1
        if (level(ctx.player, Stat.RUNECRAFTING) < req) {
            msg(ctx.player, "You need a Runecrafting level of $req to craft ${runeName.lowercase()}s.")
            return "level-too-low $req"
        }
        val c = backpack(ctx.player)
        val snapshot = c.toArray()
        var essence = 0
        for (name in ESSENCE_NAMES) {
            val id = itemIdByName(name) ?: continue
            val n = c.count(id).toInt()
            if (n > 0) essence += c.remove(id, n).removed
        }
        if (essence == 0) { msg(ctx.player, "You don't have any rune essence."); return "no-essence" }
        val added = c.add(runeId, essence)
        if (added.remaining > 0) {
            for (i in snapshot.indices) c[i] = snapshot[i]
            msg(ctx.player, "You don't have enough inventory space.")
            return "no-space"
        }
        ProductionActions.inventoryResend(ctx.player)
        msg(ctx.player, "You bind the temple's power into ${runeName.lowercase()}s.")
        val xpEach = row?.xp ?: PLACEHOLDER_XP
        xp(ctx.player, Stat.RUNECRAFTING, xpEach * essence)
        return "crafted $essence x $runeId '$runeName' for ${xpEach * essence} xp [${if (row != null) "TABLE Runes" else "placeholder"}]"
    }

    fun onInfusePouch(ctx: LocContext): Any? {
        interactions++
        val r = ProductionActions.onLoc(ctx.player, Stat.SUMMONING, ctx.locId, ctx.x, ctx.z, ctx.plane) {
            it.productName?.endsWith(" pouch", ignoreCase = true) == true
        }
        return "infuse -> ${r.outcome} ${r.recipe?.productName ?: ""}"
    }

    fun onOffer(ctx: LocContext): Any? {
        interactions++
        val c = backpack(ctx.player)
        val slot = (0 until c.size).firstOrNull { s -> c[s]?.let { Bury.isBuryable(it.id) } == true }
        if (slot == null) { msg(ctx.player, "You have no bones to offer."); return "no-bones" }
        val held = c[slot]!!
        val req = Bury.requirementFor(held.id, itemName(held.id))
        val left = held.amount - 1
        if (left > 0) c[slot] = Item(held.id, left) else c.removeSlot(slot)
        ProductionActions.inventoryResend(ctx.player)
        msg(ctx.player, "The gods are pleased with your offering.")
        xp(ctx.player, Stat.PRAYER, req.xpTenths / 10.0)
        return "offered ${held.id} for ${req.xpTenths / 10.0} [${req.source}; no altar bonus modelled]"
    }

    fun onConvertMemories(ctx: LocContext): Any? {
        interactions++
        val rows = refRows("Divination", "Conversion")
        val c = backpack(ctx.player)
        var converted = 0
        var total = 0.0
        val divLevel = level(ctx.player, Stat.DIVINATION)
        for (row in rows) {
            val id = itemIdByName(row.name) ?: continue
            if ((row.level ?: 1) > divLevel) continue
            val n = c.count(id).toInt()
            if (n <= 0) continue
            val removed = c.remove(id, n).removed
            converted += removed
            total += removed * row.xp
        }
        if (converted == 0) { msg(ctx.player, "You have no memories you can convert."); return "no-memories" }
        ProductionActions.inventoryResend(ctx.player)
        msg(ctx.player, "You convert $converted memories into experience.")
        xp(ctx.player, Stat.DIVINATION, total)
        return "converted $converted memories for $total Divination xp [TABLE Conversion]"
    }

    private val MATERIAL_CACHE = Regex("""^Material cache \((.+)\)$""", RegexOption.IGNORE_CASE)

    fun materialLevel(itemId: Int): Int? =
        SkillRecipes.recipesUsing(itemId).filter { it.stat == Stat.ARCHAEOLOGY }.minOfOrNull { it.level }

    fun onExcavate(ctx: LocContext): Any? {
        val name = ctx.definition.name ?: "hotspot"
        val material = MATERIAL_CACHE.matchEntire(name)?.groupValues?.get(1)
        if (material != null) {
            val productId = itemIdByName(material)
            val level = productId?.let { materialLevel(it) }
            if (productId == null || level == null) {
                interactions++
                msg(ctx.player, "You can't find anything useful here.")
                return "no-level-for '$material' (item $productId) - refused"
            }
            return startGather(ctx.player, Gather(Stat.ARCHAEOLOGY, "excavating $name", level, PLACEHOLDER_XP, productId,
                "level from the lowest Archaeology recipe using '$material'; placeholder xp"))
        }
        return startGather(ctx.player, Gather(Stat.ARCHAEOLOGY, "excavating $name", 1, PLACEHOLDER_XP, null,
            "a hotspot with no material: placeholder xp only"))
    }

    fun onFarmHarvest(ctx: LocContext): Any? {
        val locName = ctx.definition.name ?: return "no-name"
        val crop = cropNameFor(locName)
        val productId = crop?.let { itemIdByName(it) }
        val row = crop?.let { c -> refRows("Farming").firstOrNull { it.name.equals(c, ignoreCase = true) } }
        return startGather(ctx.player, Gather(Stat.FARMING, "harvesting $locName", row?.level ?: 1,
            row?.xp?.div(10.0) ?: PLACEHOLDER_XP, productId,
            if (row != null) "TABLE Farming '${row.name}' (the row's total / 10 per harvest)" else "placeholder"))
    }

    fun cropNameFor(locName: String): String? {
        val candidates = listOf(locName, locName.removeSuffix("s"), locName.removeSuffix("es"), locName.lowercase().replaceFirstChar { it.uppercase() })
        return candidates.firstOrNull { itemIdByName(it) != null }
    }

    private val WISP = Regex("""^(.+?) (wisp|spring)$""", RegexOption.IGNORE_CASE)

    fun onDivinationHarvest(ctx: NpcContext): Any? {
        val npcName = ctx.definition.name ?: return "no-name"
        val m = WISP.matchEntire(npcName) ?: return "not-a-wisp '$npcName'"
        val tier = m.groupValues[1]
        val row = refRow("Divination", "$tier wisp", "Gathering")
        return startGather(ctx.player, Gather(Stat.DIVINATION, "harvesting $npcName", row?.level ?: 1, row?.xp ?: PLACEHOLDER_XP,
            itemIdByName("$tier memory"), if (row != null) "TABLE Gathering '${row.name}'" else "placeholder"), ctx.npcIndex)
    }

    fun onHunterCatch(ctx: NpcContext): Any? {
        val npcName = ctx.definition.name ?: return "no-name"
        val row = refRows("Hunter").firstOrNull { it.name.equals(npcName, ignoreCase = true) }
        if (row == null) { interactions++; msg(ctx.player, "You can't catch that."); return "no-hunter-row '$npcName'" }
        val productName = row.product?.substringBefore(",")?.trim()
        val product = productName?.let { itemIdByName(it) }
        val jar = when {
            productName == null -> null
            productName.endsWith("impling jar", ignoreCase = true) -> itemIdByName("Impling jar")
            productName.endsWith(" jar", ignoreCase = true) -> itemIdByName("Butterfly jar")
            else -> null
        }
        val net = itemIdByName("Butterfly net")
        if (net == null) { interactions++; return "no 'Butterfly net' item in this cache - refused" }
        if (product == null || jar == null) {
            interactions++
            msg(ctx.player, "You can't catch that on this server yet.")
            return "catch of '$npcName' refused: product ${productName ?: "none"} -> item $product, jar $jar (only jar catches are modelled)"
        }
        return startGather(ctx.player, Gather(Stat.HUNTER, "catching $npcName", row.level ?: 1, row.xp, product,
            "TABLE ${row.category} '${row.name}'", consumes = jar, tool = net, successes = 1), ctx.npcIndex)
    }

    data class SlayerTask(val monster: String, var left: Int, val xpPerKill: Double, val master: String)

    private val slayerTasks = java.util.concurrent.ConcurrentHashMap<String, SlayerTask>()

    fun slayerTaskFor(playerName: String): SlayerTask? = slayerTasks[playerName.lowercase()]

    fun restoreSlayerTask(playerName: String, task: SlayerTask?) {
        if (task == null || task.left <= 0 || task.monster.isBlank()) slayerTasks.remove(playerName.lowercase())
        else slayerTasks[playerName.lowercase()] = task
    }

    fun toSaved(task: SlayerTask?): com.opennxt.model.account.PlayerSave.SavedSlayerTask? =
        task?.takeIf { it.left > 0 && it.monster.isNotBlank() && it.master.isNotBlank() && it.xpPerKill >= 0.0 }
            ?.let { com.opennxt.model.account.PlayerSave.SavedSlayerTask(it.monster, it.left, Math.round(it.xpPerKill * 10).toInt(), it.master) }

    fun fromSaved(saved: com.opennxt.model.account.PlayerSave.SavedSlayerTask?): SlayerTask? =
        saved?.let { SlayerTask(it.monster, it.left, it.xpTenths / 10.0, it.master) }

    private val attackableNames: Set<String> by lazy {
        runCatching {
            ContentRegistry.source.npcsDeclaring("Attack").mapNotNullTo(HashSet()) { ContentRegistry.source.npc(it)?.name?.lowercase() }
        }.getOrDefault(emptySet())
    }

    fun onGetTask(ctx: NpcContext): Any? {
        interactions++
        val name = ctx.player.name
        slayerTasks[name.lowercase()]?.let { t ->
            if (t.left > 0) { msg(ctx.player, "You're still hunting ${t.monster.lowercase()}s; you have ${t.left} to go."); return "has-task" }
        }
        val slayerLevel = level(ctx.player, Stat.SLAYER)
        val options = refRows("Slayer", "Monsters").filter { (it.level2 ?: 1) <= slayerLevel && it.name.lowercase() in attackableNames }
        if (options.isEmpty()) { msg(ctx.player, "I have no task for you right now."); return "no-options" }
        val pick = options[random.nextInt(options.size)]
        val count = 15 + random.nextInt(26)
        slayerTasks[name.lowercase()] = SlayerTask(pick.name, count, pick.xp, ctx.definition.name?.takeIf { it.isNotBlank() } ?: "Slayer master")
        msg(ctx.player, "Your new task is to kill $count ${pick.name.lowercase()}s.")
        return "task ${pick.name} x$count (${pick.xp} xp each) [TABLE Monsters; count 15..40]"
    }

    fun onNpcKilled(killerName: String?, npcName: String?) {
        if (killerName == null || npcName == null) return
        val t = slayerTasks[killerName.lowercase()] ?: return
        if (t.left <= 0 || !t.monster.equals(npcName, ignoreCase = true)) return
        if (!xpByName(killerName, Stat.SLAYER, t.xpPerKill)) return
        t.left--
        if (t.left == 0) messageByName(killerName, "You have completed your Slayer task. Return to a Slayer master.")
        else if (t.left % 10 == 0) messageByName(killerName, "You have ${t.left} ${t.monster.lowercase()}s left to kill.")
    }

    val AGILITY_OBSTACLE_NAMES: Set<String> = setOf(
        "log balance", "obstacle net", "obstacle pipe", "rope swing", "ropeswing", "balancing ledge", "balancing rope",
        "tree branch", "monkey bars", "zip line", "hurdle"
    )

    fun onCrossed(ctx: LocContext): Boolean {
        val n = ctx.definition.name?.lowercase() ?: return false
        if (n !in AGILITY_OBSTACLE_NAMES) return false
        val last = lastAgilityAt[ctx.player]
        if (last != null && clock - last < AGILITY_COOLDOWN_TICKS) return false
        lastAgilityAt[ctx.player] = clock
        xp(ctx.player, Stat.AGILITY, PLACEHOLDER_XP)
        logger.info { "skill bones ${ctx.player.name}: crossed '${ctx.definition.name}' for $PLACEHOLDER_XP Agility xp [placeholder]" }
        return true
    }

    private val agilityListener: (LocContext, TileLocation) -> Unit = { ctx, _ -> onCrossed(ctx) }

    fun uninstallListeners() {
        if (Obstacles.crossedListener === agilityListener) Obstacles.crossedListener = null
    }

    private val bound = LinkedHashMap<String, Int>()
    private val skipped = ArrayList<String>()

    fun boundBindings(): Map<String, Int> = bound.toMap()
    fun skippedBindings(): List<String> = skipped.toList()

    private fun bindLoc(action: String, handler: (LocContext) -> Any?) {
        if (action in ContentRegistry.boundLocActions()) { skipped += "loc '$action' (already bound)"; return }
        try { bound["loc '$action'"] = ContentRegistry.onLocAction(action, handler) }
        catch (e: IllegalArgumentException) { skipped += "loc '$action' (declared by no loc)" }
    }

    private fun bindNpc(action: String, handler: (NpcContext) -> Any?) {
        if (action in ContentRegistry.boundNpcActions()) { skipped += "npc '$action' (already bound)"; return }
        try { bound["npc '$action'"] = ContentRegistry.onNpcAction(action, handler) }
        catch (e: IllegalArgumentException) { skipped += "npc '$action' (declared by no npc)" }
    }

    fun install(): Int {
        bound.clear(); skipped.clear()
        if (!enabled) {
            uninstallListeners()
            logger.warn { "skill interactions: disabled (-Dopennxt.experiment.skillbones=false)" }
            return 0
        }
        runCatching { names; refEntry; attackableNames }.onFailure { logger.warn(it) { "skill interactions: table preload failed; will retry on first use" } }
        bindLoc("Craft runes", ::onCraftRunes)
        bindLoc("Infuse-pouch", ::onInfusePouch)
        bindLoc("Renew points", info("You renew your Summoning points."))
        bindLoc("Renew-points", info("You renew your Summoning points."))
        bindLoc("Pray at", prayAt("You recharge your Prayer points."))
        bindLoc("Pray-at", prayAt("You recharge your Prayer points."))
        bindLoc("Pray", prayAt("You recharge your Prayer points."))
        bindLoc("Offer", ::onOffer)
        bindLoc("Convert memories", ::onConvertMemories)
        bindLoc("Excavate", ::onExcavate)
        bindLoc("Harvest", ::onFarmHarvest)
        bindLoc("Clear", info("You clear the patch."))
        bindLoc("Build", info("Player-owned houses are not available on this server yet."))
        bindNpc("Harvest", ::onDivinationHarvest)
        bindNpc("Catch", ::onHunterCatch)
        bindNpc("Get task", ::onGetTask)
        if (Obstacles.crossedListener == null) Obstacles.crossedListener = agilityListener
        logger.info {
            "skill interactions: ${bound.size} binding(s) over ${bound.values.sum()} ids - " +
                bound.entries.joinToString { "${it.key} ${it.value}" } +
                (if (skipped.isNotEmpty()) "; skipped ${skipped.joinToString()}" else "") +
                "; ${AGILITY_OBSTACLE_NAMES.size} agility obstacle names"
        }
        return bound.values.sum()
    }
}

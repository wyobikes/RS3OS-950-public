package com.opennxt.content.impl

import com.opennxt.api.stat.Stat
import com.opennxt.content.ActionSlot
import com.opennxt.content.ContentPlayer
import com.opennxt.content.ContentRegistry
import com.opennxt.content.LocContext
import com.opennxt.model.items.ItemContainer
import com.opennxt.model.entity.rendering.UpdateBlockType
import com.opennxt.model.entity.rendering.blocks.PlayerFaceDirectionBlock
import com.opennxt.model.map.LocInteraction
import com.opennxt.resources.Names950
import com.opennxt.resources.MiningLevels
import com.opennxt.model.world.LocChanges
import com.opennxt.model.world.TileLocation
import com.opennxt.resources.sqlite.RsDatabase
import mu.KotlinLogging
import java.util.Collections
import java.util.WeakHashMap

object Skilling {
    private val logger = KotlinLogging.logger { }

    val enabled: Boolean = System.getProperty("opennxt.experiment.skilling") != "false"

    val WOODCUTTING_TABLE: Map<String, Requirement> = mapOf(
        "Logs" to Requirement(1, xpFor("Logs", 250)),
        "Oak logs" to Requirement(15, xpFor("Oak logs", 375)),
        "Willow logs" to Requirement(30, xpFor("Willow logs", 675)),
        "Teak logs" to Requirement(35, 850),
        "Maple logs" to Requirement(45, 1000),
        "Mahogany logs" to Requirement(50, 1250),
        "Yew logs" to Requirement(60, 1750),
        "Magic logs" to Requirement(75, xpFor("Magic logs", 2500)),
        "Elder logs" to Requirement(90, xpFor("Elder logs", 3250))
    )

    private fun xpFor(log: String, fallback: Int): Int =
        SkillingRates.WOODCUTTING_XP_TENTHS[log] ?: fallback

    val MINING_TABLE: Map<String, Requirement> = run {
        val out = LinkedHashMap<String, Requirement>()
        for ((item, level) in MiningLevels.levels()) {
            out[item] = Requirement(level, SkillingRates.MINING_XP_TENTHS[item] ?: DEFAULT_XP_TENTHS)
        }
        for ((item, xp) in SkillingRates.MINING_XP_TENTHS) {
            val level = MiningLevels.levelForItem(item) ?: DEFAULT_MINING_LEVEL
            out[item] = Requirement(level, xp)
        }
        out
    }

    data class Requirement(
        val level: Int,
        val xpTenths: Int,
        val levelSource: String = "AUTHORED",
        val xpSource: String = "AUTHORED"
    )

    val DEFAULT_XP_TENTHS: Int = System.getProperty("opennxt.skilling.defaultXpTenths")?.toIntOrNull() ?: 250

    val DEFAULT_WOODCUTTING_LEVEL: Int =
        System.getProperty("opennxt.skilling.defaultWoodcuttingLevel")?.toIntOrNull() ?: 1

    val DEFAULT_MINING_LEVEL: Int =
        System.getProperty("opennxt.skilling.defaultMiningLevel")?.toIntOrNull() ?: 1

    val RESPAWN_TICKS: Int = System.getProperty("opennxt.skilling.respawnTicks")?.toIntOrNull() ?: 60

    val STUMP_LOC: Int? = System.getProperty("opennxt.skilling.stumpLoc")?.toIntOrNull()

    const val REMOVE_LOC = -1

    const val HATCHET_SUFFIX = " hatchet"

    const val PICKAXE_SUFFIX = " pickaxe"

    const val PROVENANCE =
        "Levels and names from the cache; stumps from data/seed/tree_stumps.tsv."

    const val PROVENANCE_SHORT = ""

    enum class Outcome {
        GATHERED,

        NO_TOOL,

        LEVEL_TOO_LOW,

        NO_YIELD,

        DEPLETED,

        NO_SPACE,

        NO_DATABASE,
        REPEATING,
        MISSED,
        STARTED,
        TOOL_LEVEL_TOO_LOW,

        NO_PLACEMENT
    }

    data class Gather(
        val outcome: Outcome,
        val locId: Int,
        val locName: String?,
        val kind: ResourceNodes.Kind,
        val itemId: Int? = null,
        val itemName: String? = null,
        val amount: Int = 0,
        val xpTenths: Int = 0,
        val chance: Double? = null,
        val levelSource: String? = null,
        val xpSource: String? = null,
        val stat: Stat? = null,
        val levelRequired: Int = 1,
        val levelFromCache: Boolean = false,
        val playerLevel: Int = 1,
        val toolId: Int? = null,
        val depletedTo: Int? = null,
        val respawnAtTick: Long = -1,
        val inventorySent: Boolean = false,
        val detail: String = ""
    ) {
        val gathered: Boolean get() = outcome == Outcome.GATHERED
        override fun toString() = buildString {
            append("Gather(").append(outcome).append(" loc ").append(locId).append(" '").append(locName)
            append("' ").append(kind)
            if (itemId != null) append(" -> ").append(amount).append("x ").append(itemId).append(" '").append(itemName)
                .append("'")
            if (xpTenths > 0) append(" +").append(xpTenths / 10.0).append(" ").append(stat).append(" xp")
            append(" level ").append(playerLevel).append("/").append(levelRequired)
            append(if (levelFromCache) " [level GROUNDED: param 23]" else " [level ${levelSource ?: "AUTHORED"}]")
            if (xpSource != null) append(" [xp ").append(xpSource).append("]")
            if (chance != null) append(" [chance %.3f]".format(chance))
            if (depletedTo != null) {
                if (depletedTo == REMOVE_LOC) append(" REMOVED (no listed stump) until tick ").append(respawnAtTick)
                else append(" depleted->").append(depletedTo).append(" until tick ").append(respawnAtTick)
            }
            if (detail.isNotEmpty()) append(" ").append(detail)
            append(")")
        }
    }

    @Volatile
    var containerSupplier: (ContentPlayer) -> ItemContainer = { it.inventory }

    @Volatile
    var wornSupplier: (ContentPlayer) -> ItemContainer? = { null }

    val TOOLBELT_BASE: Map<ResourceNodes.Kind, Int> = mapOf(
        ResourceNodes.Kind.WOODCUTTING to Names950.itemId("bronze_axe"),
        ResourceNodes.Kind.MINING to Names950.itemId("bronze_pickaxe"),
    )
    val DEFAULT_TOOLBELT: Boolean = System.getProperty("opennxt.skilling.toolbelt") != "off"

    @Volatile
    var toolbelt: Boolean = DEFAULT_TOOLBELT

    @Volatile
    var beltSupplier: (ContentPlayer) -> Set<Int> = { emptySet() }

    val CYCLE_TICKS: Int = System.getProperty("opennxt.skilling.cycleTicks")?.toIntOrNull() ?: 4

    val DEFAULT_FIRST_CYCLE: (ResourceNodes.Kind) -> Int = { kind ->
        System.getProperty("opennxt.skilling.firstCycle")?.toIntOrNull() ?: when (kind) {
            ResourceNodes.Kind.MINING -> 2
            ResourceNodes.Kind.WOODCUTTING -> 3
            ResourceNodes.Kind.GATHERING -> 0
        }
    }
    var firstCycleTicks: (ResourceNodes.Kind) -> Int = DEFAULT_FIRST_CYCLE

    private val MINING_SEQ = Names950.seqId("mtx_batch6_player_mega_punch_mining")
    private val WOODCUTTING_SEQ = Names950.seqId("human_woodcutting_bronze_axe_2013_update")

    // Each hatchet item carries its exact chop animation in the cache via the
    // `woodcutting_anim_default` param (primal -> human_woodcutting_primal_axe_2013_update,
    // dragon -> human_woodcutting_dragon_axe_2013_update, ...). The param name is resolved
    // through Names950 so a cache rebuild never needs a source edit here.
    private val WOODCUTTING_ANIM_PARAM = Names950.paramId("woodcutting_anim_default")

    private fun itemExtraParam(toolId: Int, paramId: Int): Int? {
        if (!RsDatabase.available) return null
        val json = RsDatabase.queryOne("SELECT value FROM items_attr WHERE id = ? AND field = 'extra'", toolId) {
            it.getString(1)
        } ?: return null
        return com.opennxt.model.combat.NpcCombat.parseParams(json)[paramId]
    }

    private val hatchetAnimMemo = java.util.concurrent.ConcurrentHashMap<Int, java.util.Optional<Int>>()

    private fun hatchetAnimationSeq(toolId: Int): Int? =
        hatchetAnimMemo.computeIfAbsent(toolId) {
            java.util.Optional.ofNullable(itemExtraParam(toolId, WOODCUTTING_ANIM_PARAM)?.takeIf { it >= 0 })
        }.orElse(null)

    fun animationFor(kind: ResourceNodes.Kind, toolId: Int = -1): IntArray? = when (kind) {
        ResourceNodes.Kind.MINING -> intArrayOf(MINING_SEQ, MINING_SEQ, MINING_SEQ, MINING_SEQ)
        ResourceNodes.Kind.WOODCUTTING -> {
            val seq = hatchetAnimationSeq(toolId) ?: WOODCUTTING_SEQ
            intArrayOf(seq, seq, seq, seq)
        }

        else -> null
    }

    fun animationEveryTick(kind: ResourceNodes.Kind): Boolean = kind == ResourceNodes.Kind.WOODCUTTING

    private val STOP_ANIMATION = intArrayOf(-1, -1, -1, -1)

    fun startMessage(kind: ResourceNodes.Kind): String? = when (kind) {
        ResourceNodes.Kind.WOODCUTTING -> "You swing your hatchet at the tree."
        else -> null
    }

    var animationSink: (ContentPlayer, IntArray) -> Unit = { _, _ -> }
    var messageSink: (ContentPlayer, String) -> Unit = { _, _ -> }

    var faceSink: (ContentPlayer, Int) -> Unit = { _, _ -> }
    private var animationsSent = 0
    private var messagesSent = 0
    private var facesSent = 0
    fun animationsSent(): Int = animationsSent
    fun messagesSent(): Int = messagesSent
    fun facesSent(): Int = facesSent

    fun faceLoc(player: ContentPlayer, originX: Int, originZ: Int, sizeX: Int, sizeZ: Int): Int? {
        val from = whereIs(player)
        if (2 * from.x + 1 == 2 * originX + sizeX && 2 * from.y + 1 == 2 * originZ + sizeZ) return null
        val angle = PlayerFaceDirectionBlock.towards(from.x, from.y, originX, originZ, sizeX, sizeZ)
        runCatching { faceSink(player, angle) }
        facesSent++
        return angle
    }

    @Volatile
    private var containedFailures = 0
    fun containedFailures(): Int = containedFailures

    fun yieldMessage(kind: ResourceNodes.Kind, itemName: String): String? = when (kind) {
        ResourceNodes.Kind.WOODCUTTING -> "You get some ${itemName.lowercase()}."
        else -> null
    }

    data class SuccessContext(
        val kind: ResourceNodes.Kind, val itemName: String, val level: Int, val requiredLevel: Int,
        val toolId: Int, val toolName: String?, val toolLevel: Int?
    )

    val DEFAULT_SUCCESS_CHANCE: (SuccessContext) -> Double = { c ->
        if (System.getProperty("opennxt.skilling.chance") == "off") 1.0
        else when (c.kind) {
            ResourceNodes.Kind.WOODCUTTING ->
                c.toolName?.let { SkillXpTable.woodcuttingChance(c.itemName, it, c.level) } ?: 1.0

            else -> 1.0
        }
    }
    var successChance: (SuccessContext) -> Double = DEFAULT_SUCCESS_CHANCE

    val DEFAULT_FELL_CHANCE: (String) -> Double? = { logs ->
        if (System.getProperty("opennxt.skilling.fell") == "off") null else FELL_OVERRIDES[logs]
            ?: SkillXpTable.fellChance(logs)
    }

    val FELL_OVERRIDES: Map<String, Double> = mapOf("Logs" to 1.0)
    var fellChance: (String) -> Double? = DEFAULT_FELL_CHANCE
    var random: java.util.Random = java.util.Random(0x534b494cL)

    data class Active(
        val ctx: LocContext,
        val kind: ResourceNodes.Kind,
        val startTile: TileLocation,
        var nextTick: Long,
        var cycles: Int = 0,
        val toolId: Int = -1
    )

    private val active = java.util.Collections.synchronizedMap(java.util.IdentityHashMap<ContentPlayer, Active>())

    private val lastCycle: MutableMap<ContentPlayer, Long> =
        java.util.Collections.synchronizedMap(WeakHashMap<ContentPlayer, Long>())

    fun lastCycleOf(player: ContentPlayer): Long? = lastCycle[player]

    fun stopFor(player: ContentPlayer, why: String) = stop(player, why)
    fun activeCount(): Int = active.size
    fun activeFor(player: ContentPlayer): Active? = active[player]
    internal fun clearActions() {
        active.clear(); lastCycle.clear()
    }

    private var cyclesPaid = 0
    private var cyclesMissed = 0
    private var actionsStopped = 0
    fun cyclesPaid(): Int = cyclesPaid
    fun cyclesMissed(): Int = cyclesMissed
    fun actionsStopped(): Int = actionsStopped

    private fun whereIs(player: ContentPlayer): TileLocation =
        SkillingWiring.ownerOf(player)?.entity?.location?.let { TileLocation(it.x, it.y, it.plane) } ?: player.location

    internal object SLOT : ActionSlot.Owner {
        override val actionName = "skilling"
        override fun cancelSlot(player: ContentPlayer, why: String) = stop(player, why)
    }

    private fun arm(player: ContentPlayer, a: Active) {
        active[player] = a
        ActionSlot.claim(player, SLOT)
    }

    private fun stop(player: ContentPlayer, why: String) {
        val was = active.remove(player)
        ActionSlot.release(player, SLOT)
        if (was != null) {
            actionsStopped++
            logger.info { "skilling: ${player.name}'s action stopped - $why" }
            if (animationEveryTick(was.kind)) {
                runCatching { animationSink(player, STOP_ANIMATION) }; animationsSent++
            }
        }
    }

    @Volatile
    var levelSupplier: (ContentPlayer, Stat) -> Int = { _, _ -> 1 }

    @Volatile
    var xpSink: (ContentPlayer, Stat, Double) -> Unit = { _, _, _ -> }

    @Volatile
    var inventoryResend: (ContentPlayer) -> Boolean = { false }

    private val awarded: MutableMap<ContentPlayer, MutableMap<Stat, Int>> =
        Collections.synchronizedMap(WeakHashMap())

    fun xpAwarded(player: ContentPlayer, stat: Stat): Int =
        synchronized(awarded) { awarded[player]?.get(stat) ?: 0 }

    private fun record(player: ContentPlayer, stat: Stat, tenths: Int) {
        synchronized(awarded) {
            val map = awarded.getOrPut(player) { java.util.EnumMap(Stat::class.java) }
            map[stat] = (map[stat] ?: 0) + tenths
        }
    }

    internal fun clearAwards() = synchronized(awarded) { awarded.clear() }

    object Stumps {
        const val STUMP_NAME = "Tree stump"

        enum class Source {
            FIXED,

            RUN,

            FORCED
        }

        data class Stump(val locId: Int, val source: Source, val sampleRows: Int)

        val enabled: Boolean get() = System.getProperty("opennxt.skilling.stumps") != "off"

        private val seedPath = com.opennxt.Constants.DATA_PATH.resolve("seed").resolve("tree_stumps.tsv")

        val fixedValue: Map<Int, Pair<Int, Int>> by lazy { loadSeed() }

        private fun loadSeed(): Map<Int, Pair<Int, Int>> {
            if (!java.nio.file.Files.isRegularFile(seedPath)) {
                logger.warn {
                    "skilling: no stump seed at $seedPath; chopped trees are removed until they respawn"
                }
                return emptyMap()
            }
            val out = LinkedHashMap<Int, Pair<Int, Int>>()
            java.nio.file.Files.newBufferedReader(seedPath).useLines { lines ->
                for (line in lines) {
                    if (line.startsWith("#") || line.startsWith("tree_loc")) continue
                    val p = line.split('\t')
                    if (p.size < 5) continue
                    val tree = p[0].trim().toIntOrNull() ?: continue
                    val stump = p[2].trim().toIntOrNull() ?: continue
                    out[tree] = stump to (p[4].trim().toIntOrNull() ?: 0)
                }
            }
            logger.info {
                "skilling: loaded ${out.size} tree->stump pair(s) from $seedPath"
            }
            return out
        }

        private val memo = java.util.concurrent.ConcurrentHashMap<Int, java.util.Optional<Stump>>()

        internal fun clearMemo() = memo.clear()

        fun chopRun(locId: Int): List<Int> {
            val name = nameOf(locId) ?: return listOf(locId)
            var lo = locId
            while (isChoppableNamed(lo - 1, name)) lo--
            var hi = locId
            while (isChoppableNamed(hi + 1, name)) hi++
            return (lo..hi).toList()
        }

        private fun nameOf(locId: Int): String? =
            com.opennxt.resources.sqlite.SqliteLocCodec.load(locId)?.name?.takeIf { it.isNotEmpty() }

        private fun isChoppableNamed(locId: Int, name: String): Boolean {
            val def = com.opennxt.resources.sqlite.SqliteLocCodec.load(locId) ?: return false
            if (def.name != name) return false
            return def.actions.any { it != null && ResourceNodes.isChopAction(it) }
        }

        fun stumpFor(locId: Int): Stump? = memo.computeIfAbsent(locId) {
            java.util.Optional.ofNullable(resolve(locId))
        }.orElse(null)

        private fun resolve(locId: Int): Stump? {
            STUMP_LOC?.let { return Stump(it, Source.FORCED, 0) }
            if (!enabled) return null
            fixedValue[locId]?.let { (stump, n) -> return Stump(stump, Source.FIXED, n) }
            if (!RsDatabase.available) return null
            val run = chopRun(locId)
            if (run.size < 2) return null
            val offsets = run.mapNotNull { fixedValue[it]?.first?.minus(it) }.toSet()
            if (offsets.size != 1) return null
            val candidate = locId + offsets.first()
            if (nameOf(candidate) != STUMP_NAME) return null
            return Stump(candidate, Source.RUN, 0)
        }

        fun coverage(): Map<Int, Stump> {
            val out = LinkedHashMap<Int, Stump>()
            for (action in listOf(ResourceNodes.CHOP_DOWN, ResourceNodes.CHOP_DOWN_HYPHEN, ResourceNodes.CHOP)) {
                for (id in ResourceNodes.locsDeclaring(action)) {
                    if (out.containsKey(id)) continue
                    stumpFor(id)?.let { out[id] = it }
                }
            }
            return out
        }
    }

    object Canopy {
        val enabled: Boolean get() = System.getProperty("opennxt.skilling.canopy") != "off"

        fun above(trunk: LocInteraction.Placed, plane: Int): LocInteraction.Placed? {
            if (!enabled) return null
            if (!RsDatabase.available) return null
            if (plane >= 3) return null
            val candidates = LocInteraction.placementsCovering(trunk.originX, trunk.originZ, plane + 1)
                .filter { it.locId != trunk.locId && it.tiles > 1 && it.rot == trunk.rot && !interactable(it.locId) }
            if (candidates.isEmpty()) return null
            val trunkName = nameOf(trunk.locId)
            return candidates.sortedWith(
                compareBy(
                    { if (trunkName != null && nameOf(it.locId) == trunkName) 0 else 1 },
                    { it.tiles },
                    { it.locId }
                )
            ).first()
        }

        private fun interactable(locId: Int): Boolean =
            com.opennxt.resources.sqlite.SqliteLocCodec.load(locId)
                ?.actions?.any { !it.isNullOrEmpty() } ?: false

        internal fun nameOf(locId: Int): String? =
            com.opennxt.resources.sqlite.SqliteLocCodec.load(locId)?.name?.takeIf { it.isNotEmpty() }
    }

    data class RemovedCanopy(
        val key: LocChanges.Key,
        val locId: Int,
        val rotation: Int
    )

    data class Depletion(
        val key: LocChanges.Key,
        val originalLocId: Int,
        val depletedLocId: Int,
        val rotation: Int,
        val respawnAtTick: Long,
        val stumpSource: Stumps.Source? = null,
        val canopy: RemovedCanopy? = null
    ) {
        val removed: Boolean get() = depletedLocId == REMOVE_LOC
    }

    private val depleted = LinkedHashMap<LocChanges.Key, Depletion>()

    @Volatile
    private var tickCount: Long = 0

    fun ticks(): Long = tickCount

    fun depletionAt(plane: Int, x: Int, z: Int, shape: Int): Depletion? =
        synchronized(depleted) { depleted[LocChanges.Key(plane, x, z, shape)] }

    fun depletedCount(): Int = synchronized(depleted) { depleted.size }

    internal fun clearDepletions() {
        synchronized(depleted) { depleted.clear() }
        tickCount = 0
    }

    fun tick(): Int {
        tickCount++
        if (active.isNotEmpty()) {
            for ((player, a) in ArrayList(active.entries).map { it.key to it.value }) {
                try {
                    val here = whereIs(player)
                    if (here.x != a.startTile.x || here.y != a.startTile.y || here.plane != a.startTile.plane) {
                        stop(
                            player,
                            "moved from (${a.startTile.x},${a.startTile.y}) to (${here.x},${here.y})"
                        ); continue
                    }
                    val everyTick = animationEveryTick(a.kind)
                    if (!everyTick && tickCount == a.nextTick - 1) {
                        animationFor(a.kind, a.toolId)?.let { ids ->
                            runCatching {
                                animationSink(
                                    player,
                                    ids
                                )
                            }; animationsSent++
                        }
                    }
                    if (tickCount < a.nextTick) {
                        if (everyTick) animationFor(a.kind, a.toolId)?.let { ids ->
                            runCatching {
                                animationSink(
                                    player,
                                    ids
                                )
                            }; animationsSent++
                        }
                        continue
                    }
                    val g = gather(a.ctx, a.kind, fromCycle = true)
                    when (g.outcome) {
                        Outcome.GATHERED, Outcome.MISSED -> {
                            a.nextTick = tickCount + CYCLE_TICKS; a.cycles++
                            if (everyTick && active.containsKey(player)) animationFor(a.kind, a.toolId)?.let { ids ->
                                runCatching {
                                    animationSink(
                                        player,
                                        ids
                                    )
                                }; animationsSent++
                            }
                        }

                        else -> stop(player, "cycle refused: ${g.outcome} ${g.detail}")
                    }
                } catch (t: Throwable) {
                    containedFailures++
                    runCatching { stop(player, "error: ${t.javaClass.simpleName}") }
                    logger.error(t) {
                        "skilling: ${player.name}'s ${a.kind} action failed and was stopped (failures: $containedFailures)"
                    }
                }
            }
        }
        val due = synchronized(depleted) {
            depleted.values.filter { it.respawnAtTick <= tickCount }.also { list ->
                list.forEach { depleted.remove(it.key) }
            }
        }
        for (d in due) {
            respawn(d)
            logger.info {
                "skilling: loc ${d.originalLocId} respawned at (${d.key.x},${d.key.y},plane ${d.key.plane}) " +
                        "after $RESPAWN_TICKS ticks (was " +
                        (if (d.removed) "removed" else "stump ${d.depletedLocId} [${d.stumpSource}]") +
                        ")"
            }
        }
        return due.size
    }

    private fun respawn(d: Depletion) {
        d.canopy?.let { c ->
            canopyAttempts++
            runCatching { LocChanges.revert(c.key.plane, c.key.x, c.key.y, c.key.shape) }
                .onFailure {
                    canopyFailures++
                    if (!canopyWarned) {
                        canopyWarned = true
                        logger.warn {
                            "skilling: could not restore canopy ${c.locId} at ${c.key}: " +
                                    "${it::class.simpleName}: ${it.message} (logged once)"
                        }
                    }
                }
                .getOrNull()?.let { canopyApplied++ }
        }
        locChangeAttempts++
        val reverted = runCatching {
            LocChanges.revert(d.key.plane, d.key.x, d.key.y, d.key.shape)
        }.onFailure {
            locChangeFailures++
            if (!locChangeWarned) {
                locChangeWarned = true
                logger.warn {
                    "skilling: could not send respawn of loc ${d.originalLocId} at ${d.key}: " +
                            "${it::class.simpleName}: ${it.message} (logged once)"
                }
            }
        }.getOrNull()
        if (reverted != null) locChangeApplied++
    }

    private fun sendLocChange(key: LocChanges.Key, rotation: Int, originalId: Int, newId: Int, why: String) {
        locChangeAttempts++
        val outcome = runCatching {
            if (newId == REMOVE_LOC) LocChanges.remove(
                plane = key.plane, x = key.x, y = key.y, shape = key.shape,
                rotation = rotation, originalId = originalId
            ) else LocChanges.change(
                plane = key.plane, x = key.x, y = key.y, shape = key.shape,
                rotation = rotation, originalId = originalId, newId = newId
            )
        }
        outcome.onFailure {
            locChangeFailures++
            if (!locChangeWarned) {
                locChangeWarned = true
                logger.warn {
                    "skilling: could not send LOC_ADD_CHANGE ($why) for loc $originalId at $key: " +
                            "${it::class.simpleName}: ${it.message} (logged once)"
                }
            }
        }.onSuccess { change ->
            if (change != null) locChangeApplied++
            else logger.warn {
                "skilling: loc change ($why) for loc $originalId at $key not sent (disabled or unsupported on this build)"
            }
        }
    }

    private fun sendCanopyRemoval(c: RemovedCanopy) {
        canopyAttempts++
        runCatching {
            LocChanges.remove(
                plane = c.key.plane, x = c.key.x, y = c.key.y, shape = c.key.shape,
                rotation = c.rotation, originalId = c.locId,
                crossPlane = true
            )
        }.onFailure {
            canopyFailures++
            if (!canopyWarned) {
                canopyWarned = true
                logger.warn {
                    "skilling: could not send LOC_DEL for canopy ${c.locId} at ${c.key}: " +
                            "${it::class.simpleName}: ${it.message} (logged once)"
                }
            }
        }.onSuccess { change ->
            if (change != null) canopyApplied++
            else logger.warn {
                "skilling: canopy removal for ${c.locId} at ${c.key} not sent (disabled or unsupported on this build)"
            }
        }
    }

    @Volatile
    private var canopyAttempts = 0

    @Volatile
    private var canopyApplied = 0

    @Volatile
    private var canopyFailures = 0

    @Volatile
    private var canopyWarned = false

    fun canopyAttempts(): Int = canopyAttempts

    fun canopyApplied(): Int = canopyApplied

    fun canopyFailures(): Int = canopyFailures

    @Volatile
    private var locChangeAttempts = 0

    @Volatile
    private var locChangeApplied = 0

    @Volatile
    private var locChangeFailures = 0

    @Volatile
    private var locChangeWarned = false

    fun locChangeAttempts(): Int = locChangeAttempts

    fun locChangeApplied(): Int = locChangeApplied

    fun locChangeFailures(): Int = locChangeFailures

    internal fun resetLocChangeCounters() {
        locChangeAttempts = 0; locChangeApplied = 0; locChangeFailures = 0; locChangeWarned = false
        canopyAttempts = 0; canopyApplied = 0; canopyFailures = 0; canopyWarned = false
    }

    private val itemNameMemo = java.util.concurrent.ConcurrentHashMap<Int, java.util.Optional<String>>()
    fun itemNameOf(id: Int): String? =
        if (!RsDatabase.available) null
        else itemNameMemo.computeIfAbsent(id) {
            java.util.Optional.ofNullable(RsDatabase.queryOne("SELECT name FROM items WHERE id = ?", id) {
                it.getString(
                    1
                )
            })
        }.orElse(null)

    fun toolIn(container: ItemContainer, kind: ResourceNodes.Kind): Int? = toolIn(container, kind, Int.MAX_VALUE)

    fun toolIn(
        container: ItemContainer,
        kind: ResourceNodes.Kind,
        level: Int,
        worn: ItemContainer? = null,
        belt: Set<Int> = emptySet()
    ): Int? {
        val suffix = when (kind) {
            ResourceNodes.Kind.WOODCUTTING -> HATCHET_SUFFIX
            ResourceNodes.Kind.MINING -> PICKAXE_SUFFIX
            ResourceNodes.Kind.GATHERING -> return null
        }

        data class Held(val id: Int, val requirement: Int, val power: Int)

        val held = ArrayList<Held>()
        val ids = ArrayList<Int>()
        for (item in container.items() + (worn?.items() ?: emptyList())) ids += item.id
        if (toolbelt) TOOLBELT_BASE[kind]?.let { ids += it }
        ids += belt
        for (id in ids) {
            val name = itemNameOf(id) ?: continue
            if (!name.endsWith(suffix)) continue
            val requirement = toolRequirement(id) ?: 1
            val power = SkillXpTable.hatchets[name]?.power ?: requirement
            if (held.none { it.id == id }) held += Held(id, requirement, power)
        }
        if (held.isEmpty()) return null
        return (held.filter { it.requirement <= level }.maxByOrNull { it.power }
            ?: held.minByOrNull { it.requirement })?.id
    }

    fun statFor(kind: ResourceNodes.Kind): Stat = when (kind) {
        ResourceNodes.Kind.WOODCUTTING -> Stat.WOODCUTTING
        ResourceNodes.Kind.MINING -> Stat.MINING
        ResourceNodes.Kind.GATHERING -> Stat.FARMING
    }

    fun requirementFor(locId: Int, kind: ResourceNodes.Kind, itemName: String): Pair<Requirement, Boolean> {
        val cacheLevel = ResourceNodes.levelFromCache(locId)
        val table = when (kind) {
            ResourceNodes.Kind.WOODCUTTING -> WOODCUTTING_TABLE
            ResourceNodes.Kind.MINING -> MINING_TABLE
            ResourceNodes.Kind.GATHERING -> emptyMap()
        }
        val authored = table[itemName] ?: Requirement(
            when (kind) {
                ResourceNodes.Kind.WOODCUTTING -> DEFAULT_WOODCUTTING_LEVEL
                ResourceNodes.Kind.MINING -> DEFAULT_MINING_LEVEL
                ResourceNodes.Kind.GATHERING -> 1
            },
            DEFAULT_XP_TENTHS, "AUTHORED-DEFAULT", "DEFAULT"
        )
        val fixedXp = when (kind) {
            ResourceNodes.Kind.WOODCUTTING -> SkillingRates.WOODCUTTING_XP_TENTHS[itemName]
            ResourceNodes.Kind.MINING -> SkillingRates.MINING_XP_TENTHS[itemName]
            ResourceNodes.Kind.GATHERING -> null
        }
        val refEntry = if (SkillXpTable.enabled) SkillXpTable.requirementFor(kind, itemName) else null
        val miningLadder = if (kind == ResourceNodes.Kind.MINING) MiningLevels.levelForItem(itemName) else null
        val (level, levelSource) = when {
            cacheLevel != null -> cacheLevel to "CACHE"
            miningLadder != null -> miningLadder to "MINING-TABLE"
            refEntry?.level != null -> refEntry.level to "TABLE"
            else -> authored.level to authored.levelSource
        }
        val (xp, xpSource) = when {
            fixedXp != null -> fixedXp to "FIXED"
            refEntry != null -> refEntry.xpTenths to "TABLE"
            else -> authored.xpTenths to authored.xpSource
        }
        return Requirement(level, xp, levelSource, xpSource) to (cacheLevel != null)
    }

    fun depletes(kind: ResourceNodes.Kind): Boolean = kind == ResourceNodes.Kind.WOODCUTTING

    fun gather(ctx: LocContext, kind: ResourceNodes.Kind, fromCycle: Boolean = false): Gather {
        val locId = ctx.locId
        val locName = ctx.definition.name
        val stat = statFor(kind)

        if (!RsDatabase.available) {
            return Gather(Outcome.NO_DATABASE, locId, locName, kind, detail = "no rs3.sqlite")
        }
        if (!fromCycle) {
            val running = active[ctx.player]
            if (running != null && running.ctx.locId == locId && running.ctx.x == ctx.x && running.ctx.z == ctx.z &&
                running.ctx.plane == ctx.plane && tickCount < running.nextTick
            ) {
                return Gather(
                    Outcome.REPEATING, locId, locName, kind,
                    detail = "already gathering here; next cycle in ${running.nextTick - tickCount} tick(s)"
                )
            }
            if (running != null) stop(
                ctx.player,
                "re-clicked (${if (running.ctx.locId == locId) "same node, cycle due" else "a different node"})"
            )
        }

        val placement = LocInteraction.placementOf(locId, ctx.x, ctx.z, ctx.plane)
        val shape = placement?.type ?: -1
        val rotation = placement?.rot ?: 0
        val originX = placement?.originX ?: ctx.x
        val originZ = placement?.originZ ?: ctx.z
        val existing = depletionAt(ctx.plane, originX, originZ, shape)
        if (existing != null) {
            return Gather(
                Outcome.DEPLETED, locId, locName, kind,
                depletedTo = existing.depletedLocId, respawnAtTick = existing.respawnAtTick,
                detail = "still a stump for ${existing.respawnAtTick - tickCount} more tick(s)"
            )
        }

        val container = containerSupplier(ctx.player)
        val playerLevelOnce = levelSupplier(ctx.player, stat)
        val tool = toolIn(container, kind, playerLevelOnce, wornSupplier(ctx.player), beltSupplier(ctx.player))
        if (tool == null) {
            return Gather(
                Outcome.NO_TOOL, locId, locName, kind,
                detail = "no '*${if (kind == ResourceNodes.Kind.WOODCUTTING) HATCHET_SUFFIX else PICKAXE_SUFFIX}' in backpack, worn" +
                        (if (toolbelt) " or tool belt" else " (tool belt off)")
            )
        }

        val toolLevel = toolRequirement(tool)
        val playerLevel = playerLevelOnce
        if (toolLevel != null && playerLevel < toolLevel) {
            return Gather(
                Outcome.TOOL_LEVEL_TOO_LOW, locId, locName, kind, toolId = tool,
                playerLevel = playerLevel, levelRequired = toolLevel,
                detail = "tool ${itemNameOf(tool) ?: tool} needs level $toolLevel (item param 750)"
            )
        }
        val resolved = ResourceNodes.resolve(locId, kind)
        if (resolved !is ResourceNodes.Resolved) {
            return Gather(
                Outcome.NO_YIELD, locId, locName, kind, toolId = tool,
                detail = "no yield for this loc: $resolved"
            )
        }

        val (requirement, grounded) = requirementFor(locId, kind, resolved.itemName)
        if (playerLevel < requirement.level) {
            return Gather(
                Outcome.LEVEL_TOO_LOW, locId, locName, kind,
                itemId = resolved.itemId, itemName = resolved.itemName,
                levelRequired = requirement.level, levelFromCache = grounded,
                playerLevel = playerLevel, toolId = tool,
                detail = if (grounded) "requirement is the cache's param 23" else "requirement is AUTHORED"
            )
        }

        if (placement == null) {
            return Gather(
                Outcome.NO_PLACEMENT, locId, locName, kind,
                itemId = resolved.itemId, itemName = resolved.itemName,
                levelRequired = requirement.level, levelFromCache = grounded,
                playerLevel = playerLevel, toolId = tool,
                detail = "loc $locId not found at (${ctx.x},${ctx.z},plane ${ctx.plane})"
            )
        }

        if (container.isFull() && !(container.stacks(resolved.itemId) && container.count(resolved.itemId) > 0)) {
            return Gather(
                Outcome.NO_SPACE, locId, locName, kind,
                itemId = resolved.itemId, itemName = resolved.itemName,
                levelRequired = requirement.level, levelFromCache = grounded,
                playerLevel = playerLevel, toolId = tool,
                detail = "container full (${container.usedSlots()}/${container.size})"
            )
        }
        if (!fromCycle) {
            faceLoc(
                ctx.player, originX, originZ,
                placement.dx, placement.dz
            )
        }

        if (!fromCycle) {
            val first = firstCycleTicks(kind)
            if (first > 0) {
                val last = lastCycle[ctx.player]
                val due = maxOf(tickCount + first, (last ?: Long.MIN_VALUE) + CYCLE_TICKS)
                arm(ctx.player, Active(ctx, kind, whereIs(ctx.player), due, toolId = tool))
                if (due - 1 == tickCount) animationFor(kind, tool)?.let { ids ->
                    runCatching {
                        animationSink(
                            ctx.player,
                            ids
                        )
                    }; animationsSent++
                }
                startMessage(kind)?.let { msg -> runCatching { messageSink(ctx.player, msg) }; messagesSent++ }
                return Gather(
                    Outcome.STARTED, locId, locName, kind,
                    itemId = resolved.itemId, itemName = resolved.itemName,
                    levelRequired = requirement.level, levelFromCache = grounded,
                    playerLevel = playerLevel, toolId = tool,
                    detail = "first cycle in ${due - tickCount} tick(s)"
                )
            }
        }
        if (!fromCycle) {
            val last = lastCycle[ctx.player]
            if (last != null && tickCount - last < CYCLE_TICKS) {
                arm(ctx.player, Active(ctx, kind, whereIs(ctx.player), last + CYCLE_TICKS, toolId = tool))
                return Gather(
                    Outcome.REPEATING, locId, locName, kind,
                    itemId = resolved.itemId, itemName = resolved.itemName,
                    levelRequired = requirement.level, levelFromCache = grounded,
                    playerLevel = playerLevel, toolId = tool,
                    detail = "switched to this node inside the cycle; next cycle in ${last + CYCLE_TICKS - tickCount} tick(s)"
                )
            }
        }
        lastCycle[ctx.player] = tickCount
        val chance = successChance(
            SuccessContext(kind, resolved.itemName, playerLevel, requirement.level, tool, itemNameOf(tool), toolLevel)
        ).coerceIn(0.0, 1.0)
        if (random.nextDouble() >= chance) {
            cyclesMissed++
            val miss = Gather(
                Outcome.MISSED, locId, locName, kind,
                itemId = resolved.itemId, itemName = resolved.itemName,
                levelRequired = requirement.level, levelFromCache = grounded,
                playerLevel = playerLevel, toolId = tool,
                detail = "cycle missed at chance %.3f".format(chance)
            )
            if (!fromCycle) arm(
                ctx.player,
                Active(ctx, kind, whereIs(ctx.player), tickCount + CYCLE_TICKS, toolId = tool)
            )
            return miss
        }
        val add = container.add(resolved.itemId, 1)
        if (add.added == 0) {
            return Gather(
                Outcome.NO_SPACE, locId, locName, kind,
                itemId = resolved.itemId, itemName = resolved.itemName,
                levelRequired = requirement.level, levelFromCache = grounded,
                playerLevel = playerLevel, toolId = tool,
                detail = "container full (${container.usedSlots()}/${container.size})"
            )
        }

        record(ctx.player, stat, requirement.xpTenths)
        runCatching { xpSink(ctx.player, stat, requirement.xpTenths / 10.0) }
            .onFailure { logger.warn(it) { "skilling: xp sink threw; the award was still recorded" } }

        var depletedTo: Int? = null
        var respawnAt = -1L
        val fell = fellChance(resolved.itemName)
        val fellsNow = fell == null || random.nextDouble() < fell
        if (depletes(kind) && placement != null && fellsNow) {
            val stump = Stumps.stumpFor(locId)
            val newId = stump?.locId ?: REMOVE_LOC
            depletedTo = newId
            respawnAt = tickCount + RESPAWN_TICKS
            val key = LocChanges.Key(ctx.plane, originX, originZ, shape)
            val leaves = Canopy.above(placement, ctx.plane)
            val canopyRef = leaves?.let {
                RemovedCanopy(
                    LocChanges.Key(ctx.plane + 1, it.originX, it.originZ, it.type), it.locId, it.rot
                )
            }
            val record = Depletion(key, locId, newId, rotation, respawnAt, stump?.source, canopyRef)
            val already = synchronized(depleted) { depleted.putIfAbsent(key, record) }
            if (already != null) {
                logger.warn {
                    "skilling: loc $locId at $key felled twice in one tick; keeping respawn at ${already.respawnAtTick}"
                }
                depletedTo = already.depletedLocId
                respawnAt = already.respawnAtTick
            } else {
                sendLocChange(key, rotation, locId, newId, if (stump == null) "removed (no listed stump)" else "stump")
                if (canopyRef != null) {
                    sendCanopyRemoval(canopyRef)
                    logger.info {
                        "skilling: removed canopy loc ${canopyRef.locId} " +
                                "'${Canopy.nameOf(canopyRef.locId) ?: "?"}' at (${canopyRef.key.x}," +
                                "${canopyRef.key.y},plane ${canopyRef.key.plane}) shape ${canopyRef.key.shape} " +
                                "rot ${canopyRef.rotation}"
                    }
                } else logger.info {
                    "skilling: loc $locId at $key has no canopy on plane ${ctx.plane + 1}"
                }
                if (stump == null) logger.info {
                    "skilling: loc $locId '$locName' has no stump in data/seed/tree_stumps.tsv; " +
                            "removed for $RESPAWN_TICKS tick(s)"
                } else logger.info {
                    "skilling: loc $locId '$locName' -> stump ${stump.locId} [${stump.source}" +
                            (if (stump.sampleRows > 0) ", ${stump.sampleRows} sample(s)" else "") + "]"
                }
            }
            stop(ctx.player, "the tree fell (stump)")
        } else if (depletes(kind) && fellsNow) {
            logger.warn {
                "skilling: chopped loc $locId at (${ctx.x},${ctx.z},plane ${ctx.plane}) is not in map_loc; " +
                        "the tree was not replaced"
            }
        }

        val sent = runCatching { inventoryResend(ctx.player) }.getOrDefault(false)

        val result = Gather(
            Outcome.GATHERED, locId, locName, kind,
            itemId = resolved.itemId, itemName = resolved.itemName, amount = add.added,
            xpTenths = requirement.xpTenths, stat = stat, chance = chance,
            levelSource = requirement.levelSource, xpSource = requirement.xpSource,
            levelRequired = requirement.level, levelFromCache = grounded,
            playerLevel = playerLevel, toolId = tool,
            depletedTo = depletedTo, respawnAtTick = respawnAt,
            inventorySent = sent,
            detail = if (resolved.ambiguous) "ambiguous item name, ${resolved.candidates.size} ids ${resolved.candidates}, lowest wins" else ""
        )
        logger.info { "skilling: $result" }
        cyclesPaid++
        yieldMessage(kind, resolved.itemName)?.let { msg ->
            runCatching {
                messageSink(
                    ctx.player,
                    msg
                )
            }; messagesSent++
        }
        if (!fromCycle) {
            arm(ctx.player, Active(ctx, kind, whereIs(ctx.player), tickCount + CYCLE_TICKS, toolId = tool))
        }
        if (depletedTo != null) stop(ctx.player, "the node depleted")
        return result
    }

    private val toolRequirementMemo = java.util.concurrent.ConcurrentHashMap<Int, java.util.Optional<Int>>()
    fun toolRequirement(toolId: Int): Int? = toolRequirementMemo.computeIfAbsent(toolId) {
        java.util.Optional.ofNullable(toolRequirementUncached(toolId))
    }.orElse(null)

    private fun toolRequirementUncached(toolId: Int): Int? {
        val json = RsDatabase.queryOne("SELECT value FROM items_attr WHERE id = ? AND field = 'extra'", toolId) {
            it.getString(1)
        } ?: return null
        val params = com.opennxt.model.combat.NpcCombat.parseParams(json)
        val level = params[Names950.paramId("wear_requires_stat_level1")] ?: return null
        val skill = params[Names950.paramId("wear_requires_stat1")]
        return if (skill == null || skill == 0 || skill == Stat.MINING.id || skill == Stat.WOODCUTTING.id) level else null
    }

    fun onChop(ctx: LocContext): Any = gather(ctx, ResourceNodes.Kind.WOODCUTTING)

    fun onMine(ctx: LocContext): Any = gather(ctx, ResourceNodes.Kind.MINING)

    data class Installed(val chopDown: Int, val chop: Int, val mine: Int) {
        val total: Int get() = chopDown + chop + mine
    }

    fun install(): Installed {
        if (!enabled) {
            logger.warn { "skilling: disabled (-Dopennxt.experiment.skilling=false)" }
            return Installed(0, 0, 0)
        }
        val chopDownSpaced = ContentRegistry.onLocAction(ResourceNodes.CHOP_DOWN, ::onChop)
        val chopDownHyphen = try {
            ContentRegistry.onLocAction(ResourceNodes.CHOP_DOWN_HYPHEN, ::onChop)
        } catch (e: IllegalArgumentException) {
            logger.info { "skilling: no loc declares '${ResourceNodes.CHOP_DOWN_HYPHEN}' in this cache" }
            0
        }
        val chopDown = chopDownSpaced + chopDownHyphen
        val chop = ContentRegistry.onLocAction(ResourceNodes.CHOP, ::onChop)
        val mine = ContentRegistry.onLocAction(ResourceNodes.MINE, ::onMine)
        logger.info {
            "skilling: bound '${ResourceNodes.CHOP_DOWN}' across $chopDownSpaced locs, " +
                    "'${ResourceNodes.CHOP_DOWN_HYPHEN}' across $chopDownHyphen, " +
                    "'${ResourceNodes.CHOP}' across $chop, '${ResourceNodes.MINE}' across $mine"
        }
        runCatching {
            val cov = Stumps.coverage()
            val fixedValue = cov.count { it.value.source == Stumps.Source.FIXED }
            val run = cov.count { it.value.source == Stumps.Source.RUN }
            val forced = cov.count { it.value.source == Stumps.Source.FORCED }
            val choppable = chopDown + chop
            logger.info {
                "skilling stumps: ${cov.size} of $choppable choppable locs have a stump " +
                        "(fixed $fixedValue, run $run, forced $forced)"
            }
        }.onFailure { logger.warn(it) { "skilling stumps: the coverage line could not be derived" } }
        return Installed(chopDown, chop, mine)
    }
}

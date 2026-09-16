package com.opennxt.model.world

import com.opennxt.content.ContentPlayer
import com.opennxt.model.combat.NpcCombat
import com.opennxt.model.combat.NpcCombatDefinition
import com.opennxt.model.vars.VarPlayerState
import com.opennxt.model.vars.isPlayerDomain
import com.opennxt.model.vars.varpId
import com.opennxt.resources.sqlite.NpcDefinition
import com.opennxt.resources.sqlite.RsDatabase
import com.opennxt.resources.sqlite.SqliteNpcCodec
import com.google.gson.JsonParser
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap
import it.unimi.dsi.fastutil.ints.IntOpenHashSet
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

object NpcMorph {
    private val logger = mu.KotlinLogging.logger { }

    const val FLAG = "opennxt.npcs.morph"
    const val PER_PLAYER_FLAG = "opennxt.npcs.morph.perplayer"

    val enabled: Boolean
        get() = System.getProperty(FLAG)?.let { !it.equals("off", true) && !it.equals("false", true) } ?: true

    val perPlayerEnabled: Boolean
        get() = System.getProperty(PER_PLAYER_FLAG)?.let { !it.equals("off", true) && !it.equals("false", true) } ?: true

    const val MOVEMENT_FLAG = "opennxt.npcs.morph.movement"

    val movementFillEnabled: Boolean
        get() = System.getProperty(MOVEMENT_FLAG)?.let { it.equals("on", true) || it.equals("true", true) } ?: false

    const val SENTINEL = 0xffff

    const val MAX_DEPTH = 4

    private const val LOG_LIMIT = 3

    class Record(
        val id: Int,
        val varbit: Int?,
        val varp: Int?,
        val options: IntArray,
        val default: Int,
        val extra: Int?
    ) {
        override fun toString() =
            "NpcMorph.Record($id sel=${varbit?.let { "varbit $it" } ?: varp?.let { "varp $it" } ?: "none"} " +
                "${options.size} option(s) default=$default)"
    }

    private val cStubsSpawned = AtomicLong()
    private val cResolved = AtomicLong()
    private val cUnresolved = AtomicLong()
    private val cLoops = AtomicLong()
    private val cSizeDisagreements = AtomicLong()
    private val cPerPlayerDiffered = AtomicLong()
    private var loggedUnresolved = 0
    private var loggedLoops = 0
    private var loggedSize = 0
    private var loggedPerPlayer = 0

    val stubsSpawned: Long get() = cStubsSpawned.get()

    val resolved: Long get() = cResolved.get()

    val unresolved: Long get() = cUnresolved.get()

    val loopsRefused: Long get() = cLoops.get()

    val sizeDisagreements: Long get() = cSizeDisagreements.get()

    val perPlayerDiffered: Long get() = cPerPlayerDiffered.get()

    fun resetCounters() {
        cStubsSpawned.set(0); cResolved.set(0); cUnresolved.set(0); cLoops.set(0)
        cSizeDisagreements.set(0); cPerPlayerDiffered.set(0)
        loggedUnresolved = 0; loggedLoops = 0; loggedSize = 0; loggedPerPlayer = 0
    }

    internal fun countStubSpawn() = cStubsSpawned.incrementAndGet()

    private class Table(
        val morphs: Int2ObjectOpenHashMap<Record>,
        val knownIds: IntOpenHashSet,
        val declaresSize: IntOpenHashSet
    )

    private val table: Table by lazy {
        val morphs = Int2ObjectOpenHashMap<Record>()
        val known = IntOpenHashSet()
        val sized = IntOpenHashSet()
        if (RsDatabase.available) {
            runCatching {
                RsDatabase.queryAll("SELECT id, boundSize FROM npcs") { rs ->
                    val size = rs.getInt(2)
                    val absent = rs.wasNull()
                    val id = rs.getInt(1)
                    known.add(id)
                    if (!absent && size > 0) sized.add(id)
                }
                RsDatabase.queryAll(
                    "SELECT id, field, value FROM npcs_attr WHERE field IN ('morphs_1', 'morphs_2')"
                ) { rs ->
                    val id = rs.getInt(1)
                    parse(id, rs.getString(3))?.let { morphs.put(id, it) }
                }
            }.onFailure {
                logger.warn { "npc morph table unreadable (${it.message}); every npc id resolves to itself" }
                morphs.clear(); known.clear(); sized.clear()
            }
        }
        Table(morphs, known, sized)
    }

    private fun parse(id: Int, json: String?): Record? {
        if (json == null) return null
        return runCatching {
            val o = JsonParser().parse(json).asJsonObject
            val varbit = o.get("varbit")?.takeIf { it.isJsonPrimitive }?.asInt?.takeIf { it != SENTINEL }
            val varp = o.get("varp")?.takeIf { it.isJsonPrimitive }?.asInt?.takeIf { it != SENTINEL }
            val extra = o.get("unk2")?.takeIf { it.isJsonPrimitive }?.asInt?.takeIf { it != SENTINEL }
            val arr = o.getAsJsonArray("options")
            val options = IntArray(arr?.size() ?: 0) { arr!!.get(it).asInt }
            val default = o.get("default")?.takeIf { it.isJsonPrimitive }?.asInt ?: SENTINEL
            Record(id, varbit, varp, options, default, extra)
        }.onFailure {
            logger.warn { "npcs_attr morph row for npc $id did not parse (${it.message}); treating $id as a plain npc" }
        }.getOrNull()
    }

    fun warm(): Int = table.morphs.size

    fun stubCount(): Int = table.morphs.size

    fun stubIds(): Set<Int> = table.morphs.keys.toIntArray().toSortedSet()

    fun isStub(id: Int): Boolean = table.morphs.containsKey(id) && enabled

    fun record(id: Int): Record? = table.morphs.get(id)

    fun known(id: Int): Boolean = table.knownIds.contains(id)

    fun declaresSize(id: Int): Boolean = table.declaresSize.contains(id)

    fun effectiveId(baseId: Int): Int = resolveWith(baseId) { it.default }

    fun effectiveIdFor(player: ContentPlayer, baseId: Int): Int = effectiveIdFor(player.vars, baseId)

    fun effectiveIdFor(vars: VarPlayerState?, baseId: Int): Int {
        if (!enabled || !perPlayerEnabled || vars == null) return effectiveId(baseId)
        val byDefault = effectiveId(baseId)
        val byPlayer = resolveWith(baseId) { pick(vars, it) }
        if (byPlayer != byDefault) {
            cPerPlayerDiffered.incrementAndGet()
            if (loggedPerPlayer < LOG_LIMIT) {
                loggedPerPlayer++
                logger.info {
                    "npc morph: npc $baseId resolves to $byPlayer for this player, $byDefault by default (${record(baseId)})"
                }
            }
        }
        return byPlayer
    }

    private fun pick(vars: VarPlayerState, r: Record): Int {
        val value = when {
            r.varbit != null -> {
                val def = vars.definition(r.varbit) ?: return r.default
                if (!def.isWellFormed || !def.isPlayerDomain) return r.default
                val varp = vars.getVarp(def.varpId)
                if (varp == 0) return r.default
                def.read(varp)
            }
            r.varp != null -> vars.getVarp(r.varp)
            else -> return r.default
        }
        if (value <= 0 || value >= r.options.size) return r.default
        val picked = r.options[value]
        return if (picked == SENTINEL) r.default else picked
    }

    fun resolveWith(baseId: Int, choose: (Record) -> Int): Int {
        if (!table.morphs.containsKey(baseId)) return baseId
        if (!enabled) return baseId
        var current = baseId
        var seen: MutableSet<Int>? = null
        var depth = 0
        while (true) {
            val r = table.morphs.get(current) ?: return if (current == baseId) baseId else current.also { cResolved.incrementAndGet() }
            if (depth++ >= MAX_DEPTH) return refuseLoop(baseId, current, "chain longer than $MAX_DEPTH")
            if (seen == null) seen = HashSet(4)
            if (!seen.add(current)) return refuseLoop(baseId, current, "chain revisits npc $current")
            val next = choose(r)
            if (next == SENTINEL) return refuseUnresolved(baseId, current, "its default is the 0xffff sentinel")
            if (!table.knownIds.contains(next)) return refuseUnresolved(baseId, current, "target npc $next is not in the definitions")
            current = next
        }
    }

    private fun refuseLoop(baseId: Int, at: Int, why: String): Int {
        cLoops.incrementAndGet()
        if (loggedLoops < LOG_LIMIT) {
            loggedLoops++
            logger.warn { "npc morph: loop resolving npc $baseId at $at ($why); using the base id" }
        }
        return baseId
    }

    private fun refuseUnresolved(baseId: Int, at: Int, why: String): Int {
        cUnresolved.incrementAndGet()
        if (loggedUnresolved < LOG_LIMIT) {
            loggedUnresolved++
            logger.warn { "npc morph: npc $baseId does not resolve at $at ($why); using the base id" }
        }
        return baseId
    }

    private val definitionMemo = ConcurrentHashMap<Int, NpcDefinition>()

    fun definition(baseId: Int): NpcDefinition? {
        definitionMemo[baseId]?.let { return it }
        val base = SqliteNpcCodec.load(baseId) ?: return null
        val merged = definition(base) { SqliteNpcCodec.load(it) }
        definitionMemo[baseId] = merged
        return merged
    }

    fun definition(base: NpcDefinition, lookup: (Int) -> NpcDefinition?): NpcDefinition =
        definition(base, effectiveId(base.id), lookup)

    fun definition(base: NpcDefinition, targetId: Int, lookup: (Int) -> NpcDefinition?): NpcDefinition {
        if (!enabled) return base
        if (targetId == base.id) return base
        val t = lookup(targetId) ?: return base
        if (declaresSize(base.id) && base.size != t.size) {
            cSizeDisagreements.incrementAndGet()
            if (loggedSize < LOG_LIMIT) {
                loggedSize++
                logger.info {
                    "npc morph: stub ${base.id} size ${base.size} differs from target $targetId size ${t.size}; using the stub's"
                }
            }
        }
        val actions = arrayOfNulls<String>(maxOf(base.actions.size, t.actions.size))
        for (i in actions.indices) actions[i] = base.actions.getOrNull(i) ?: t.actions.getOrNull(i)
        return NpcDefinition(
            id = targetId,
            name = base.name ?: t.name,
            size = if (declaresSize(base.id)) base.size else t.size,
            combatLevel = base.combatLevel?.takeIf { it != 0 } ?: t.combatLevel,
            movementType = base.movementType ?: t.movementType,
            animationGroup = base.animationGroup ?: t.animationGroup,
            drawMapDot = base.drawMapDot,
            actions = actions
        )
    }

    fun combat(baseId: Int): NpcCombatDefinition? {
        val base = NpcCombat.load(baseId) ?: return null
        if (!enabled) return base
        val targetId = effectiveId(baseId)
        if (targetId == baseId) return base
        val merged = definition(baseId) ?: return base
        val target = NpcCombat.load(targetId) ?: return NpcCombatDefinition(merged, base.params)
        if (base.params.isEmpty()) return NpcCombatDefinition(merged, target.params)
        val params = LinkedHashMap(target.params)
        params.putAll(base.params)
        return NpcCombatDefinition(merged, params)
    }

    fun forget() = definitionMemo.clear()

    fun statusLine(): String =
        if (!enabled) "npc morph: disabled (-D$FLAG=off)"
        else "npc morph: ${stubCount()} stub ids, " +
            "spawned $stubsSpawned, resolved $resolved, unresolved $unresolved, loops refused $loopsRefused, " +
            "size disagreements $sizeDisagreements, per-player differences $perPlayerDiffered" +
            (if (perPlayerEnabled) "" else " (per-player resolution off)")
}

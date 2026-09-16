package com.opennxt.model.world

import com.opennxt.model.entity.updating.NpcInfoEncoder
import mu.KotlinLogging

object SpawnActivation {
    private val logger = KotlinLogging.logger { }

    val enabled: Boolean
        get() = System.getProperty("opennxt.experiment.npcs.worldspawns")
            ?.let { !it.equals("off", true) && !it.equals("false", true) } ?: true

    val radius: Int
        get() = System.getProperty("opennxt.npcs.worldspawns.radius")?.toIntOrNull()?.coerceIn(0, 4) ?: 1

    const val DEACTIVATE_GRACE_TICKS = 50

    const val MAX_SQUARES_PER_TICK = 2

    const val MAX_ROWS_PER_TICK = 32

    const val MAX_DEACTIVATE_ROWS_PER_TICK = 160

    val liveCap: Int
        get() = System.getProperty("opennxt.npcs.worldspawns.cap")
            ?.let { if (it.equals("off", true)) 0 else it.toIntOrNull() ?: MAX_LIVE_NPCS }
            ?: MAX_LIVE_NPCS

    const val MAX_LIVE_NPCS = 1250

    const val QUARANTINE_SCAN_PER_TICK = 256

    const val SLOT_QUARANTINE_TICKS = 100

    private val SCENE_ORDER = listOf(
        0 to 0,
        -1 to 0, 1 to 0, 0 to -1, 0 to 1,
        -1 to -1, 1 to -1, -1 to 1, 1 to 1
    )

    private class Entry(val row: WorldSpawns.Row, val npc: WorldNpc)

    private class Activated(
        val square: Int,
        val plane: Int,
        val rows: List<WorldSpawns.Row>,
        var lastWantedTick: Long
    ) {
        val entries: MutableList<Entry> = ArrayList(rows.size)
        var cursor: Int = 0
        val pending: Int get() = rows.size - cursor
    }

    private class Lingering(val key: Long, val row: WorldSpawns.Row, val npc: WorldNpc)

    private class Freed(val index: Int, val freedAtTick: Long)

    private val active = LinkedHashMap<Long, Activated>()
    private val quarantine = ArrayDeque<Freed>()
    private val free = ArrayDeque<Int>()

    private val lingering = ArrayList<Lingering>()

    private var clock = 0L

    var activations = 0L; private set
    var deactivations = 0L; private set
    var npcsSpawned = 0L; private set
    var npcsDespawned = 0L; private set
    var slotsReused = 0L; private set
    var slotsQuarantined = 0L; private set
    var refusals = 0L; private set
    var lingeringRetained = 0L; private set

    var duplicateRowsSkipped = 0L; private set

    var capDeactivations = 0L; private set

    var deferredByBudget = 0L; private set
    var deferredByCap = 0L; private set

    var quarantineExamined = 0L; private set

    fun ticks(): Long = clock
    fun activeSquares(): Int = active.size
    fun liveCount(): Int = active.values.sumOf { it.entries.size } + lingering.size
    fun lingeringNow(): Int = lingering.size
    fun pendingRows(): Int = active.values.sumOf { it.pending }

    fun quarantinedSlots(): Int = quarantine.size
    fun freeSlots(): Int = free.size

    fun activeKeys(): List<Long> = active.keys.toList()

    fun npcsIn(square: Int, plane: Int): List<WorldNpc> =
        active[WorldSpawns.key(square, plane)]?.entries?.map { it.npc } ?: emptyList()

    fun isActive(square: Int, plane: Int): Boolean = active.containsKey(WorldSpawns.key(square, plane))

    private fun lingeringRowsIn(key: Long): Set<WorldSpawns.Row> {
        if (lingering.isEmpty()) return emptySet()
        val out: MutableSet<WorldSpawns.Row> =
            java.util.Collections.newSetFromMap(java.util.IdentityHashMap())
        for (l in lingering) if (l.key == key) out.add(l.row)
        return out
    }

    fun warm(): String {
        if (!WorldSpawns.loaded) return "worldspawns: nothing to warm - the seed is not loaded"
        val ids = WorldSpawns.all().filter { it.activatable }.mapTo(HashSet()) { it.npcId }
        val stmts = com.opennxt.model.combat.NpcCombat.warm(ids)
        val poses = NpcWalkPose.warm(ids)
        return "worldspawns: warmed ${ids.size} npc id(s), $stmts sqlite statement(s), $poses walking pose(s)"
    }

    fun clear(npcs: WorldNpcs?) {
        val victims = ArrayList<WorldNpc>(liveCount())
        for (a in active.values) for (e in a.entries) victims.add(e.npc)
        for (l in lingering) victims.add(l.npc)
        npcs?.despawnAll(victims)
        active.clear(); lingering.clear(); quarantine.clear(); free.clear(); capCooldown.clear()
        clock = 0L
        activations = 0; deactivations = 0; npcsSpawned = 0; npcsDespawned = 0
        slotsReused = 0; slotsQuarantined = 0; refusals = 0; lingeringRetained = 0
        duplicateRowsSkipped = 0; capDeactivations = 0; deferredByBudget = 0; deferredByCap = 0
        quarantineExamined = 0
        deactivationsBefore = 0
    }

    fun tick(npcs: WorldNpcs, tiles: List<TileLocation>) {
        clock++
        if (!enabled) return
        if (!WorldSpawns.loaded) return

        releaseQuarantine()
        sweepLingering(npcs)

        val wanted = LinkedHashSet<Long>()
        val wantedSquares = ArrayList<IntArray>()
        val r = radius
        val rotated = if (tiles.size <= 1) tiles else {
            val start = (clock % tiles.size).toInt()
            tiles.subList(start, tiles.size) + tiles.subList(0, start)
        }
        for (t in rotated) {
            val sx = t.x / 64
            val sy = t.y / 64
            for ((dx, dy) in SCENE_ORDER) {
                if (dx < -r || dx > r || dy < -r || dy > r) continue
                val nx = sx + dx
                val ny = sy + dy
                if (nx < 0 || ny < 0 || nx > 127 || ny > 255) continue
                val square = nx or (ny shl 7)
                if (wanted.add(WorldSpawns.key(square, t.plane))) wantedSquares.add(intArrayOf(square, t.plane))
            }
            if (r > 1) {
                for (dy in -r..r) for (dx in -r..r) {
                    if (dx in -1..1 && dy in -1..1) continue
                    val nx = sx + dx
                    val ny = sy + dy
                    if (nx < 0 || ny < 0 || nx > 127 || ny > 255) continue
                    val square = nx or (ny shl 7)
                    if (wanted.add(WorldSpawns.key(square, t.plane))) wantedSquares.add(intArrayOf(square, t.plane))
                }
            }
        }

        for (k in wanted) active[k]?.lastWantedTick = clock

        if (active.isNotEmpty()) {
            val expired = active.entries
                .filter { clock - it.value.lastWantedTick >= DEACTIVATE_GRACE_TICKS }
                .sortedBy { it.value.lastWantedTick }
                .map { it.key }
            var rowsSpent = 0
            val going = ArrayList<Long>(expired.size)
            for (k in expired) {
                if (rowsSpent > 0 && rowsSpent >= MAX_DEACTIVATE_ROWS_PER_TICK) break
                rowsSpent += active[k]?.entries?.size ?: 0
                going.add(k)
            }
            deactivate(npcs, going, "grace")
        }

        val cap = liveCap
        val standing = HashSet<Long>()
        for (t in tiles) standing.add(WorldSpawns.key((t.x / 64) or ((t.y / 64) shl 7), t.plane))
        if (cap > 0 && liveCount() > cap) {
            makeRoom(npcs, cap, 0, standing, wanted)
            if (liveCount() > cap) {
                logger.warn {
                    "worldspawns: ${liveCount()} live npcs exceed the cap of $cap; new squares deferred " +
                        "(-Dopennxt.npcs.worldspawns.cap)"
                }
            }
        }

        var squareBudget = MAX_SQUARES_PER_TICK
        var rowBudget = MAX_ROWS_PER_TICK
        val activationsBefore = activations

        if (pendingRows() > 0) {
            for (rec in active.values.sortedBy { it.lastWantedTick }) {
                if (rowBudget <= 0) break
                if (rec.pending == 0) continue
                rowBudget -= fill(npcs, rec, rowBudget)
            }
        }

        for (sp in wantedSquares) {
            if (squareBudget <= 0 || rowBudget <= 0) break
            val k = WorldSpawns.key(sp[0], sp[1])
            if (active.containsKey(k)) continue
            val cooldown = capCooldown[k]
            if (cooldown != null) {
                if (clock < cooldown) { deferredByCap++; continue }
                capCooldown.remove(k)
            }
            val rows = WorldSpawns.activatableInSquare(sp[0], sp[1]).size
            if (rows == 0) continue
            if (cap > 0 && liveCount() + rows > cap) {
                makeRoom(npcs, cap, rows, standing, wanted)
                if (liveCount() + rows > cap) { deferredByCap++; continue }
            }
            val spent = activate(npcs, sp[0], sp[1], rowBudget)
            if (spent < 0) continue
            squareBudget--
            rowBudget -= spent
        }
        if (rowBudget <= 0 && (pendingRows() > 0 || wantedSquares.any { !active.containsKey(WorldSpawns.key(it[0], it[1])) })) {
            deferredByBudget++
        }

        if (activations != activationsBefore || deactivations != deactivationsBefore) {
            deactivationsBefore = deactivations
            logger.info { statusLine() }
        }
    }

    private var deactivationsBefore = 0L

    private fun activate(npcs: WorldNpcs, square: Int, plane: Int, budget: Int): Int {
        val all = WorldSpawns.activatableInSquare(square, plane)
        if (all.isEmpty()) return -1
        val key = WorldSpawns.key(square, plane)
        val held = lingeringRowsIn(key)
        val rows: List<WorldSpawns.Row> = if (held.isEmpty()) all else all.filter { it !in held }
        if (held.isNotEmpty()) {
            duplicateRowsSkipped += (all.size - rows.size).toLong()
            logger.info {
                "worldspawns: square $square plane $plane skipped ${all.size - rows.size} row(s) " +
                    "still in combat from the last deactivation"
            }
        }
        val rec = Activated(square, plane, rows, clock)
        active[key] = rec
        activations++
        val spent = fill(npcs, rec, budget)
        logger.info {
            "worldspawns: activated square $square plane $plane - ${rec.entries.size} of " +
                "${rec.rows.size} row(s) this tick, ${rec.pending} pending; " +
                "${active.size} active square(s), ${liveCount()} live, ${free.size} free slot(s)"
        }
        return spent
    }

    private fun fill(npcs: WorldNpcs, rec: Activated, budget: Int): Int {
        if (budget <= 0 || rec.pending == 0) return 0
        val take = minOf(budget, rec.pending)
        val rows = rec.rows.subList(rec.cursor, rec.cursor + take)
        val slots = ArrayList<Int?>(take)
        val requests = ArrayList<WorldNpcs.SpawnRequest>(take)
        for (row in rows) {
            val slot = takeSlot()
            slots.add(slot)
            requests.add(WorldNpcs.SpawnRequest(row.npcId, row.tile, slot))
        }
        val made = runCatching { npcs.spawnNpcsAt(requests) }
            .onFailure {
                refusals += take.toLong()
                logger.warn(it) {
                    "worldspawns: could not spawn $take row(s) in square ${rec.square}/${rec.plane}"
                }
            }
            .getOrDefault(emptyList())
        if (made.size != take) {
            for (s in slots) if (s != null) free.addFirst(s)
            rec.cursor += take
            return take
        }
        for (i in 0 until take) {
            val slot = slots[i]
            if (slot != null && made[i].infoIndex == slot) slotsReused++
            else if (slot != null) free.addFirst(slot)
            rec.entries.add(Entry(rows[i], made[i]))
        }
        rec.cursor += take
        npcsSpawned += take.toLong()
        return take
    }

    private fun deactivate(npcs: WorldNpcs, key: Long, why: String) = deactivate(npcs, listOf(key), why)

    private fun deactivate(npcs: WorldNpcs, keys: List<Long>, why: String) {
        if (keys.isEmpty()) return
        val victims = ArrayList<WorldNpc>()
        val given = ArrayList<Activated>(keys.size)
        for (key in keys) {
            val rec = active.remove(key) ?: continue
            given.add(rec)
            for (e in rec.entries) {
                if (stillBusy(e.npc)) {
                    lingering.add(Lingering(key, e.row, e.npc))
                    lingeringRetained++
                    continue
                }
                victims.add(e.npc)
            }
            deactivations++
        }
        if (given.isEmpty()) return
        val removed = despawnAndQuarantine(npcs, victims)
        logger.info {
            "worldspawns: deactivated ${given.size} square(s) ($why) - $removed despawned, " +
                "${victims.size - removed} refused by the world, " +
                "${given.sumOf { it.entries.size } - victims.size} retained in combat; " +
                given.joinToString(", ", limit = 6) { "${it.square}/${it.plane}x${it.entries.size}" } +
                "; ${active.size} active square(s), ${quarantine.size} slot(s) in quarantine"
        }
    }

    private fun makeRoom(
        npcs: WorldNpcs,
        cap: Int,
        headroom: Int,
        standing: Set<Long>,
        wanted: Set<Long>
    ): Int {
        if (cap <= 0) return 0
        var given = 0
        val victims = active.entries
            .filter { it.key !in standing }
            .sortedWith(compareBy({ if (it.key in wanted) 1 else 0 }, { it.value.lastWantedTick }))
            .map { it.key }
        for (k in victims) {
            if (liveCount() + headroom <= cap) break
            capDeactivations++
            given++
            capCooldown[k] = clock + DEACTIVATE_GRACE_TICKS
            deactivate(npcs, k, "world cap $cap")
        }
        return given
    }

    private val capCooldown = HashMap<Long, Long>()

    private fun stillBusy(npc: WorldNpc): Boolean =
        com.opennxt.model.combat.PlayerCombat.isInCombat(npc) ||
            com.opennxt.content.ability.AbilityActivation.hasQueuedHitOn(npc)

    private fun despawnAndQuarantine(npcs: WorldNpcs, victims: List<WorldNpc>): Int {
        if (victims.isEmpty()) return 0
        val removed = npcs.despawnAll(victims)
        if (removed.isEmpty()) return 0
        npcsDespawned += removed.size.toLong()
        for (n in removed) {
            val index = n.infoIndex
            if (index >= 0) {
                quarantine.addLast(Freed(index, clock))
                slotsQuarantined++
            }
        }
        return removed.size
    }

    private fun sweepLingering(npcs: WorldNpcs) {
        if (lingering.isEmpty()) return
        val done = ArrayList<WorldNpc>()
        val it = lingering.iterator()
        while (it.hasNext()) {
            val l = it.next()
            if (stillBusy(l.npc)) continue
            it.remove()
            done.add(l.npc)
        }
        despawnAndQuarantine(npcs, done)
    }

    private fun releaseQuarantine() {
        if (quarantine.isEmpty()) return
        val held = NpcInfoEncoder.heldIndexes()
        val examine = minOf(quarantine.size, QUARANTINE_SCAN_PER_TICK)
        quarantineExamined += examine.toLong()
        repeat(examine) {
            val f = quarantine.removeFirst()
            when {
                clock - f.freedAtTick < SLOT_QUARANTINE_TICKS -> quarantine.addLast(f)
                held.contains(f.index) -> quarantine.addLast(f)
                else -> free.addLast(f.index)
            }
        }
    }

    private fun takeSlot(): Int? = if (free.isEmpty()) null else free.removeFirst()

    fun counters(): Map<String, Long> = linkedMapOf(
        "tick" to clock,
        "active_squares" to active.size.toLong(),
        "live_npcs" to liveCount().toLong(),
        "activations" to activations,
        "deactivations" to deactivations,
        "npcs_spawned" to npcsSpawned,
        "npcs_despawned" to npcsDespawned,
        "lingering_now" to lingering.size.toLong(),
        "lingering_retained" to lingeringRetained,
        "duplicate_rows_skipped" to duplicateRowsSkipped,
        "cap_deactivations" to capDeactivations,
        "deferred_by_budget" to deferredByBudget,
        "deferred_by_cap" to deferredByCap,
        "quarantine_examined" to quarantineExamined,
        "slots_quarantined" to slotsQuarantined,
        "slots_in_quarantine" to quarantine.size.toLong(),
        "slots_free" to free.size.toLong(),
        "slots_reused" to slotsReused,
        "refusals" to refusals
    )

    fun statusLine(): String =
        "worldspawns: " + counters().entries.joinToString(", ") { "${it.key}=${it.value}" }
}

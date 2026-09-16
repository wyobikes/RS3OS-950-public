package com.opennxt.model.world

import com.opennxt.OpenNXT
import com.opennxt.net.game.serverprot.LocAddChange
import com.opennxt.net.game.serverprot.LocDel
import com.opennxt.net.game.serverprot.UpdateZonePartialFollows
import com.opennxt.net.game.serverprot.ZoneCoord
import com.opennxt.resources.sqlite.RsDatabase
import com.opennxt.resources.sqlite.SqliteLocCodec
import mu.KotlinLogging
import java.util.Collections
import java.util.WeakHashMap

object LocChanges {
    private val logger = KotlinLogging.logger { }

    val enabled: Boolean = System.getProperty("opennxt.experiment.locChanges") != "false"

    fun swingStateLine(): String =
        "doors.flags[state]: opennxt.experiment.locChanges=${if (enabled) "ON" else "OFF"} " +
            "opennxt.experiment.doors.swing=${DoorSwing.mode.id.uppercase()} " +
            "(${DoorSwing.mode.why})"

    fun logSwingStateOnce() {
        if (swingStateLogged) return
        synchronized(this) {
            if (swingStateLogged) return
            swingStateLogged = true
            logger.info { swingStateLine() }
        }
    }

    @Volatile
    private var swingStateLogged = false

    internal fun rearmSwingState() {
        swingStateLogged = false
    }

    data class Key(val plane: Int, val x: Int, val y: Int, val shape: Int)

    data class Change(
        val key: Key,
        val originalId: Int,
        val currentId: Int,
        val rotation: Int,
        val forcedSwingMode: DoorSwing.Mode? = null,
        val removal: Boolean = false,
        val crossPlane: Boolean = false
    ) {
        val swing: DoorSwing.Placement = if (forcedSwingMode != null) {
            DoorSwing.Placement(key.x, key.y,
                when (forcedSwingMode) {
                    DoorSwing.Mode.TURN -> (rotation + 1) and 3
                    DoorSwing.Mode.TURN_BACK -> (rotation + 3) and 3
                    else -> rotation
                }, forcedSwingMode)
        } else {
            DoorSwing.placementFor(key.shape, key.x, key.y, rotation)
        }

        val swingMoved: Boolean
            get() = swing.x != key.x || swing.y != key.y || swing.rotation != rotation

        val swingToken: String
            get() = "swing[${swing.mode.id.uppercase()}]"
    }

    private val changes = LinkedHashMap<Key, Change>()

    fun all(): List<Change> = synchronized(changes) { changes.values.toList() }

    fun changeAt(plane: Int, x: Int, y: Int, shape: Int): Change? =
        synchronized(changes) { changes[Key(plane, x, y, shape)] }

    internal fun clear() {
        synchronized(changes) { changes.clear() }
        sent.clear()
    }

    enum class OpenVariantRule { OFFSET, TWIN }

    data class OpenVariant(
        val shutId: Int,
        val openId: Int,
        val rule: OpenVariantRule,
        val candidates: Int,
        val modelsIdentical: Boolean
    )

    fun openVariant(locId: Int): OpenVariant? {
        if (!RsDatabase.available) return null
        return variantMemo.getOrPut(locId) { resolveOpenVariant(locId) ?: UNRESOLVED }
            .takeIf { it !== UNRESOLVED }
    }

    fun openVariantOf(locId: Int): Int? = openVariant(locId)?.openId

    private fun resolveOpenVariant(locId: Int): OpenVariant? {
        val shut = SqliteLocCodec.load(locId) ?: return null
        if (shut.actions.none { it == OPEN }) return null

        val offsetId = locId + openOffset
        val offsetDef = SqliteLocCodec.load(offsetId)
        if (offsetDef != null && offsetDef.name == shut.name && offsetDef.actions.any { it == CLOSE }) {
            return OpenVariant(locId, offsetId, OpenVariantRule.OFFSET, 1, modelsOf(locId) == modelsOf(offsetId))
        }

        val shutKey = twinKey(locId) ?: return null
        if (shutKey.name == null || shutKey.models == null) return null
        val candidates = closeTwins[shutKey]
            ?.filter { it != locId && it !in placedLocIds }
            ?.filter { SqliteLocCodec.load(it)?.actions?.any { a -> a == CLOSE } == true }
            ?: return null
        if (candidates.isEmpty()) return null
        val chosen = candidates.minWithOrNull(
            compareBy({ Math.abs(it - locId) }, { if (it > locId) 0 else 1 }, { it })
        ) ?: return null
        return OpenVariant(locId, chosen, OpenVariantRule.TWIN, candidates.size, true)
    }

    private fun modelsOf(locId: Int): String? =
        RsDatabase.queryOne("SELECT value FROM locs_attr WHERE id = ? AND field = 'models'", locId) {
            it.getString(1)
        }

    private data class TwinKey(val name: String?, val group: Int, val models: String?)

    private fun twinKey(locId: Int): TwinKey? = RsDatabase.queryOne(
        "SELECT l.name AS name, l._group AS grp, " +
            "(SELECT value FROM locs_attr WHERE id = l.id AND field = 'models') AS models " +
            "FROM locs l WHERE l.id = ?", locId
    ) { TwinKey(it.getString("name"), it.getInt("grp"), it.getString("models")) }

    private val placedLocIds: Set<Int> by lazy {
        if (!RsDatabase.available) emptySet()
        else HashSet(RsDatabase.queryAll("SELECT DISTINCT loc_id FROM map_loc") { it.getInt(1) })
    }

    private val closeTwins: Map<TwinKey, List<Int>> by lazy {
        if (!RsDatabase.available) emptyMap()
        else RsDatabase.queryAll(
            "SELECT l.id AS id, l.name AS name, l._group AS grp, " +
                "(SELECT value FROM locs_attr WHERE id = l.id AND field = 'models') AS models " +
                "FROM locs l WHERE l.id IN (" +
                "SELECT id FROM locs WHERE actions_0 = 'Close' " +
                "UNION SELECT id FROM locs_attr WHERE field LIKE 'actions_%' AND value = '\"Close\"')"
        ) { TwinKey(it.getString("name"), it.getInt("grp"), it.getString("models")) to it.getInt("id") }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, ids) -> ids.sorted() }
    }

    fun locsDeclaring(action: String): List<Int> {
        if (!RsDatabase.available) return emptyList()
        val quoted = "\"$action\""
        return RsDatabase.queryAll(
            "SELECT id FROM locs WHERE actions_0 = '$action' " +
                "UNION SELECT id FROM locs_attr WHERE field LIKE 'actions_%' AND value = '$quoted' " +
                "ORDER BY id"
        ) { it.getInt(1) }
    }

    internal fun clearVariantMemo() = variantMemo.clear()

    private val UNRESOLVED = OpenVariant(-1, -1, OpenVariantRule.OFFSET, 0, false)
    private val variantMemo = java.util.concurrent.ConcurrentHashMap<Int, OpenVariant>()

    const val OPEN = "Open"
    const val CLOSE = "Close"

    val openOffset: Int = System.getProperty("opennxt.locChanges.openOffset")?.toIntOrNull() ?: 1

    fun change(
        plane: Int, x: Int, y: Int, shape: Int, rotation: Int, originalId: Int, newId: Int,
        forcedSwingMode: DoorSwing.Mode? = null
    ): Change? {
        if (!enabled) return null
        if (!opcodesAvailable()) return null
        logSwingStateOnce()
        val key = Key(plane, x, y, shape)
        val existing = synchronized(changes) { changes[key] }
        val change = Change(key, existing?.originalId ?: originalId, newId, rotation, forcedSwingMode)
        synchronized(changes) { changes[key] = change }

        val world = runCatching { OpenNXT.world }.getOrNull() ?: return change
        world.forEachPlayer { player -> sendTo(player, change) }
        return change
    }

    fun remove(
        plane: Int, x: Int, y: Int, shape: Int, rotation: Int, originalId: Int,
        crossPlane: Boolean = false,
        forcedSwingMode: DoorSwing.Mode? = null
    ): Change? {
        if (!enabled) return null
        if (!opcodesAvailable()) return null
        if (!removalOpcodeAvailable()) return null
        val key = Key(plane, x, y, shape)
        val existing = synchronized(changes) { changes[key] }
        val change = Change(
            key, existing?.originalId ?: originalId, REMOVED, rotation,
            forcedSwingMode = forcedSwingMode, removal = true, crossPlane = crossPlane
        )
        synchronized(changes) { changes[key] = change }

        val world = runCatching { OpenNXT.world }.getOrNull() ?: return change
        world.forEachPlayer { player -> sendTo(player, change) }
        return change
    }

    const val REMOVED = -1

    fun place(plane: Int, x: Int, y: Int, shape: Int, rotation: Int, newId: Int): Change? {
        if (!enabled) return null
        if (!opcodesAvailable()) return null
        val key = Key(plane, x, y, shape)
        val existing = synchronized(changes) { changes[key] }
        val change = Change(key, existing?.originalId ?: REMOVED, newId, rotation, DoorSwing.Mode.OFF)
        synchronized(changes) { changes[key] = change }
        val world = runCatching { OpenNXT.world }.getOrNull() ?: return change
        world.forEachPlayer { player -> sendTo(player, change) }
        return change
    }

    private fun removalOpcodeAvailable(): Boolean {
        if (OpenNXT.protocol.serverProtNames.values["LOC_DEL"] != null) return true
        if (!locDelWarned) {
            locDelWarned = true
            logger.warn {
                "Build ${OpenNXT.protocol.effectiveBuild} has no LOC_DEL opcode; locs cannot be removed (logged once)"
            }
        }
        return false
    }

    @Volatile
    private var locDelWarned = false

    fun revert(plane: Int, x: Int, y: Int, shape: Int): Change? {
        if (!enabled) return null
        if (!opcodesAvailable()) return null
        val key = Key(plane, x, y, shape)
        val existing = synchronized(changes) { changes.remove(key) } ?: return null
        if (existing.originalId == REMOVED) {
            val gone = Change(key, REMOVED, REMOVED, existing.rotation, DoorSwing.Mode.OFF, removal = true, crossPlane = existing.crossPlane)
            val w = runCatching { OpenNXT.world }.getOrNull() ?: return gone
            w.forEachPlayer { player -> sendTo(player, gone) }
            return gone
        }
        val restored = Change(
            key, existing.originalId, existing.originalId, existing.rotation, existing.forcedSwingMode,
            removal = false, crossPlane = existing.crossPlane
        )
        val world = runCatching { OpenNXT.world }.getOrNull() ?: return restored
        world.forEachPlayer { player -> sendTo(player, restored) }
        return restored
    }

    fun doubleDoorPartner(
        plane: Int, x: Int, y: Int, shape: Int, locId: Int, rotation: Int
    ): Triple<Int, Int, Int>? {
        if (shape !in WALL_SHAPES) return null
        val def = SqliteLocCodec.load(locId) ?: return null
        val name = def.name ?: return null
        if (name.isEmpty()) return null

        for ((dx, dy) in NEIGHBOURS) {
            val nx = x + dx
            val ny = y + dy
            
            val squareId = ((ny / 64) shl 7) or (nx / 64)
            val sql = "SELECT loc_id, type, rot FROM map_loc WHERE square_id = $squareId " +
                "AND x = ${nx % 64} AND y = ${ny % 64} AND plane = $plane " +
                "AND type = $shape AND rot = $rotation"
                
            val hits = RsDatabase.queryAll(sql) { it.getInt("loc_id") }

            for (hitLocId in hits) {
                if (hitLocId == locId) continue
                val hDef = SqliteLocCodec.load(hitLocId) ?: continue
                if (hDef.name == name) return Triple(nx, ny, hitLocId)
            }
        }
        return null
    }

    private val NEIGHBOURS = listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)

    val WALL_SHAPES = setOf(0, 1, 2, 3, 9)

    fun syncFor(player: WorldPlayer) {
        if (!enabled) return
        if (!opcodesAvailable()) return
        logSwingStateOnce()
        val epoch = player.viewport.sceneEpoch
        val last = sent[player]
        if (last == epoch) return
        sent[player] = epoch
        val list = all()
        if (list.isEmpty()) return
        var n = 0
        for (change in list) if (sendTo(player, change)) n++
        if (n > 0) logger.info { "Re-sent $n loc change(s) to ${player.name} after scene epoch $epoch" }
    }

    private fun sendTo(player: WorldPlayer, change: Change): Boolean {
        val key = change.key
        val v = player.viewport
        if (!change.crossPlane && player.entity.location.plane != key.plane) return false
        if (!v.containsTile(key.x, key.y)) return false
        return try {
            val open = change.swing
            if (open.mode != DoorSwing.Mode.OFF && !v.containsTile(open.x, open.y)) {
                logger.warn {
                    "door swing [${open.mode.id}] skipped for ${player.name}: door tile (${key.x},${key.y}) " +
                        "is in view but swung tile (${open.x},${open.y}) is not"
                }
                return false
            }
            player.client.write(UpdateZonePartialFollows(key.plane, v.zoneX(open.x), v.zoneY(open.y)))
            if (change.removal) {
                player.client.write(
                    LocDel(
                        coord = ZoneCoord.coord(open.x, open.y),
                        shapeRotation = ZoneCoord.shapeRotation(key.shape, open.rotation)
                    )
                )
            } else {
                player.client.write(
                    LocAddChange(
                        shapeRotation = ZoneCoord.shapeRotation(key.shape, open.rotation),
                        loc = change.currentId,
                        coord = ZoneCoord.coord(open.x, open.y)
                    )
                )
            }
            if (open.mode != DoorSwing.Mode.OFF) logger.info {
                "door swing [${open.mode.id}]: loc ${change.currentId} placed at " +
                    "(${open.x},${open.y}) rot ${open.rotation} instead of (${key.x},${key.y}) " +
                    "rot ${change.rotation} for ${player.name}"
            }
            true
        } catch (t: Throwable) {
            if (!failureWarned) {
                failureWarned = true
                logger.error(t) {
                    "LOC_ADD_CHANGE failed for ${player.name} at $key; " +
                        "-Dopennxt.experiment.locChanges=false disables loc changes (logged once)"
                }
            }
            false
        }
    }

    private fun opcodesAvailable(): Boolean {
        val names = OpenNXT.protocol.serverProtNames.values
        val missing = listOf("UPDATE_ZONE_PARTIAL_FOLLOWS", "LOC_ADD_CHANGE").filter { names[it] == null }
        if (missing.isEmpty()) return true
        if (!unmappedWarned) {
            unmappedWarned = true
            logger.warn {
                "Build ${OpenNXT.protocol.effectiveBuild} has no opcode for ${missing.joinToString(", ")}; " +
                    "loc changes are disabled (logged once)"
            }
        }
        return false
    }

    private val sent: MutableMap<WorldPlayer, Int> =
        Collections.synchronizedMap(WeakHashMap<WorldPlayer, Int>())

    @Volatile
    private var unmappedWarned = false

    @Volatile
    private var failureWarned = false
}

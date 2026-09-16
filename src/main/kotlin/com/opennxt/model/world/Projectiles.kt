package com.opennxt.model.world

import com.opennxt.OpenNXT
import com.opennxt.model.entity.player.Viewport
import com.opennxt.model.entity.rendering.UpdateBlockType
import com.opennxt.net.game.serverprot.UpdateZonePartialEnclosed
import com.opennxt.net.game.serverprot.ZoneSubPacket
import com.opennxt.net.game.serverprot.ZoneSubProtocol950
import mu.KotlinLogging
import java.util.Collections
import java.util.WeakHashMap

object Projectiles {
    private val logger = KotlinLogging.logger { }

    val enabled: Boolean = System.getProperty("opennxt.combat.projectiles") != "false"

    const val MAX_PER_FRAME = 64

    const val MAX_PENDING = 4096

    @Volatile
    var onQueued: ((Event) -> Boolean)? = null

    sealed class Event {
        abstract val plane: Int
        abstract val anchorX: Int
        abstract val anchorY: Int
    }

    data class Launch(
        val fromX: Int, val fromY: Int, val toX: Int, val toY: Int, override val plane: Int,
        val spotanim: Int,
        val startHeight: Int, val endHeight: Int,
        val startDelay: Int, val endDelay: Int,
        val angle: Int = 0, val startDistance: Int = 0,
        val source: Int = SOURCE_NONE, val target: Int = ZoneSubProtocol950.TAG_NONE,
        val flags: Int = 0,
        val srcHalfOffset: Int = 0, val dstHalfOffset: Int = 0
    ) : Event() {
        override val anchorX get() = fromX
        override val anchorY get() = fromY
        init {
            require(plane in 0..3) { "plane $plane" }
            require(spotanim in 0..0xffff) { "spotanim id $spotanim does not fit a ushort" }
            require(startDelay in 0..0xffff && endDelay in 0..0xffff) { "delays must be ushorts: $startDelay/$endDelay" }
            require(startHeight in -0x8000..0x7fff && endHeight in -0x8000..0x7fff) { "heights are signed shorts: $startHeight/$endHeight" }
            require(angle in 0..0xff) { "angle is a byte (0xff = none): $angle" }
            require(startDistance in 0..0xffff) { "startdistance is a ushort: $startDistance" }
            require(source in 0..0xffffff && target in 0..0xffffff) { "entity tags are u24: $source/$target" }
            require(flags in 0..3) { "flags carry bits 0..1 only: $flags" }
            require(srcHalfOffset in 0..1 && dstHalfOffset in 0..1) { "half offsets are 0 or 1" }
            require((toX - fromX) in -63..63 && (toY - fromY) in -63..63) {
                "projectile distance exceeds 63 tiles on an axis: ($fromX,$fromY) -> ($toX,$toY)"
            }
        }
    }

    data class TileAnim(
        val x: Int, val y: Int, override val plane: Int,
        val spotanim: Int, val height: Int = 0, val delay: Int = 0, val rotation: Int = 0
    ) : Event() {
        override val anchorX get() = x
        override val anchorY get() = y
        init {
            require(plane in 0..3) { "plane $plane" }
            require(spotanim in 0..0xffff) { "spotanim id $spotanim does not fit a ushort" }
            require(height in -0x8000..0x7fff) { "height is a signed short: $height" }
            require(delay in 0..0xffff) { "delay is a ushort (bit 15 = flag): $delay" }
            require(rotation in 0..0xff) { "rotation is a byte: $rotation" }
        }
    }

    data class LaunchTiles(val launch: Launch, val coordFlag: Boolean = false) : Event() {
        override val plane get() = launch.plane
        override val anchorX get() = launch.fromX
        override val anchorY get() = launch.fromY
        init {
            require(launch.startHeight in -128..127 && launch.endHeight in -128..127) {
                "MAP_PROJANIM heights must be signed bytes: ${launch.startHeight}/${launch.endHeight}"
            }
        }
    }

    const val SOURCE_NONE = 0x7f0000

    fun launch(event: Launch): Boolean = queue(event)

    fun launch(
        fromX: Int, fromY: Int, toX: Int, toY: Int, plane: Int, spotanim: Int,
        startHeight: Int, endHeight: Int, startDelay: Int, endDelay: Int,
        angle: Int = 0, startDistance: Int = 0,
        source: Int = SOURCE_NONE, target: Int = ZoneSubProtocol950.TAG_NONE
    ): Boolean = launch(Launch(fromX, fromY, toX, toY, plane, spotanim, startHeight, endHeight, startDelay, endDelay,
        angle, startDistance, source, target))

    fun launchTiles(event: Launch, coordFlag: Boolean = false): Boolean = queue(LaunchTiles(event, coordFlag))

    fun tileAnim(event: TileAnim): Boolean = queue(event)

    fun tileAnim(x: Int, y: Int, plane: Int, spotanim: Int, height: Int = 0, delay: Int = 0, rotation: Int = 0): Boolean =
        tileAnim(TileAnim(x, y, plane, spotanim, height, delay, rotation))

    fun tileAnimRemove(x: Int, y: Int, plane: Int): Boolean = tileAnim(TileAnim(x, y, plane, ZoneSubPacket.MapAnim.REMOVE))

    fun active(): Boolean {
        if (!enabled) return false
        if (!UpdateBlockType.experimentalBuild()) {
            warnOnce("projectiles are only supported on build 950 (running ${runCatching { OpenNXT.config.build }.getOrNull()})")
            return false
        }
        return opcodeAvailable()
    }

    private class Pending(val seq: Long, val tick: Long, val event: Event, val sub: ZoneSubPacket)

    private val pending = ArrayDeque<Pending>()
    private var nextSeq = 0L
    private val watermark: MutableMap<WorldPlayer, Long> = Collections.synchronizedMap(WeakHashMap())

    @Volatile private var queued = 0L
    @Volatile private var framesSent = 0L
    @Volatile private var subsSent = 0L
    @Volatile private var refused = 0L

    fun queuedCount() = queued
    fun framesSent() = framesSent
    fun subsSent() = subsSent
    fun refusedCount() = refused
    @Synchronized fun pendingCount() = pending.size

    @Synchronized
    fun resetCounters() { queued = 0; framesSent = 0; subsSent = 0; refused = 0 }

    @Synchronized
    fun clearAll() { pending.clear(); watermark.clear() }

    private fun currentTick(): Long = runCatching { OpenNXT.world.currentTick }.getOrDefault(0L)

    @Synchronized
    private fun queue(event: Event): Boolean {
        if (!active()) { refused++; return false }
        onQueued?.let { hook -> if (!hook(event)) { refused++; return false } }
        val sub = try { encodeEvent(event) } catch (e: IllegalArgumentException) {
            refused++
            warnOnce("dropped an unencodable event: ${e.message}")
            return false
        }
        val now = currentTick()
        prune(now)
        pending.addLast(Pending(nextSeq++, now, event, sub))
        while (pending.size > MAX_PENDING) pending.removeFirst()
        queued++
        return true
    }

    private fun prune(now: Long) {
        while (pending.isNotEmpty() && pending.first().tick < now - 1) pending.removeFirst()
    }

    fun encodeEvent(event: Event): ZoneSubPacket = when (event) {
        is Launch -> {
            val lx = event.fromX and 7; val lz = event.fromY and 7
            ZoneSubPacket.MapProjanimHalfsq949(
                coord = ZoneSubProtocol950.halfsqCoord(2 * lx + 1 + event.srcHalfOffset, 2 * lz + 1 + event.srcHalfOffset),
                flags = event.flags,
                deltax = 2 * (event.toX - event.fromX) + event.dstHalfOffset - event.srcHalfOffset,
                deltaz = 2 * (event.toY - event.fromY) + event.dstHalfOffset - event.srcHalfOffset,
                source = event.source, target = event.target, spotanim = event.spotanim,
                startheight = event.startHeight and 0xffff, endheight = event.endHeight and 0xffff,
                startdelay = event.startDelay, enddelay = event.endDelay,
                angle = event.angle, startdistance = event.startDistance
            )
        }
        is LaunchTiles -> {
            val l = event.launch
            ZoneSubPacket.MapProjanim(
                coord = ZoneSubProtocol950.projanimCoord(l.fromX and 7, l.fromY and 7, event.coordFlag),
                deltax = l.toX - l.fromX, deltaz = l.toY - l.fromY,
                target = l.target, spotanim = l.spotanim,
                startheight = l.startHeight and 0xff, endheight = l.endHeight and 0xff,
                startdelay = l.startDelay, enddelay = l.endDelay,
                angle = l.angle, startdistance = l.startDistance
            )
        }
        is TileAnim -> ZoneSubPacket.MapAnim(
            coord = ZoneSubProtocol950.mapAnimCoord(event.x and 7, event.y and 7),
            spotanim = event.spotanim, height = event.height and 0xffff,
            delay = event.delay, rotation = event.rotation
        )
    }

    fun flush(player: WorldPlayer) {
        if (!enabled) return
        try {
            val packets = takeFor(player)
            for (p in packets) player.client.write(p)
        } catch (t: Throwable) {
            if (!failureWarned) {
                failureWarned = true
                logger.error(t) { "Projectile flush failed for ${player.name} (logged once; -Dopennxt.combat.projectiles=false disables)" }
            }
        }
    }

    @Synchronized
    fun takeFor(player: WorldPlayer): List<UpdateZonePartialEnclosed> {
        if (!active()) return emptyList()
        val now = currentTick()
        prune(now)
        val since = watermark[player] ?: -1L
        if (pending.isEmpty() || pending.last().seq <= since) return emptyList()
        watermark[player] = pending.last().seq
        val fresh = pending.filter { it.seq > since }
        return frames(player.viewport, player.entity.location.plane, fresh.map { it.event to it.sub }).also {
            framesSent += it.size; subsSent += it.sumOf { f -> f.subPackets.size }
        }
    }

    fun frames(viewport: Viewport, viewerPlane: Int, events: List<Pair<Event, ZoneSubPacket>>): List<UpdateZonePartialEnclosed> {
        val byZone = LinkedHashMap<Triple<Int, Int, Int>, MutableList<ZoneSubPacket>>()
        for ((e, sub) in events) {
            if (e.plane != viewerPlane) continue
            if (!viewport.containsTile(e.anchorX, e.anchorY)) continue
            val key = Triple(e.plane, viewport.zoneX(e.anchorX), viewport.zoneY(e.anchorY))
            byZone.getOrPut(key) { ArrayList() }.add(sub)
        }
        val out = ArrayList<UpdateZonePartialEnclosed>()
        for ((key, subs) in byZone) {
            for (chunk in subs.chunked(MAX_PER_FRAME)) out += UpdateZonePartialEnclosed(key.first, key.second, key.third, chunk)
        }
        return out
    }

    private fun opcodeAvailable(): Boolean {
        val names = runCatching { OpenNXT.protocol.serverProtNames.values }.getOrNull() ?: return false
        if (names["UPDATE_ZONE_PARTIAL_ENCLOSED"] != null) return true
        warnOnce("build ${OpenNXT.protocol.effectiveBuild} has no UPDATE_ZONE_PARTIAL_ENCLOSED opcode; projectiles disabled")
        return false
    }

    @Volatile private var warned = false
    @Volatile private var failureWarned = false

    private fun warnOnce(msg: String) {
        if (warned) return
        warned = true
        logger.warn { "Projectiles: $msg (logged once)" }
    }
}

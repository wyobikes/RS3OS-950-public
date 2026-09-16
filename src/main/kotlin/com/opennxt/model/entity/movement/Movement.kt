package com.opennxt.model.entity.movement

import com.opennxt.model.entity.Entity
import com.opennxt.model.entity.PlayerEntity
import com.opennxt.model.map.CollisionMap
import com.opennxt.model.map.LocClipping
import com.opennxt.model.map.PathFinder
import com.opennxt.model.world.TileLocation
import java.util.*

class Movement(val entity: Entity) {
    private val queue = LinkedList<TileLocation>()
    private val isPlayer = entity is PlayerEntity
    var speed = MovementSpeed.RUN
    var currentSpeed = MovementSpeed.STATIONARY
    var nextWalkDirection: CompassPoint? = null
    var nextRunDirection: CompassPoint? = null
    var teleportLocation: TileLocation? = null
    var onArrival: (() -> Unit)? = null

    var lastStepDirection: CompassPoint? = null

    @Volatile
    var hold: (() -> String?)? = null

    val hasSteps: Boolean get() = queue.isNotEmpty()
    val remainingSteps: Int get() = queue.size

    private val runEnergy: RunEnergy? get() = (entity as? PlayerEntity)?.runEnergy

    fun destination(): Pair<Int, Int>? = queue.lastOrNull()?.let { it.x to it.y }

    fun reset() {
        queue.clear()
        currentSpeed = MovementSpeed.STATIONARY
        nextWalkDirection = null
        nextRunDirection = null
        onArrival = null
    }

    fun teleport(location: TileLocation) {
        queue.clear()
        teleportLocation = location
        onArrival = null
    }

    fun addStep(x: Int, z: Int): Boolean {
        val from = queue.lastOrNull() ?: entity.location
        val dx = Integer.signum(x - from.x)
        val dz = Integer.signum(z - from.y)
        if (dx == 0 && dz == 0) return false
        if (Math.abs(x - from.x) > 1 || Math.abs(z - from.y) > 1) return false
        if (!CollisionMap.canStep(from.x, from.y, dx, dz, from.plane)) return false
        queue.add(TileLocation(x, z, from.plane))
        return true
    }

    fun walkTo(x: Int, z: Int, nearest: Boolean = true, search: Int = PathFinder.SEARCH): Boolean {
        val loc = entity.location
        clipPathEnds(loc.x, loc.y, x, z, loc.plane)
        val path = PathFinder.find(loc.x, loc.y, x, z, loc.plane, search = search, nearest = nearest)
            ?: return false
        queue.clear()
        onArrival = null
        path.drop(1).forEach { queue.add(TileLocation(it.x, it.z, loc.plane)) }
        return queue.isNotEmpty()
    }

    fun processPlayerTick(): Boolean {
        if (teleportLocation == null && hold?.invoke() != null) reset()
        val energy = runEnergy ?: run { process(); return false }
        speed = if (energy.toggled) MovementSpeed.RUN else MovementSpeed.WALK
        process()
        val ran = currentSpeed == MovementSpeed.RUN
        energy.tick(ran)
        return ran
    }

    fun process() {
        nextWalkDirection = null
        nextRunDirection = null

        teleportLocation?.let { destination ->
            entity.previousLocation = entity.location
            entity.location = destination
            teleportLocation = null
            currentSpeed = MovementSpeed.INSTANT
            queue.clear()

            val arrived = onArrival
            onArrival = null
            arrived?.invoke()
            return
        }

        if (queue.isEmpty()) {
            currentSpeed = MovementSpeed.STATIONARY

            val arrived = onArrival
            if (arrived != null) {
                onArrival = null
                arrived.invoke()
            }
            return
        }

        entity.previousLocation = entity.location

        val first = step()
        if (first == null) {
            reset()
            return
        }
        nextWalkDirection = first

        if (speed == MovementSpeed.RUN && queue.isNotEmpty() && runEnergy?.canRunStep() != false) {
            val second = step()
            if (second != null) {
                nextRunDirection = second
                currentSpeed = MovementSpeed.RUN
                return
            }
        }
        currentSpeed = MovementSpeed.WALK
    }

    private fun step(): CompassPoint? {
        val next = queue.peek() ?: return null
        val from = entity.location
        val dx = Integer.signum(next.x - from.x)
        val dz = Integer.signum(next.y - from.y)
        if (!CollisionMap.canStep(from.x, from.y, dx, dz, from.plane)) return null
        queue.poll()
        entity.location = next
        return CompassPoint.forDelta(dx, dz).also { if (it != null) lastStepDirection = it }
    }

    companion object {
        @Volatile
        @JvmStatic
        var clipEndsBeforePathing: Boolean =
            System.getProperty("opennxt.experiment.pathClipping") != "false"

        val PROVENANCE: String =
            "path clipping: clipEndsBeforePathing=$clipEndsBeforePathing"

        private val endClips = java.util.concurrent.atomic.AtomicLong()

        @JvmStatic
        fun endClipApplications(): Long = endClips.get()

        @JvmStatic
        fun clipPathEnds(fromX: Int, fromZ: Int, toX: Int, toZ: Int, plane: Int): Int {
            if (!clipEndsBeforePathing) return 0
            var n = 0
            if (LocClipping.applySquareAt(fromX, fromZ, plane) > 0) n++
            if (LocClipping.applySquareAt(toX, toZ, plane) > 0) n++
            if (n > 0) endClips.addAndGet(n.toLong())
            return n
        }
    }

}

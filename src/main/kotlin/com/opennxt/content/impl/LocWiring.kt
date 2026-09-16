package com.opennxt.content.impl

import com.opennxt.content.ContentRegistry
import com.opennxt.content.DispatchResult
import com.opennxt.model.world.LocChanges
import com.opennxt.model.world.WorldPlayer
import mu.KotlinLogging

object LocWiring {
    private val logger = KotlinLogging.logger { }

    const val DISPATCH_SWITCH = "opennxt.experiment.loc.dispatch"

    val dispatchEnabled: Boolean get() = System.getProperty(DISPATCH_SWITCH) != "false"

    fun alreadyRoutedByHandler(action: String): Boolean =
        (action == LocChanges.OPEN && LocChanges.enabled) ||
            ResourceNodes.isGatherAction(action) ||
            action == Banks.BANK ||
            action == Banks.USE

    @Volatile
    var droppedMoves: Int = 0
        private set

    @Volatile
    var routed: Int = 0
        private set

    @Volatile
    var skipped: Int = 0
        private set

    @Volatile
    private var offWarned = false

    internal fun resetCounters() {
        droppedMoves = 0
        routed = 0
        skipped = 0
        offWarned = false
    }

    fun routeOther(world: WorldPlayer, locId: Int, action: String, x: Int, z: Int, plane: Int): Boolean {
        if (alreadyRoutedByHandler(action)) return false
        if (!dispatchEnabled) {
            skipped++
            if (!offWarned) {
                offWarned = true
                logger.warn {
                    "loc routing is disabled: dropped '$action' on loc $locId; -D$DISPATCH_SWITCH=true to enable"
                }
            }
            return false
        }

        val content = world.contentPlayerAt()
        BanksWiring.bind(content, world)
        DialogueWiring.bind(content, world)
        SkillingWiring.bind(content, world)
        SmithingWiring.bind(content, world)

        val beforeX = content.location.x
        val beforeY = content.location.y
        val beforePlane = content.location.plane

        routed++
        val dispatched = try {
            ContentRegistry.dispatchLoc(content, locId, action, x, z, plane)
        } catch (t: Throwable) {
            logger.error(t) {
                "loc routing: handler for '$action' on loc $locId threw; click dropped"
            }
            return false
        }

        when (dispatched) {
            is DispatchResult.Handled -> {
                logger.info {
                    "loc routing: ${world.name} '$action' on loc $locId at ($x,$z,plane $plane) -> " +
                        "${dispatched.value}"
                }
                val after = content.location
                if (after.x != beforeX || after.y != beforeY || after.plane != beforePlane) {
                    droppedMoves++
                    logger.warn {
                        "loc routing: '$action' on loc $locId moved ${world.name} from " +
                        "($beforeX,$beforeY,plane $beforePlane) to (${after.x},${after.y},plane ${after.plane}); teleporting"
                    }
                    world.entity.movement.teleport(after)
                }
                return true
            }
            is DispatchResult.NoHandler -> return false
            else -> {
                logger.warn {
                    "loc routing: '$action' on loc $locId rejected by the registry ($dispatched)"
                }
                return false
            }
        }
    }
}

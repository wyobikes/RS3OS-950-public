package com.opennxt.content.impl

import com.opennxt.model.entity.player.PlayerInventory
import com.opennxt.model.items.ItemContainer
import com.opennxt.model.world.WorldPlayer
import com.opennxt.net.game.clientprot.generated.IfButtond
import mu.KotlinLogging

object BackpackDrag {
    private val logger = KotlinLogging.logger { }

    const val BACKPACK_IFACE = 1473
    const val DRAG_SOURCE_COMPONENT = 4
    const val DRAG_TARGET_COMPONENT = 5

    const val DRAG_SOURCE_COMPONENT_GRID = 5

    val DEFAULT_950_EVENTS: List<IntArray> = listOf(
        intArrayOf(5, 65535, 65535, 2097152),
        intArrayOf(5, 0, 27, 15433102),
        intArrayOf(20, 0, 17, 1422),
        intArrayOf(9, 0, 1, 2099198), intArrayOf(9, 4096, 4097, 2099198), intArrayOf(9, 4352, 4353, 2099198),
        intArrayOf(9, 4608, 4609, 2099198), intArrayOf(9, 4864, 4865, 2099198), intArrayOf(9, 5120, 5121, 2099198),
        intArrayOf(9, 5376, 5377, 2099198),
    )

    fun arm950(player: WorldPlayer): Int {
        for (e in DEFAULT_950_EVENTS)
            player.interfaces.events(id = BACKPACK_IFACE, component = e[0], from = e[1], to = e[2], mask = e[3])
        return DEFAULT_950_EVENTS.size
    }

    val enabled: Boolean get() = System.getProperty("opennxt.content.backpackdrag") != "off"

    sealed class Outcome {
        data class Moved(val from: Int, val to: Int, val itemId: Int, val displacedId: Int?) : Outcome()
        data class Refused(val reason: String) : Outcome()
    }

    fun isBackpackDrag(packet: IfButtond): Boolean =
        (packet.sourcehash == ((BACKPACK_IFACE shl 16) or DRAG_SOURCE_COMPONENT) ||
            packet.sourcehash == ((BACKPACK_IFACE shl 16) or DRAG_SOURCE_COMPONENT_GRID)) &&
            packet.targethash == ((BACKPACK_IFACE shl 16) or DRAG_TARGET_COMPONENT)

    fun apply(container: ItemContainer, from: Int, to: Int): Outcome {
        if (from !in 0 until container.size) return Outcome.Refused("source slot $from is outside 0..${container.size - 1}")
        if (to !in 0 until container.size) return Outcome.Refused("target slot $to is outside 0..${container.size - 1}")
        val moving = container[from] ?: return Outcome.Refused("source slot $from is empty")
        if (from == to) return Outcome.Refused("source and target are the same slot $from")
        val displaced = container[to]
        container.swap(from, to)
        return Outcome.Moved(from, to, moving.id, displaced?.id)
    }

    fun handleDrag(player: WorldPlayer, packet: IfButtond): Boolean {
        if (!enabled || !isBackpackDrag(packet)) return false
        val who = player.name
        val from = packet.sourceslot
        val to = packet.targetslot
        if (!player.interfaces.isOpened(BACKPACK_IFACE)) {
            logger.info { "backpackDrag: $who dragged slot $from -> $to with $BACKPACK_IFACE not open - ignored" }
            return true
        }
        if (!PlayerInventory.enabled) {
            logger.info { "backpackDrag: $who dragged slot $from -> $to, inventory disabled - ignored" }
            return true
        }
        val container = PlayerInventory.backpackOf(player)
        when (val o = apply(container, from, to)) {
            is Outcome.Moved -> logger.info {
                "backpackDrag: $who moved obj ${o.itemId} from slot ${o.from} to slot ${o.to}" +
                    (o.displacedId?.let { " (swapped with obj $it)" } ?: "")
            }
            is Outcome.Refused -> logger.info { "backpackDrag: $who dragged slot $from -> $to refused: ${o.reason}" }
        }
        if (player.allowOncePerTick(RESEND_KEY)) PlayerInventory.sendBackpack(player)
        else {
            player.backpackResendOwed = true
            deferredResends++
        }
        return true
    }

    const val RESEND_KEY = "backpackDragResend"

    @Volatile
    var deferredResends: Int = 0
        private set
}

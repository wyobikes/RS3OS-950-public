package com.opennxt.net.game.handlers

import com.opennxt.content.ContentRegistry
import com.opennxt.content.DispatchResult
import com.opennxt.content.impl.CookingWiring
import com.opennxt.model.entity.BasePlayer
import com.opennxt.model.entity.player.PlayerInventory
import com.opennxt.model.map.LocInteraction
import com.opennxt.model.world.WorldPlayer
import com.opennxt.net.game.clientprot.generated.Oploct
import com.opennxt.net.game.pipeline.GamePacketHandler
import com.opennxt.resources.sqlite.RsDatabase
import com.opennxt.resources.sqlite.SqliteLocCodec
import mu.KotlinLogging

object OpLocTHandler : GamePacketHandler<BasePlayer, Oploct> {
    private val logger = KotlinLogging.logger { }

    const val NO_SELECTION = 0xffffff

    const val BACKPACK_HASH = (1473 shl 16) or 5

    fun locIdOf(rawLocField: Int): Int = Integer.reverseBytes(rawLocField)

    override fun handle(context: BasePlayer, packet: Oploct) {
        if (context !is WorldPlayer) { DecodedPacketLogHandler.handle(context, packet); return }
        if (com.opennxt.content.ActionLock.refuse(context.contentPlayer, "OPLOCT item ${packet.selobj} at (${packet.x},${packet.y})")) return

        val locId = locIdOf(packet.loc)
        val iface = (packet.selhash ushr 16) and 0xffff
        val component = packet.selhash and 0xffff
        val plane = context.entity.location.plane
        val def = if (RsDatabase.available) SqliteLocCodec.load(locId) else null
        val itemName = com.opennxt.content.impl.Skilling.itemNameOf(packet.selobj)

        logger.info {
            "OPLOCT ${context.name} used item ${packet.selobj}${if (itemName != null) " ('$itemName')" else ""} " +
                "from $iface:$component slot ${packet.selsub} on loc $locId" +
                (if (def != null) " ('${def.name}')" else " (no definition)") +
                " at (${packet.x},${packet.y},plane $plane)" +
                (if (packet.ctrl != 0) " ctrl=${packet.ctrl}" else "")
        }

        if (packet.selobj <= 0 || packet.selobj == NO_SELECTION) {
            logger.warn { "OPLOCT: selobj ${packet.selobj} is not an item id; ignored" }
            return
        }
        if (packet.selhash != BACKPACK_HASH) {
            logger.info { "OPLOCT: selection from $iface:$component, not the backpack" }
        }
        val backpack = PlayerInventory.backpackOf(context)
        val held = if (packet.selsub in 0 until backpack.size) backpack[packet.selsub] else null
        if (held == null || held.id != packet.selobj) {
            logger.warn {
                "OPLOCT: ${context.name} sent item ${packet.selobj} for backpack slot ${packet.selsub}, " +
                    "which holds ${held?.id ?: "nothing"}; ignored"
            }
            return
        }
        if (RsDatabase.available) {
            val placed = OpLocHandler.placementsAt(packet.x, packet.y, plane).any { it.locId == locId }
            val covered = placed || LocInteraction.placementsCovering(packet.x, packet.y, plane).any { it.locId == locId }
            if (!covered) {
                logger.info {
                    "OPLOCT: loc $locId at (${packet.x},${packet.y},plane $plane) is not in map_loc (runtime loc?)"
                }
            }
        }
        val px = context.entity.location.x
        val pz = context.entity.location.y
        if (Math.abs(px - packet.x) > INTERACT_RANGE || Math.abs(pz - packet.y) > INTERACT_RANGE) {
            logger.info {
                "OPLOCT: ${context.name} at ($px,$pz) is more than $INTERACT_RANGE tiles from loc $locId " +
                    "at (${packet.x},${packet.y}); ignored"
            }
            return
        }

        val content = context.contentPlayer
        CookingWiring.bind(content, context)
        content.location = com.opennxt.model.world.TileLocation(px, pz, plane)

        val result = ContentRegistry.dispatchItemOnLoc(
            player = content, itemId = packet.selobj, locId = locId, slot = packet.selsub,
            x = packet.x, z = packet.y, plane = plane
        )
        when (result) {
            is DispatchResult.Handled -> logger.info { "OPLOCT: handled - ${result.value}" }
            else -> {
                logger.info { "OPLOCT: $result" }
                DecodedPacketLogHandler.handle(context, packet)
            }
        }
    }

    const val INTERACT_RANGE = 1
}

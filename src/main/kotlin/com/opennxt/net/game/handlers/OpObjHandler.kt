package com.opennxt.net.game.handlers

import com.opennxt.OpenNXT
import com.opennxt.model.entity.player.PlayerInventory
import com.opennxt.model.world.GroundItem
import com.opennxt.model.world.PickupResult
import com.opennxt.model.world.WorldPlayer
import com.opennxt.net.game.clientprot.OpObj
import com.opennxt.net.game.pipeline.GamePacketHandler
import com.opennxt.resources.sqlite.RsDatabase
import com.opennxt.resources.sqlite.SqliteItemCodec
import mu.KotlinLogging

object OpObjHandler : GamePacketHandler<WorldPlayer, OpObj> {
    private val logger = KotlinLogging.logger { }

    val takeOption: Int = System.getProperty("opennxt.groundItems.takeOption")?.toIntOrNull() ?: 3

    val takeAnyOption: Boolean = System.getProperty("opennxt.groundItems.takeAnyOption") == "true"

    internal fun itemsAt(x: Int, y: Int, plane: Int): List<GroundItem> {
        val world = runCatching { OpenNXT.world }.getOrNull() ?: return emptyList()
        return world.groundItems.itemsAt(x, y, plane)
    }

    internal fun isTake(option: Int): Boolean = takeAnyOption || option == takeOption

    internal fun isTake(itemId: Int, option: Int): Boolean {
        if (takeAnyOption) return true
        if (!RsDatabase.available) return isTake(option)
        return com.opennxt.content.impl.GroundOptions.isTake(itemId, option)
    }

    override fun handle(context: WorldPlayer, packet: OpObj) {
        if (com.opennxt.content.ActionLock.refuse(context.contentPlayer, "OPOBJ${packet.option} obj ${packet.id} at (${packet.x},${packet.y})")) return
        val plane = context.entity.location.plane
        val def = if (RsDatabase.available) SqliteItemCodec.load(packet.id) else null
        val name = def?.name ?: "unknown obj"

        val here = itemsAt(packet.x, packet.y, plane)
        val target = here.firstOrNull { it.itemId == packet.id }

        logger.info {
            "OPOBJ${packet.option} ${context.name} clicked $name (obj ${packet.id}) at " +
                "(${packet.x},${packet.y},plane $plane) option ${packet.option}" +
                (if (packet.ctrlHeld) " ctrl-held" else "") +
                (if (packet.inputBit) " input-bit-set" else "") +
                (if (target != null) " [present]"
                else " [not present; ${here.size} item(s) on tile]") +
                (if (RsDatabase.available) "" else " [no rs3.sqlite]")
        }

        if (target == null) {
            logger.warn {
                "OPOBJ${packet.option}: no ground item ${packet.id} at " +
                    "(${packet.x},${packet.y},plane $plane); walking to the tile"
            }
            MoveGameClickHandler.walk(context, packet.x, packet.y, "OPOBJ${packet.option}")
            return
        }

        if (!isTake(packet.id, packet.option)) {
            logger.warn {
                "OPOBJ${packet.option} on $name: option " +
                    "'${com.opennxt.content.impl.GroundOptions.optionFor(packet.id, packet.option) ?: "(empty row)"}' " +
                    "is not Take; walking to the tile"
            }
            MoveGameClickHandler.walk(context, packet.x, packet.y, "OPOBJ${packet.option}")
            return
        }

        if (com.opennxt.content.impl.LootWindow.open(context, packet.x, packet.y, plane)) return

        take(context, target)
    }

    internal fun take(player: WorldPlayer, item: GroundItem) {
        val world = runCatching { OpenNXT.world }.getOrNull() ?: return
        val backpack = PlayerInventory.backpackOf(player)
        val before = item.quantity
        when (val result = world.groundItems.pickup(player.entity.location, item, backpack, player.name)) {
            is PickupResult.PickedUp -> {
                logger.info { "${player.name} took ${item.itemName} x$before (obj ${item.itemId})" }
                PlayerInventory.sendBackpack(player)
            }

            is PickupResult.Partial -> {
                logger.info {
                    "${player.name} took ${result.add.added} of ${item.itemName} x$before; " +
                        "${result.add.remaining} left on the ground (backpack full)"
                }
                PlayerInventory.sendBackpack(player)
            }

            is PickupResult.ContainerFull ->
                logger.info { "${player.name} could not take ${item.itemName}: backpack full" }

            is PickupResult.TooFar -> {
                logger.info {
                    "${player.name} is ${result.distance} tile(s) from ${item.itemName} " +
                        "(max ${result.allowed}); walking to it instead of taking it"
                }
                MoveGameClickHandler.walk(player, item.tile.x, item.tile.y, "OPOBJ-take")
            }

            is PickupResult.NotOnGround ->
                logger.info { "${player.name} clicked ${item.itemName}, but it is no longer on the ground" }

            is PickupResult.NotYours ->
                logger.info {
                    "${player.name} tried to take ${item.itemName} owned by ${result.owner}; public in " +
                        "${result.ticksUntilPublic} tick(s); refused"
                }
        }
    }
}

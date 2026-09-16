package com.opennxt.content.impl

import com.opennxt.OpenNXT
import com.opennxt.content.ContentPlayer
import com.opennxt.model.entity.player.PlayerInventory
import com.opennxt.model.entity.rendering.PlayerUpdates
import com.opennxt.model.entity.rendering.blocks.PlayerAnimationBlock
import com.opennxt.model.map.CollisionMap
import com.opennxt.model.world.GroundItem
import com.opennxt.model.world.TileLocation
import com.opennxt.model.world.WorldPlayer
import com.opennxt.net.game.serverprot.MessageGame
import mu.KotlinLogging

object FiremakingWiring {
    private val logger = KotlinLogging.logger { }

    @Volatile
    private var installed = false

    fun isInstalled(): Boolean = installed

    private val hook: (WorldPlayer, String, Int, String, Int) -> Boolean = { world, action, itemId, name, slot ->
        if (!action.equals(Firemaking.LIGHT_ACTION, ignoreCase = true)) false
        else {
            val content = world.contentPlayer
            SkillingWiring.bind(content, world)
            val result = Firemaking.light(content, itemId, name, slot, action)
            result.outcome != Firemaking.Outcome.NOT_MINE
        }
    }

    fun install(): Boolean {
        if (!Firemaking.enabled) {
            logger.warn { "firemaking wiring: firemaking is disabled" }
            return false
        }

        Firemaking.containerSupplier = { content ->
            val world = SkillingWiring.ownerOf(content)
            if (world == null) content.inventory else PlayerInventory.backpackOf(world)
        }

        Firemaking.levelSupplier = { content, stat ->
            val world = SkillingWiring.ownerOf(content)
            if (world == null) 1 else runCatching { world.stats.getLevel(stat) }.getOrDefault(1)
        }

        Firemaking.xpSink = { content, stat, amount ->
            SkillingWiring.ownerOf(content)?.stats?.addExperience(stat, amount, SkillingWiring.SKILLING_SOURCE)
        }

        Firemaking.inventoryResend = { content ->
            val world = SkillingWiring.ownerOf(content)
            if (world != null) { PlayerInventory.sendBackpack(world); true } else false
        }

        Firemaking.messageSink = { content, msg ->
            SkillingWiring.ownerOf(content)?.client?.write(MessageGame(0, msg))
        }

        Firemaking.animationSink = { content, ids ->
            SkillingWiring.ownerOf(content)?.let { world ->
                PlayerUpdates.animate(world.entity, PlayerAnimationBlock(ids, 0))
            }
        }

        Firemaking.tileSupplier = { content ->
            SkillingWiring.ownerOf(content)?.entity?.location?.let { TileLocation(it.x, it.y, it.plane) }
                ?: content.location
        }

        Firemaking.canStep = { from, dx, dz ->
            runCatching { CollisionMap.canStep(from.x, from.y, dx, dz, from.plane) }.getOrDefault(false)
        }

        Firemaking.faceSink = { content, angle ->
            SkillingWiring.ownerOf(content)?.let { world ->
                PlayerUpdates.faceDirection(world.entity, angle)
            }
        }

        Firemaking.stepSink = { content, to ->
            val world = SkillingWiring.ownerOf(content)
            if (world == null) false else {
                val walk950 = com.opennxt.model.entity.rendering.UpdateBlockType.experimentalBuild()
                val moved = if (walk950) world.entity.movement.addStep(to.x, to.y) else true
                if (!walk950) world.entity.movement.teleport(TileLocation(to.x, to.y, to.plane))
                if (moved) content.location = TileLocation(to.x, to.y, to.plane)
                moved
            }
        }

        Firemaking.groundAdd = { content, itemId, itemName, tile ->
            val world = runCatching { OpenNXT.world }.getOrNull()
            if (world == null) null else runCatching {
                world.groundItems.spawnItem(
                    itemId = itemId, itemName = itemName, quantity = 1,
                    tile = TileLocation(tile.x, tile.y, tile.plane),
                    owner = content.name, source = "firemaking"
                )
            }.getOrNull()
        }

        Firemaking.groundRemove = { handle ->
            val world = runCatching { OpenNXT.world }.getOrNull()
            if (world == null || handle !is GroundItem) false else world.groundItems.remove(handle)
        }

        if (!ItemOps.backpackActionHooks.contains(hook)) ItemOps.backpackActionHooks.add(hook)
        installed = true

        logger.info {
            "firemaking wiring: '${Firemaking.LIGHT_ACTION}' hooked; fire loc ${Firemaking.FIRE_LOC}, " +
                "${Firemaking.LOGS_XP_TENTHS / 10.0} xp per Logs, catch ${Firemaking.CATCH_PERCENT}%/tick, " +
                "lasts ${Firemaking.FIRE_TICKS} ticks (min ${Firemaking.MIN_FIRE_TICKS})"
        }
        return true
    }

    fun uninstall() {
        ItemOps.backpackActionHooks.remove(hook)
        Firemaking.containerSupplier = { it.inventory }
        Firemaking.levelSupplier = { _, _ -> 1 }
        Firemaking.xpSink = { _, _, _ -> }
        Firemaking.messageSink = { _, _ -> }
        Firemaking.animationSink = { _, _ -> }
        Firemaking.inventoryResend = { false }
        Firemaking.tileSupplier = { it.location }
        Firemaking.canStep = { _, _, _ -> true }
        Firemaking.stepSink = { _, _ -> false }
        Firemaking.groundAdd = { _, _, _, _ -> null }
        Firemaking.groundRemove = { false }
        Firemaking.faceSink = { _, _ -> }
        installed = false
    }

    fun hookCount(): Int = ItemOps.backpackActionHooks.size
}

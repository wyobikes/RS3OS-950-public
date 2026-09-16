package com.opennxt.content.impl

import com.opennxt.OpenNXT
import com.opennxt.api.stat.ExperienceSource
import com.opennxt.content.ContentPlayer
import com.opennxt.model.entity.player.PlayerInventory
import com.opennxt.model.entity.rendering.PlayerUpdates
import com.opennxt.model.entity.rendering.blocks.PlayerAnimationBlock
import com.opennxt.model.world.WorldNpc
import com.opennxt.model.world.WorldPlayer
import com.opennxt.net.game.serverprot.MessageGame
import mu.KotlinLogging

object FishingWiring {
    private val logger = KotlinLogging.logger { }

    fun ownerOf(content: ContentPlayer): WorldPlayer? = SkillingWiring.ownerOf(content)

    fun npcAtIndex(index: Int): WorldNpc? {
        val world = runCatching { OpenNXT.world }.getOrNull() ?: return null
        return world.npcs.byInfoIndex(index)?.takeIf { it.alive }
    }

    fun install(): Boolean {
        if (!Fishing.enabled) {
            logger.warn { "fishing wiring: fishing is disabled; not installed" }
            return false
        }

        Fishing.containerSupplier = { content ->
            val world = ownerOf(content)
            if (world == null) content.inventory else PlayerInventory.backpackOf(world)
        }
        Fishing.wornSupplier = { content -> ownerOf(content)?.let { PlayerInventory.wornOf(it) } }

        Fishing.levelSupplier = { content, stat ->
            val world = ownerOf(content)
            if (world == null) 1 else runCatching { world.stats.getLevel(stat) }.getOrDefault(1)
        }

        Fishing.xpSink = { content, stat, amount ->
            ownerOf(content)?.stats?.addExperience(stat, amount, FISHING_SOURCE)
        }

        Fishing.inventoryResend = { content ->
            val world = ownerOf(content)
            if (world != null) { PlayerInventory.sendBackpack(world); true } else false
        }

        Fishing.animationSink = { content, ids ->
            ownerOf(content)?.let { world -> PlayerUpdates.animate(world.entity, PlayerAnimationBlock(ids, 0)) }
        }
        Fishing.messageSink = { content, msg ->
            ownerOf(content)?.client?.write(MessageGame(0, msg))
        }

        Fishing.standingStill = { content ->
            val world = ownerOf(content)
            if (world == null) true else runCatching { !world.entity.movement.hasSteps }.getOrDefault(true)
        }

        Fishing.spotAlive = { index -> npcAtIndex(index) != null }
        Fishing.spotTile = { index ->
            npcAtIndex(index)?.location?.let { intArrayOf(it.x, it.y, it.plane) }
        }

        logger.info {
            "fishing wiring: installed (sendStats=${com.opennxt.impl.stat.PlayerStatContainer.sendStatsEnabled})"
        }
        return true
    }

    fun uninstall() {
        Fishing.containerSupplier = { it.inventory }
        Fishing.wornSupplier = { null }
        Fishing.levelSupplier = { _, _ -> 1 }
        Fishing.xpSink = { _, _, _ -> }
        Fishing.inventoryResend = { false }
        Fishing.animationSink = { _, _ -> }
        Fishing.messageSink = { _, _ -> }
        Fishing.spotAlive = { true }
        Fishing.spotTile = { null }
        Fishing.standingStill = { true }
    }

    object FISHING_SOURCE : ExperienceSource(1.0) {
        override fun toString() = "FISHING"
    }
}

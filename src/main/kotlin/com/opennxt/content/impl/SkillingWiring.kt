package com.opennxt.content.impl

import com.opennxt.api.stat.ExperienceSource
import com.opennxt.api.stat.Stat
import com.opennxt.content.ContentPlayer
import com.opennxt.model.entity.player.PlayerInventory
import com.opennxt.model.items.ItemContainer
import com.opennxt.model.world.WorldPlayer
import mu.KotlinLogging
import java.util.Collections
import java.util.WeakHashMap

object SkillingWiring {
    private val logger = KotlinLogging.logger { }

    private val owners: MutableMap<ContentPlayer, java.lang.ref.WeakReference<WorldPlayer>> =
        Collections.synchronizedMap(WeakHashMap())

    fun bind(content: ContentPlayer, world: WorldPlayer) {
        val existing = owners[content]?.get()
        if (existing === world) return
        owners[content] = java.lang.ref.WeakReference(world)
    }

    fun ownerOf(content: ContentPlayer): WorldPlayer? = owners[content]?.get()

    fun boundPlayers(): Int = synchronized(owners) { owners.values.count { it.get() != null } }

    internal fun clear() = synchronized(owners) { owners.clear() }

    fun install(): Boolean {
        if (!Skilling.enabled) {
            logger.warn { "skilling wiring: skilling is disabled, leaving the ContentPlayer-only seams in place" }
            return false
        }

        Skilling.containerSupplier = { content ->
            val world = ownerOf(content)
            if (world == null) content.inventory else PlayerInventory.backpackOf(world)
        }

        Skilling.levelSupplier = { content, stat ->
            val world = ownerOf(content)
            if (world == null) 1 else runCatching { world.stats.getLevel(stat) }.getOrDefault(1)
        }

        Skilling.xpSink = { content, stat, amount ->
            val world = ownerOf(content)
            if (world != null) {
                world.stats.addExperience(stat, amount, SKILLING_SOURCE)
            }
        }

        Skilling.inventoryResend = { content ->
            val world = ownerOf(content)
            if (world != null) {
                PlayerInventory.sendBackpack(world)
                true
            } else {
                false
            }
        }

        Skilling.wornSupplier = { content -> ownerOf(content)?.let { PlayerInventory.wornOf(it) } }

        Skilling.beltSupplier = { content -> ToolBelt.storedIdsFor(content) }
        Gatherables.containerSupplier = Skilling.containerSupplier
        Gatherables.inventoryResend = { content -> Skilling.inventoryResend(content) }
        Gatherables.messageSender = { content, msg ->
            val world = ownerOf(content)
            if (world != null) {
                world.client.write(com.opennxt.net.game.serverprot.MessageGame(0, msg))
            }
        }
        Searchables.messageSender = Gatherables.messageSender
        Skilling.animationSink = { content, ids ->
            ownerOf(content)?.let { world ->
                com.opennxt.model.entity.rendering.PlayerUpdates.animate(
                    world.entity, com.opennxt.model.entity.rendering.blocks.PlayerAnimationBlock(ids, 0)
                )
            }
        }
        Skilling.messageSink = Gatherables.messageSender
        Skilling.faceSink = { content, angle ->
            ownerOf(content)?.let { world ->
                com.opennxt.model.entity.rendering.PlayerUpdates.faceDirection(world.entity, angle)
            }
        }

        logger.info {
            "skilling wiring: backpack, level and xp seams now point at the live player. " +
                "UPDATE_STAT stays gated (sendStats=" +
                "${com.opennxt.impl.stat.PlayerStatContainer.sendStatsEnabled}); xp accrues and persists " +
                "but is NOT transmitted."
        }
        return true
    }

    fun uninstall() {
        Skilling.containerSupplier = { it.inventory }
        Skilling.wornSupplier = { null }
        Skilling.beltSupplier = { emptySet() }
        Skilling.levelSupplier = { _, _ -> 1 }
        Skilling.xpSink = { _, _, _ -> }
        Skilling.inventoryResend = { false }
        clear()
    }

    object SKILLING_SOURCE : ExperienceSource(1.0) {
        override fun toString() = "SKILLING"
    }

    fun containerOf(content: ContentPlayer): ItemContainer = Skilling.containerSupplier(content)

    fun levelOf(content: ContentPlayer, stat: Stat): Int = Skilling.levelSupplier(content, stat)
}

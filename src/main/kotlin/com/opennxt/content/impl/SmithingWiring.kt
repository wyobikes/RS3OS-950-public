package com.opennxt.content.impl

import com.opennxt.OpenNXT
import com.opennxt.api.stat.ExperienceSource
import com.opennxt.content.ContentPlayer
import com.opennxt.model.entity.player.PlayerInventory
import com.opennxt.model.world.TileLocation
import com.opennxt.model.world.WorldPlayer
import mu.KotlinLogging
import java.util.Collections
import java.util.WeakHashMap

object SmithingWiring {
    private val logger = KotlinLogging.logger { }

    private val owners: MutableMap<ContentPlayer, java.lang.ref.WeakReference<WorldPlayer>> =
        Collections.synchronizedMap(WeakHashMap())

    fun bind(content: ContentPlayer, world: WorldPlayer) {
        if (owners[content]?.get() === world) return
        owners[content] = java.lang.ref.WeakReference(world)
    }

    fun ownerOf(content: ContentPlayer): WorldPlayer? =
        owners[content]?.get() ?: SkillingWiring.ownerOf(content)

    fun boundPlayers(): Int = synchronized(owners) { owners.values.count { it.get() != null } }

    internal fun clear() = synchronized(owners) { owners.clear() }

    fun install(): Int {
        if (!Smithing.enabled) {
            logger.warn { "smithing wiring: smithing is disabled, leaving the ContentPlayer-only seams in place" }
            return 0
        }

        Smithing.containerSupplier = { content ->
            val world = ownerOf(content)
            if (world == null) content.inventory else PlayerInventory.backpackOf(world)
        }

        Smithing.levelSupplier = { content, stat ->
            val world = ownerOf(content)
            if (world == null) 1 else runCatching { world.stats.getLevel(stat) }.getOrDefault(1)
        }

        Smithing.xpSink = { content, stat, amount ->
            ownerOf(content)?.stats?.addExperience(stat, amount, SMITHING_SOURCE)
        }

        Smithing.inventoryResend = { content ->
            val world = ownerOf(content)
            if (world != null) { PlayerInventory.sendBackpack(world); true } else false
        }

        Smithing.animationSink = { content, ids ->
            ownerOf(content)?.let { world ->
                com.opennxt.model.entity.rendering.PlayerUpdates.animate(
                    world.entity, com.opennxt.model.entity.rendering.blocks.PlayerAnimationBlock(ids, 0)
                )
            }
        }

        Smithing.messageSink = { content, msg ->
            ownerOf(content)?.client?.write(com.opennxt.net.game.serverprot.MessageGame(0, msg))
        }

        Smithing.locationSupplier = { content ->
            ownerOf(content)?.entity?.location?.let { TileLocation(it.x, it.y, it.plane) } ?: content.location
        }

        Smithing.onlineCheck = { content ->
            val owner = ownerOf(content)
            val world = runCatching { OpenNXT.world }.getOrNull()
            owner == null || world == null || world.isOnline(owner.name)
        }

        Smithing.panelOpener = { ctx -> SmithingPanel.openFor(ctx) }

        val bound = Smithing.install()
        logger.info {
            "smithing wiring: backpack, level, xp, animation and message seams now point at the live " +
                "player; $bound loc id(s) bound, ${Smithing.smeltRecipes.size} smelt and " +
                "${Smithing.smithRecipes.size} smith recipes"
        }
        return bound
    }

    fun uninstall() {
        Smithing.containerSupplier = { it.inventory }
        Smithing.levelSupplier = { _, _ -> 1 }
        Smithing.xpSink = { _, _, _ -> }
        Smithing.inventoryResend = { false }
        Smithing.animationSink = { _, _ -> }
        Smithing.messageSink = { _, _ -> }
        Smithing.onlineCheck = { true }
        Smithing.locationSupplier = { it.location }
        Smithing.smeltChoice = Smithing.DEFAULT_SMELT_CHOICE
        Smithing.smithChoice = Smithing.DEFAULT_SMITH_CHOICE
        Smithing.panelOpener = null
        clear()
    }

    object SMITHING_SOURCE : ExperienceSource(1.0) {
        override fun toString() = "SMITHING"
    }
}

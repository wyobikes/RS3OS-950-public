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

object CookingWiring {
    private val logger = KotlinLogging.logger { }

    private val owners: MutableMap<ContentPlayer, java.lang.ref.WeakReference<WorldPlayer>> =
        Collections.synchronizedMap(WeakHashMap())

    fun bind(content: ContentPlayer, world: WorldPlayer) {
        if (owners[content]?.get() === world) return
        owners[content] = java.lang.ref.WeakReference(world)
    }

    fun ownerOf(content: ContentPlayer): WorldPlayer? = owners[content]?.get()

    fun boundPlayers(): Int = synchronized(owners) { owners.values.count { it.get() != null } }

    internal fun clear() = synchronized(owners) { owners.clear() }

    fun install(): Int {
        if (!Cooking.enabled) {
            logger.warn { "cooking wiring: cooking is disabled, leaving the ContentPlayer-only seams in place" }
            return 0
        }

        Cooking.containerSupplier = { content ->
            val world = ownerOf(content)
            if (world == null) content.inventory else PlayerInventory.backpackOf(world)
        }

        Cooking.levelSupplier = { content, stat ->
            val world = ownerOf(content)
            if (world == null) 1 else runCatching { world.stats.getLevel(stat) }.getOrDefault(1)
        }

        Cooking.xpSink = { content, stat, amount ->
            ownerOf(content)?.stats?.addExperience(stat, amount, COOKING_SOURCE)
        }

        Cooking.inventoryResend = { content ->
            val world = ownerOf(content)
            if (world != null) { PlayerInventory.sendBackpack(world); true } else false
        }

        Cooking.animationSink = { content, ids ->
            ownerOf(content)?.let { world ->
                com.opennxt.model.entity.rendering.PlayerUpdates.animate(
                    world.entity, com.opennxt.model.entity.rendering.blocks.PlayerAnimationBlock(ids, 0)
                )
            }
        }

        Cooking.messageSink = { content, msg ->
            ownerOf(content)?.client?.write(com.opennxt.net.game.serverprot.MessageGame(0, msg))
        }

        Cooking.faceSink = { content, angle ->
            ownerOf(content)?.let { world ->
                com.opennxt.model.entity.rendering.PlayerUpdates.faceDirection(world.entity, angle)
            }
        }

        Cooking.locationSupplier = { content ->
            ownerOf(content)?.entity?.location?.let { TileLocation(it.x, it.y, it.plane) } ?: content.location
        }

        Cooking.onlineCheck = { content ->
            val owner = ownerOf(content)
            val world = runCatching { OpenNXT.world }.getOrNull()
            owner == null || world == null || world.isOnline(owner.name)
        }

        val bound = Cooking.install()
        logger.info {
            "cooking wiring: backpack, level, xp, animation and message seams now point at the live " +
                "player; $bound fire loc(s) bound, ${Cooking.recipes.size} recipes"
        }
        return bound
    }

    fun uninstall() {
        Cooking.containerSupplier = { it.inventory }
        Cooking.levelSupplier = { _, _ -> 1 }
        Cooking.xpSink = { _, _, _ -> }
        Cooking.inventoryResend = { false }
        Cooking.animationSink = { _, _ -> }
        Cooking.messageSink = { _, _ -> }
        Cooking.faceSink = { _, _ -> }
        Cooking.onlineCheck = { true }
        Cooking.locationSupplier = { it.location }
        Cooking.firePresent = { locId, x, z, plane -> Cooking.defaultFirePresent(locId, x, z, plane) }
        clear()
    }

    object COOKING_SOURCE : ExperienceSource(1.0) {
        override fun toString() = "COOKING"
    }
}

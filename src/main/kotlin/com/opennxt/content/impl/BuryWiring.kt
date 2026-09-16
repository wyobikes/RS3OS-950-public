package com.opennxt.content.impl

import com.opennxt.model.entity.player.PlayerInventory
import com.opennxt.model.entity.rendering.PlayerUpdates
import com.opennxt.model.entity.rendering.blocks.PlayerAnimationBlock
import com.opennxt.model.world.WorldPlayer
import com.opennxt.net.game.serverprot.MessageGame
import mu.KotlinLogging

object BuryWiring {
    private val logger = KotlinLogging.logger { }

    @Volatile
    private var installed = false

    fun isInstalled(): Boolean = installed

    private val hook: (WorldPlayer, String, Int, String, Int) -> Boolean = { world, action, itemId, name, slot ->
        if (!action.equals(Bury.BURY_ACTION, ignoreCase = true)) false
        else {
            val content = world.contentPlayer
            SkillingWiring.bind(content, world)
            val result = Bury.bury(content, itemId, name, slot, action)
            result.outcome != Bury.Outcome.NOT_MINE
        }
    }

    fun install(): Boolean {
        if (!Bury.enabled) {
            logger.warn { "bury wiring: burying is disabled, leaving the ContentPlayer-only seams in place" }
            return false
        }

        Bury.containerSupplier = { content ->
            val world = SkillingWiring.ownerOf(content)
            if (world == null) content.inventory else PlayerInventory.backpackOf(world)
        }

        Bury.xpSink = { content, stat, amount ->
            SkillingWiring.ownerOf(content)?.stats?.addExperience(stat, amount, SkillingWiring.SKILLING_SOURCE)
        }

        Bury.messageSink = { content, type, msg ->
            SkillingWiring.ownerOf(content)?.client?.write(MessageGame(type, msg))
        }

        Bury.animationSink = { content, ids ->
            SkillingWiring.ownerOf(content)?.let { world ->
                PlayerUpdates.animate(world.entity, PlayerAnimationBlock(ids, 0))
            }
        }

        Bury.inventoryResend = { content ->
            val world = SkillingWiring.ownerOf(content)
            if (world != null) { PlayerInventory.sendBackpack(world); true } else false
        }

        if (!ItemOps.backpackActionHooks.contains(hook)) ItemOps.backpackActionHooks.add(hook)
        installed = true

        logger.info {
            "bury wiring: the '${Bury.BURY_ACTION}' backpack row is hooked over " +
                "${runCatching { Bury.buryableItemCount() }.getOrDefault(-1)} buryable item ids; " +
                "${Bury.BONES_XP_TENTHS / 10.0} ${Bury.STAT.name} xp per Bones and " +
                "animation ${Bury.BURY_ANIMATION[0]} with '${Bury.BURY_MESSAGE}' type ${Bury.MESSAGE_TYPE}; " +
                "every other bone is priced by the " +
                "xp table, and the ${runCatching { Bury.buryableItemCount() - Bury.refTable().size }.getOrDefault(-1)} " +
                "the table does not name fall back to the default."
        }
        return true
    }

    fun uninstall() {
        ItemOps.backpackActionHooks.remove(hook)
        Bury.resetSeams()
        installed = false
    }
}

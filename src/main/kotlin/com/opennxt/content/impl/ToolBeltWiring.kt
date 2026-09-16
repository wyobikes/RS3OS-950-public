package com.opennxt.content.impl

import com.opennxt.model.entity.player.PlayerInventory
import com.opennxt.model.world.WorldPlayer
import com.opennxt.net.game.serverprot.MessageGame
import mu.KotlinLogging

object ToolBeltWiring {
    private val logger = KotlinLogging.logger { }

    @Volatile
    private var installed = false

    fun isInstalled(): Boolean = installed

    private val hook: (WorldPlayer, String, Int, String, Int) -> Boolean = { world, action, itemId, name, slot ->
        if (!action.equals(ToolBelt.ADD_ACTION, ignoreCase = true)) false
        else {
            val content = world.contentPlayer
            SkillingWiring.bind(content, world)
            val result = ToolBelt.add(content, itemId, name, slot, action)
            result.outcome != ToolBelt.Outcome.NOT_MINE
        }
    }

    fun install(): Boolean {
        if (!ToolBelt.enabled) {
            logger.warn { "toolbelt wiring: tool belt is disabled; not installed" }
            return false
        }

        ToolBelt.containerSupplier = { content ->
            val world = SkillingWiring.ownerOf(content)
            if (world == null) content.inventory else PlayerInventory.backpackOf(world)
        }

        ToolBelt.beltReader = { content -> SkillingWiring.ownerOf(content)?.toolbeltIds() }

        ToolBelt.beltWriter = { content, itemId ->
            SkillingWiring.ownerOf(content)?.addToToolbelt(itemId) ?: false
        }

        ToolBelt.messageSink = { content, type, msg ->
            SkillingWiring.ownerOf(content)?.client?.write(MessageGame(type, msg))
        }

        ToolBelt.inventoryResend = { content ->
            val world = SkillingWiring.ownerOf(content)
            if (world != null) { PlayerInventory.sendBackpack(world); true } else false
        }

        if (!ItemOps.backpackActionHooks.contains(hook)) ItemOps.backpackActionHooks.add(hook)
        installed = true

        logger.info {
            "toolbelt wiring: '${ToolBelt.ADD_ACTION}' hooked for " +
                "${runCatching { ToolBelt.beltItemCount() }.getOrDefault(-1)} items, base tier " +
                "${ToolBelt.baseTierIds().sorted()}"
        }
        return true
    }

    fun uninstall() {
        ItemOps.backpackActionHooks.remove(hook)
        ToolBelt.resetSeams()
        installed = false
    }
}

package com.opennxt.content.impl

import com.opennxt.model.entity.player.PlayerInventory
import com.opennxt.model.entity.rendering.PlayerUpdates
import com.opennxt.model.entity.rendering.blocks.PlayerAnimationBlock
import com.opennxt.model.world.WorldPlayer
import com.opennxt.net.game.serverprot.MessageGame
import mu.KotlinLogging

object FletchingWiring {
    private val logger = KotlinLogging.logger { }

    @Volatile
    private var installed = false

    fun isInstalled(): Boolean = installed

    private val hook: (WorldPlayer, String, Int, String, Int) -> Boolean = { world, action, itemId, name, slot ->
        if (!Fletching.claims(action, itemId)) false
        else {
            val content = world.contentPlayer
            SkillingWiring.bind(content, world)
            val result = Fletching.craft(content, itemId, name, slot, action)
            result.outcome != Fletching.Outcome.NOT_MINE && result.outcome != Fletching.Outcome.NO_RECIPE
        }
    }

    fun claimsOutcome(outcome: Fletching.Outcome): Boolean =
        outcome != Fletching.Outcome.NOT_MINE && outcome != Fletching.Outcome.NO_RECIPE

    fun install(): Boolean {
        if (!Fletching.enabled) {
            logger.warn { "fletching wiring: fletching is disabled, leaving the ContentPlayer-only seams in place" }
            return false
        }

        Fletching.containerSupplier = { content ->
            val world = SkillingWiring.ownerOf(content)
            if (world == null) content.inventory else PlayerInventory.backpackOf(world)
        }

        Fletching.levelSupplier = { content, stat ->
            val world = SkillingWiring.ownerOf(content)
            if (world == null) 1 else runCatching { world.stats.getLevel(stat) }.getOrDefault(1)
        }

        Fletching.xpSink = { content, stat, amount ->
            SkillingWiring.ownerOf(content)?.stats?.addExperience(stat, amount, SkillingWiring.SKILLING_SOURCE)
        }

        Fletching.messageSink = { content, type, msg ->
            SkillingWiring.ownerOf(content)?.client?.write(MessageGame(type, msg))
        }

        Fletching.animationSink = { content, ids, delay ->
            SkillingWiring.ownerOf(content)?.let { world ->
                PlayerUpdates.animate(world.entity, PlayerAnimationBlock(ids, delay))
            }
        }

        Fletching.inventoryResend = { content ->
            val world = SkillingWiring.ownerOf(content)
            if (world != null) { PlayerInventory.sendBackpack(world); true } else false
        }

        Fletching.onlineCheck = { content ->
            val owner = SkillingWiring.ownerOf(content)
            val world = runCatching { com.opennxt.OpenNXT.world }.getOrNull()
            owner == null || world == null || world.isOnline(owner.name)
        }

        Fletching.panelOpener = { content, recipe, product, slot ->
            val world = SkillingWiring.ownerOf(content)
            val panel = Fletching.panelFor(recipe.logId)
            if (world == null || panel == null || !MakeXPanel.enabled) false
            else {
                val categories = MakeXPanel.categoriesFor(panel)
                val opening = categories.firstOrNull { it.index == panel.defaultIndex }
                    ?: categories.firstOrNull { it.rows.isNotEmpty() }
                val selected = opening?.rows?.get(product.gridSlot)
                    ?: opening?.rows?.entries?.minByOrNull { it.key }?.value
                if (opening == null || selected == null) false
                else MakeXPanel.open(
                    world,
                    MakeXPanel.Session(
                        materialId = panel.materialId,
                        materialName = panel.materialName,
                        categoryA = panel.categoryEnum,
                        categoryB = panel.nameEnum,
                        recipe = opening.recipeEnum,
                        rows = opening.rows,
                        gridToSlot = opening.gridToSlot,
                        selected = selected,
                        countOf = { w, row ->
                            val r = Fletching.recipeFor(panel.materialId)
                            val p = r?.products?.firstOrNull { it.itemId == row.itemId }
                                ?: panel.products.firstOrNull { it.itemId == row.itemId }
                            if (r == null || p == null) 0
                            else Fletching.cyclesAvailable(PlayerInventory.backpackOf(w), r, p)
                        },
                        onConfirm = { w, row, count ->
                            SkillingWiring.bind(w.contentPlayer, w)
                            Fletching.startFromPanel(w.contentPlayer, panel.materialId, row.itemId, slot, count)
                                .outcome == Fletching.Outcome.STARTED
                        },
                        categories = categories,
                        categoryIndex = panel.defaultIndex
                    )
                )
            }
        }

        if (!ItemOps.backpackActionHooks.contains(hook)) ItemOps.backpackActionHooks.add(hook)
        installed = true

        val recipes = runCatching { Fletching.recipes }.getOrDefault(emptyMap())
        val panelStats = runCatching { Fletching.panelStats() }
            .getOrDefault(Fletching.PanelStats(0, 0, 0, 0, 0, 0))
        logger.info {
            "fletching: '${Fletching.CRAFT_ACTION}' hooked over " +
                "${runCatching { Fletching.craftableItemCount() }.getOrDefault(-1)} item ids, " +
                "${recipes.size} material(s) (${recipes.values.count { it.default != null }} with a default product), " +
                "${panelStats.panels} make-X panel(s) with ${panelStats.products} product row(s)"
        }
        return true
    }

    fun uninstall() {
        ItemOps.backpackActionHooks.remove(hook)
        Fletching.resetSeams()
        installed = false
    }
}

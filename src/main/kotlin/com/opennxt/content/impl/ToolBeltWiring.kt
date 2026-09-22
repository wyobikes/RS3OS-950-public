package com.opennxt.content.impl

import com.opennxt.model.entity.player.PlayerInventory
import com.opennxt.model.vars.VarbitBits
import com.opennxt.model.world.WorldPlayer
import com.opennxt.net.game.serverprot.MessageGame
import com.opennxt.resources.Names950
import com.opennxt.resources.sqlite.RsDatabase
import mu.KotlinLogging

object ToolBeltWiring {
    private val logger = KotlinLogging.logger { }

    @Volatile
    private var installed = false

    fun isInstalled(): Boolean = installed

    // Upgradeable tools are stored in the belt as a tier index inside a level varbit. The
    // tier enums map item id -> tier index (the client maps it back to an item via the same
    // enum). Hatchet and pickaxe are the two the skilling system relies on.
    private val hatchetTierByItem: Map<Int, Int> by lazy {
        runCatching { loadTierByItem(Names950.enumId("toolbelt_hatchets")) }
            .onFailure { logger.warn(it) { "toolbelt wiring: 'toolbelt_hatchets' enum unavailable" } }
            .getOrDefault(emptyMap())
    }
    private val pickaxeTierByItem: Map<Int, Int> by lazy {
        runCatching { loadTierByItem(Names950.enumId("toolbelt_pickaxes")) }
            .onFailure { logger.warn(it) { "toolbelt wiring: 'toolbelt_pickaxes' enum unavailable" } }
            .getOrDefault(emptyMap())
    }

    private fun loadTierByItem(enumId: Int): Map<Int, Int> {
        if (!RsDatabase.available) return emptyMap()
        val out = HashMap<Int, Int>()
        runCatching {
            RsDatabase.queryAll("SELECT key, value FROM enum_entry WHERE enum_id = $enumId") { rs ->
                rs.getString("value").toInt() to rs.getString("key").toInt()
            }.forEach { (itemId, tier) -> out[itemId] = tier }
        }.onFailure { logger.warn(it) { "toolbelt wiring: could not load tier enum $enumId" } }
        return out
    }

    private fun writeLevel(world: WorldPlayer, varbitName: String, tier: Int) {
        val varbitId = runCatching { Names950.varbitId(varbitName) }.getOrNull() ?: return
        val bits = VarbitBits.bits(varbitId) ?: return
        val current = world.varpValue(bits.varp)
        world.setVarpOverride(bits.varp, bits.write(current, tier), store = true)
    }

    /**
     * Push the server's view of [WorldPlayer.toolbeltIds] into the client-side toolbelt varps,
     * so the panel shows the tools the player has actually added rather than the login defaults.
     */
    fun syncVarps(world: WorldPlayer) {
        val belt = world.toolbeltIds()
        val hatchetTier = belt.mapNotNull { hatchetTierByItem[it] }.maxOrNull() ?: 0
        val pickaxeTier = belt.mapNotNull { pickaxeTierByItem[it] }.maxOrNull() ?: 0
        writeLevel(world, "toolbelt_hatchet_level", hatchetTier)
        writeLevel(world, "toolbelt_pickaxe_level", pickaxeTier)
        logger.info {
            "toolbelt wiring: synced belt varps for ${world.name} " +
                    "(hatchet tier $hatchetTier, pickaxe tier $pickaxeTier)"
        }
    }

    private val hook: (WorldPlayer, String, Int, String, Int) -> Boolean = { world, action, itemId, name, slot ->
        if (!action.equals(ToolBelt.ADD_ACTION, ignoreCase = true)) false
        else {
            val content = world.contentPlayer
            SkillingWiring.bind(content, world)
            val result = ToolBelt.add(content, itemId, name, slot, action)
            if (result.outcome == ToolBelt.Outcome.ADDED) syncVarps(world)
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
            if (world != null) {
                PlayerInventory.sendBackpack(world); true
            } else false
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

package com.opennxt.model.entity.player

import com.opennxt.OpenNXT
import com.opennxt.model.items.Item
import com.opennxt.model.items.ItemContainer
import com.opennxt.model.world.WorldPlayer
import com.opennxt.net.game.serverprot.InvEntry
import com.opennxt.net.game.serverprot.UpdateInvFull
import com.opennxt.net.game.serverprot.UpdateInvPartial
import com.opennxt.net.game.serverprot.variables.ClientSetvarcstrSmall
import mu.KotlinLogging
import java.util.Collections
import java.util.WeakHashMap

object PlayerInventory {
    private val logger = KotlinLogging.logger { }

    val enabled: Boolean = System.getProperty("opennxt.experiment.inventory") != "false"

    val backpackInv: Int = System.getProperty("opennxt.inventory.backpackInv")?.toIntOrNull() ?: 93

    val starterEnabled: Boolean = System.getProperty("opennxt.experiment.inventory.starter") != "false"

    val starterKit: List<Item> = listOf(
        Item(995, 25),
        Item(1265, 1),
        Item(1351, 1),
        Item(590, 1)
    )

    val wornInv: Int = System.getProperty("opennxt.inventory.wornInv")?.toIntOrNull() ?: 94

    const val MAX_EQUIP_SLOT = 18

    const val WORN_SIZE = MAX_EQUIP_SLOT + 1

    const val WEAPON_SLOT = 3

    val equipEnabled: Boolean
        get() = System.getProperty("opennxt.experiment.equip") == "true"

    private val wornContainers: MutableMap<WorldPlayer, ItemContainer> =
        Collections.synchronizedMap(WeakHashMap<WorldPlayer, ItemContainer>())

    fun wornOf(player: WorldPlayer): ItemContainer =
        wornContainers.getOrPut(player) { player.save.restoreWorn() }

    fun forgetWorn(player: WorldPlayer) {
        wornContainers.remove(player)
    }

    fun wornPacketFor(container: ItemContainer): UpdateInvFull = fullPacketFor(container, wornInv)

    fun sendWorn(player: WorldPlayer) {
        if (!enabled || !equipEnabled) return

        val mapped = try {
            OpenNXT.protocol.serverProtNames.values["UPDATE_INV_FULL"] != null
        } catch (t: Throwable) {
            false
        }
        if (!mapped) {
            if (!wornUnmappedWarned) {
                wornUnmappedWarned = true
                logger.warn {
                    "No UPDATE_INV_FULL opcode mapped; worn equipment will not be shown"
                }
            }
            return
        }

        val container = wornOf(player)
        if (wornSends < 3) {
            wornSends++
            logger.info {
                "UPDATE_INV_FULL (worn) send #$wornSends: inv $wornInv, ${container.size} slot(s), " +
                    "${container.usedSlots()} occupied, for ${player.name}"
            }
        }
        player.client.write(wornPacketFor(container))
    }

    val WORN_PROVENANCE: String =
        "worn equipment: inv $wornInv, $WORN_SIZE slots; -Dopennxt.experiment.equip=true to enable equipping"

    @Volatile
    private var wornUnmappedWarned = false

    @Volatile
    private var wornSends = 0

    private val backpacks: MutableMap<WorldPlayer, ItemContainer> =
        Collections.synchronizedMap(WeakHashMap<WorldPlayer, ItemContainer>())

    fun backpackOf(player: WorldPlayer): ItemContainer = backpacks.getOrPut(player) {
        val restored = player.save.restoreBackpack()
        if (starterEnabled && restored.usedSlots() == 0 && player.save.bank.isEmpty() && player.save.worn.isEmpty()) {
            starterKit.forEachIndexed { slot, item -> restored[slot] = item }
            logger.info { "starter kit given to ${player.name}" }
        }
        restored
    }

    fun forget(player: WorldPlayer) {
        backpacks.remove(player)
    }

    fun fullPacketFor(container: ItemContainer, inv: Int = backpackInv): UpdateInvFull {
        val width = if (trimFullEnabled()) container.lastOccupiedSlot() + 1 else container.size
        return UpdateInvFull(
            inv = inv,
            slots = (0 until width).map { slot ->
                container[slot]?.let { InvEntry(it.id, it.amount) }
            },
            flags = 0
        )
    }

    fun trimFullEnabled(): Boolean = System.getProperty("opennxt.experiment.inv.trimFull") == "true"

    private fun ItemContainer.lastOccupiedSlot(): Int {
        var last = -1
        for (slot in 0 until size) if (this[slot] != null) last = slot
        return last
    }

    fun loginInvsEnabled(): Boolean = System.getProperty("opennxt.experiment.ui.loginInvs") != "false"

    val LOGIN_895_IDS: List<Int> = listOf(960, 8778, 8780, 8782, 54860, 54862, 54864, 54866, 54868, 54870, 63190)

    fun sendLoginContainers(player: WorldPlayer) {
        if (!enabled || !loginInvsEnabled()) return
        if (OpenNXT.protocol.serverProtNames.values["UPDATE_INV_FULL"] == null) return
        val worn = wornOf(player)
        player.client.write(UpdateInvFull(inv = 891, slots = emptyList(), flags = 0))
        player.client.write(wornPacketFor(worn))
        player.client.write(UpdateInvFull(inv = 895, slots = LOGIN_895_IDS.map { InvEntry(it, 0) }, flags = 0))
        for (id in 2254..2275) player.client.write(ClientSetvarcstrSmall(id, ""))
        logger.info {
            "ui.loginInvs: sent invs 891, ${wornInv} (${worn.usedSlots()} occupied), 895 and varcstrs 2254..2275 to ${player.name}"
        }
    }

    fun partialPacketFor(container: ItemContainer, slots: Iterable<Int>, inv: Int = backpackInv): UpdateInvPartial =
        UpdateInvPartial(
            inv = inv,
            entries = slots.map { slot -> slot to container[slot]?.let { InvEntry(it.id, it.amount) } },
            flags = 0
        )

    fun sendBackpack(player: WorldPlayer) {
        if (!enabled) return

        if (OpenNXT.protocol.serverProtNames.values["UPDATE_INV_FULL"] == null) {
            if (!unmappedWarned) {
                unmappedWarned = true
                logger.warn {
                    "Build ${OpenNXT.protocol.effectiveBuild} has no UPDATE_INV_FULL opcode in " +
                        "data/prot/<build>/serverProtNames.toml; the backpack will not be sent"
                }
            }
            return
        }

        val container = try {
            backpackOf(player)
        } catch (t: Throwable) {
            if (!failureWarned) {
                failureWarned = true
                logger.error(t) {
                    "Could not build ${player.name}'s backpack; inventory not sent"
                }
            }
            return
        }

        if (sends < 3) {
            sends++
            logger.info {
                "UPDATE_INV_FULL send #$sends: inv $backpackInv, ${container.size} slot(s), " +
                    "${container.usedSlots()} occupied, for ${player.name}"
            }
        }

        player.client.write(fullPacketFor(container))
    }

    @Volatile
    private var unmappedWarned = false

    @Volatile
    private var failureWarned = false

    @Volatile
    private var sends = 0
}

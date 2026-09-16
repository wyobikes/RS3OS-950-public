package com.opennxt.content.impl

import com.opennxt.content.ContentPlayer
import com.opennxt.model.entity.player.PlayerInventory
import com.opennxt.model.world.WorldPlayer
import com.opennxt.net.game.clientprot.IfButtonN
import com.opennxt.net.game.serverprot.InvEntry
import com.opennxt.net.game.serverprot.MessageGame
import com.opennxt.net.game.serverprot.UpdateInvFull
import mu.KotlinLogging

object BanksWiring {
    private val logger = KotlinLogging.logger { }

    private val owners: MutableMap<ContentPlayer, java.lang.ref.WeakReference<WorldPlayer>> =
        java.util.Collections.synchronizedMap(java.util.WeakHashMap())

    fun bind(content: ContentPlayer, world: WorldPlayer) {
        val existing = owners[content]?.get()
        if (existing === world) return
        owners[content] = java.lang.ref.WeakReference(world)
    }

    fun ownerOf(content: ContentPlayer): WorldPlayer? = owners[content]?.get()

    fun boundPlayers(): Int = synchronized(owners) { owners.values.count { it.get() != null } }

    internal fun clear() = synchronized(owners) { owners.clear() }

    private class LiveSink(val world: WorldPlayer) : Banks.Sink {
        override fun openSub(interfaceId: Int, parent: Int, component: Int, walkable: Boolean) =
            world.interfaces.open(id = interfaceId, parent = parent, component = component, walkable = walkable)

        override fun setEvents(interfaceId: Int, component: Int, fromSlot: Int, toSlot: Int, mask: Int) =
            world.interfaces.events(interfaceId, component, fromSlot, toSlot, mask)

        override fun closeSub(parent: Int, component: Int) =
            world.interfaces.close(parent, component)

        override fun updateInvFull(inv: Int, slots: List<Pair<Int, Int>?>) {
            world.client.write(
                UpdateInvFull(
                    inv = inv,
                    slots = slots.map { entry -> entry?.let { InvEntry(it.first, it.second) } },
                    flags = 0
                )
            )
        }

        override fun setVarp(id: Int, value: Int) = world.setVarpOverride(id, value, store = false)

        override fun varpValue(id: Int): Int = world.varpValue(id)

        override fun runClientScript(script: Int, args: List<Any>) {
            world.client.write(com.opennxt.net.game.serverprot.RunClientScript(script, args.toTypedArray()))
        }
    }

    private class MetalBankLiveSink(val world: WorldPlayer) : MetalBanks.Sink {
        override fun openSub(interfaceId: Int, parent: Int, component: Int, walkable: Boolean) =
            world.interfaces.open(id = interfaceId, parent = parent, component = component, walkable = walkable)

        override fun updateInvFull(inv: Int, slots: List<Pair<Int, Int>?>) {
            world.client.write(
                UpdateInvFull(inv = inv, slots = slots.map { e -> e?.let { InvEntry(it.first, it.second) } }, flags = 0)
            )
        }

        override fun stopTransmit(inv: Int) {
            world.client.write(com.opennxt.net.game.serverprot.UpdateInvStopTransmit(inv))
        }

        override fun closeSub(parent: Int, component: Int) = world.interfaces.close(parent, component)

        override fun message(text: String) {
            world.client.write(MessageGame(0, text))
        }

        override fun resendBackpack() = PlayerInventory.sendBackpack(world)
    }

    fun install(): Boolean {
        MetalBanks.backpackSupplier = { content -> ownerOf(content)?.let { PlayerInventory.backpackOf(it) } ?: content.inventory }
        MetalBanks.sinkSupplier = { content -> ownerOf(content)?.let { MetalBankLiveSink(it) } }
        if (!Banks.uiEnabled) {
            logger.info {
                "banks wiring: bank interface disabled; clicks update the bank without a screen " +
                    "(-Dopennxt.experiment.banks.ui=true to enable)"
            }
            return false
        }
        Banks.sinkSupplier = { content -> ownerOf(content)?.let { LiveSink(it) } }
        logger.warn {
            "banks wiring: bank interface ${Banks.BANK_INTERFACE} enabled, mounted at " +
                "${Banks.GAMEFRAME}:${Banks.BANK_MOUNT}, inv ${Banks.BANK_INV} (${Banks.BANK_INV_SLOTS} slots)"
        }
        return true
    }

    fun uninstall() {
        Banks.sinkSupplier = { null }
        MetalBanks.sinkSupplier = { null }
        MetalBanks.backpackSupplier = { it.inventory }
        clear()
    }

    fun handleLocClick(world: WorldPlayer, locId: Int, action: String, x: Int, z: Int, plane: Int): Boolean {
        if (MetalBanks.isMetalBankOption(locId, action)) {
            val metalPlayer = world.contentPlayerAt()
            bind(metalPlayer, world)
            MetalBanks.handleLocOption(metalPlayer, locId, action, "loc $locId '$action' at ($x,$z,plane $plane)")
            return true
        }
        if (action != Banks.BANK && action != Banks.USE)
            return LocWiring.routeOther(world, locId, action, x, z, plane)
        val content = world.contentPlayerAt()
        bind(content, world)
        val result = com.opennxt.content.ContentRegistry.dispatchLoc(content, locId, action, x, z, plane)
        return when (result) {
            is com.opennxt.content.DispatchResult.Handled -> {
                if (Banks.uiEnabled) PlayerInventory.sendBackpack(world)
                logger.info {
                    "banks: ${world.name} '$action' on loc $locId at ($x,$z,plane $plane) -> ${result.value}" +
                        (if (Banks.uiEnabled) ""
                         else " (screen off: -Dopennxt.experiment.banks.ui=true)")
                }
                true
            }
            is com.opennxt.content.DispatchResult.NoHandler -> false
            else -> false
        }
    }

    fun handleButton(world: WorldPlayer, packet: IfButtonN): Boolean {
        if (!Banks.uiEnabled) return false
        val content = world.contentPlayerAt()
        bind(content, world)
        when (val metal = MetalBanks.handleButton(content, packet.interfaceId, packet.component, packet.buttonOp)) {
            is MetalBanks.ButtonResult.NotOurs -> Unit
            else -> {
                logger.info {
                    "metal bank: ${world.name} IF_BUTTON${packet.buttonOp} on ${packet.interfaceId}:${packet.component} -> $metal"
                }
                return true
            }
        }
        if (Banks.handleClose(content, packet.interfaceId, packet.component)) {
            logger.info {
                "bank screen: ${world.name} IF_BUTTON${packet.buttonOp} on ${packet.interfaceId}:" +
                    "${packet.component} closed the bank"
            }
            return true
        }
        return handleItemButton(world, content, packet)
    }

    fun handleDrag(world: WorldPlayer, packet: com.opennxt.net.game.clientprot.generated.IfButtond): Boolean {
        if (!Banks.uiEnabled) return false
        val source = packet.sourcehash
        val target = packet.targethash
        if ((source ushr 16) != Banks.BANK_INTERFACE || (target ushr 16) != Banks.BANK_INTERFACE) return false
        val content = world.contentPlayerAt()
        bind(content, world)
        val result = Banks.handleDrag(
            content, source and 0xffff, packet.sourceslot, target and 0xffff, packet.targetslot,
            sourceObj = packet.sourceobj
        )
        return when (result) {
            is Banks.ButtonResult.NotOurs -> false
            is Banks.ButtonResult.Refused -> {
                result.message?.let { world.client.write(MessageGame(0, it)) }
                logger.info {
                    "bank tabs: ${world.name} drag 517:${source and 0xffff} slot ${packet.sourceslot} -> " +
                        "517:${target and 0xffff} slot ${packet.targetslot} refused - ${result.why}"
                }
                true
            }
            else -> {
                logger.info {
                    "bank tabs: ${world.name} drag 517:${source and 0xffff} slot ${packet.sourceslot} -> " +
                        "517:${target and 0xffff} slot ${packet.targetslot}: $result"
                }
                true
            }
        }
    }

    private fun handleItemButton(world: WorldPlayer, content: ContentPlayer, packet: IfButtonN): Boolean {
        val backpack = PlayerInventory.backpackOf(world)
        val worn = if (PlayerInventory.equipEnabled) world.worn else null
        val result = Banks.handleButton(
            content, packet.interfaceId, packet.component, packet.buttonOp,
            packet.mid, packet.arg2,
            Banks.Containers(backpack, worn,
                if (MoneyPouch.enabled) Banks.CoinPouch({ world.coinPouch() }, { world.setCoinPouch(it) }) else null)
        )
        when (result) {
            is Banks.ButtonResult.NotOurs -> return false

            is Banks.ButtonResult.Deposited -> {
                if (result.component == Banks.DEPOSIT_WORN_COMPONENT) {
                    PlayerInventory.sendWorn(world)
                    world.entity.model.dirty = true
                } else if (result.component == Banks.DEPOSIT_COINPOUCH_COMPONENT) {
                    MoneyPouch.sendTotal(world)
                } else {
                    PlayerInventory.sendBackpack(world)
                }
                world.client.write(
                    MessageGame(0, "You deposit ${result.units} item(s) into your bank.")
                )
                if (result.leftBehind > 0) {
                    world.client.write(MessageGame(0, "Your bank is too full to hold everything."))
                }
                logger.info {
                    "bank screen: ${world.name} IF_BUTTON${packet.buttonOp} on 517:${result.component} " +
                        "deposited ${result.units} item(s) over ${result.ids} id(s) from ${result.source}; " +
                        "${result.leftBehind} id(s) left behind; bank ${result.bankSlots} slot(s)."
                }
            }

            is Banks.ButtonResult.Withdrew -> {
                PlayerInventory.sendBackpack(world)
                logger.info {
                    "bank screen: ${world.name} withdrew ${result.withdrawn} x ${result.id} from bank " +
                        "slot ${result.slot} (${result.stillBanked} still banked)"
                }
                if (result.withdrawn < result.requested) {
                    world.client.write(MessageGame(0, "You don't have enough room for all of that."))
                }
            }

            is Banks.ButtonResult.DepositedOne -> {
                PlayerInventory.sendBackpack(world)
                logger.info {
                    "bank screen: ${world.name} deposited ${result.moved} x ${result.id} from backpack " +
                        "slot ${result.slot} (${result.bankedTotal} banked, ${result.bankSlots} slot(s))"
                }
            }

            is Banks.ButtonResult.QuantityChanged -> {
                logger.info {
                    "bank screen: ${world.name} set the bank move quantity to " +
                        (if (result.now == Banks.ALL) "ALL" else "${result.now}") +
                        " on 517:${result.component} (was " +
                        (if (result.was == Banks.ALL) "ALL" else "${result.was}") + ")."
                }
            }

            is Banks.ButtonResult.Refused -> {
                result.message?.let { world.client.write(MessageGame(0, it)) }
                logger.info {
                    "bank screen: ${world.name} IF_BUTTON${packet.buttonOp} on 517:${result.component} " +
                        "refused: ${result.why}"
                }
            }

            is Banks.ButtonResult.TabChanged -> {
                logger.info {
                    "bank tabs: ${world.name} IF_BUTTON${packet.buttonOp} on 517:${result.component} slot " +
                        "${packet.arg2} -> ${result.detail}"
                }
            }
        }
        return true
    }
}

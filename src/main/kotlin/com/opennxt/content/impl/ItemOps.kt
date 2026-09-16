package com.opennxt.content.impl

import com.opennxt.model.entity.player.PlayerInventory
import com.opennxt.model.items.Item
import com.opennxt.model.world.WorldPlayer
import com.opennxt.net.game.clientprot.IfButtonN
import com.opennxt.net.game.serverprot.MessageGame
import com.opennxt.resources.sqlite.SqliteItemCodec
import mu.KotlinLogging

object ItemOps {
    private val logger = KotlinLogging.logger { }

    const val BACKPACK_IFACE = 1473
    const val BACKPACK_ITEM_LAYER = 5

    const val WORN_IFACE = 1464
    const val WORN_ITEM_LAYER = 15

    private const val NO_ITEM = 0xFFFFFF

    private val EQUIP_ACTIONS = setOf("wield", "wear", "equip")
    private val CONSUME_ACTIONS = setOf("eat", "drink", "consume")

    val enabled: Boolean get() = System.getProperty("opennxt.experiment.itemOps") != "false"

    val backpackActionHooks: MutableList<(WorldPlayer, String, Int, String, Int) -> Boolean> =
        java.util.concurrent.CopyOnWriteArrayList()

    fun handleButton(player: WorldPlayer, packet: IfButtonN): Boolean {
        if (!enabled) return false
        val iface = packet.interfaceId
        val comp = packet.component
        val backpack = iface == BACKPACK_IFACE && comp == BACKPACK_ITEM_LAYER
        val worn = iface == WORN_IFACE && comp == WORN_ITEM_LAYER
        if (!backpack && !worn) return false

        val itemId = packet.mid
        val slot = packet.arg2
        if (itemId == NO_ITEM || itemId < 0) return false

        return if (backpack) backpack(player, packet.buttonOp, itemId, slot)
        else worn(player, packet.buttonOp, itemId, slot)
    }

    internal fun rowForOp(player: WorldPlayer, iface: Int, component: Int, op: Int): Int? =
        rowForOp(player.interfaces.armedMaskFor(iface, component), op)

    internal fun rowForOp(mask: Int?, op: Int): Int? {
        if (mask == null) return op - 1
        val index = enabledOps(mask).indexOf(op)
        return if (index < 0) null else index
    }

    internal fun enabledOps(mask: Int): List<Int> = (1..10).filter { (mask shr it) and 1 == 1 }

    @Volatile
    var slotRefusals: Int = 0
        private set

    private fun slotInRange(player: WorldPlayer, grid: String, op: Int, itemId: Int, slot: Int, size: Int): Boolean {
        if (slot in 0 until size) return true
        slotRefusals++
        logger.info { "itemOps ${player.name}: refused $grid op $op: slot $slot outside 0..${size - 1} (item $itemId)" }
        return false
    }

    const val DROP_ACTION = "drop"

    internal const val DEFAULT_DROP_ROW = 4

    internal fun actionForRow(actions: Array<String?>?, row: Int?): String? {
        if (row == null) return null
        val explicit = actions?.getOrNull(row)
        if (explicit != null) return explicit
        return if (row == DEFAULT_DROP_ROW) "Drop" else null
    }

    const val DROP_SOUND = 4173
    private const val DROP_SOUND_VOLUME = 140
    private const val DROP_SOUND_EXTRA = 256

    @Volatile
    var drops: Int = 0
        private set

    @Volatile
    var dropRefusals: Int = 0
        private set

    internal fun drop(player: WorldPlayer, container: com.opennxt.model.items.ItemContainer, slot: Int, itemId: Int, name: String): Boolean {
        val world = runCatching { com.opennxt.OpenNXT.world }.getOrNull()
        if (world == null) {
            dropRefusals++
            logger.warn { "itemOps ${player.name}: drop of $name refused: no world is running" }
            return true
        }
        val tile = player.entity.location
        val item = container.removeSlot(slot) ?: return true
        val ground = runCatching {
            world.groundItems.spawnItem(
                itemId = item.id, itemName = name, quantity = item.amount,
                tile = com.opennxt.model.world.TileLocation(tile.x, tile.y, tile.plane),
                owner = player.name, source = "drop:${player.name}"
            )
        }
        if (ground.isFailure) {
            container[slot] = item
            dropRefusals++
            logger.error(ground.exceptionOrNull()) { "itemOps ${player.name}: drop of $name failed; restored to slot $slot" }
            return true
        }
        drops++
        PlayerInventory.sendBackpack(player)
        runCatching {
            player.client.write(
                com.opennxt.net.game.serverprot.generated.VorbisSound(
                    sound = DROP_SOUND, count = 1, delay = 0, volume = DROP_SOUND_VOLUME, extra = DROP_SOUND_EXTRA
                )
            )
        }
        logger.info {
            "itemOps ${player.name}: dropped $name x${item.amount} (obj ${item.id}) from slot $slot at " +
                "(${tile.x},${tile.y},${tile.plane})"
        }
        return true
    }

    private fun backpack(player: WorldPlayer, op: Int, itemId: Int, slot: Int): Boolean {
        val container = PlayerInventory.backpackOf(player)
        if (!slotInRange(player, "backpack", op, itemId, slot, container.size)) return true
        val held = container[slot]
        if (held == null || held.id != itemId) {
            logger.info {
                "itemOps ${player.name}: refused backpack op $op: client item $itemId, " +
                    "server has ${held?.id ?: "nothing"} in slot $slot"
            }
            return true
        }
        val definition = SqliteItemCodec.load(itemId)
        val row = rowForOp(player, BACKPACK_IFACE, BACKPACK_ITEM_LAYER, op)
        val action = actionForRow(definition?.inventoryActions, row)
        val name = definition?.name ?: "item $itemId"
        if (action == null) {
            logger.info {
                "itemOps ${player.name}: $name has no option for op $op " +
                    "(menu row ${row?.plus(1) ?: "unmapped"}, enabled ops " +
                    "${player.interfaces.armedMaskFor(BACKPACK_IFACE, BACKPACK_ITEM_LAYER)
                        ?.let { m -> (1..10).filter { (m shr it) and 1 == 1 } } ?: "none"}); ignored"
            }
            return true
        }
        val key = action.lowercase()
        return when {
            key in EQUIP_ACTIONS -> equip(player, container, slot, itemId, name, action)
            key in CONSUME_ACTIONS -> {
                container.removeSlot(slot)
                PlayerInventory.sendBackpack(player)
                player.client.write(MessageGame(0, "You ${key} the ${name.lowercase()}."))
                logger.info { "itemOps ${player.name}: $action $name (slot $slot)" }
                true
            }
            key == DROP_ACTION -> drop(player, container, slot, itemId, name)
            else -> {
                for (hook in backpackActionHooks) {
                    val claimed = runCatching { hook(player, action, itemId, name, slot) }.getOrElse {
                        logger.error(it) { "itemOps ${player.name}: backpack action hook failed on '$action' $name" }
                        false
                    }
                    if (claimed) return true
                }
                logger.info { "itemOps ${player.name}: '$action' on $name (slot $slot) is not implemented" }
                true
            }
        }
    }

    internal fun equip(
        player: WorldPlayer,
        container: com.opennxt.model.items.ItemContainer,
        slot: Int,
        itemId: Int,
        name: String,
        action: String
    ): Boolean {
        if (!PlayerInventory.equipEnabled) {
            logger.warn {
                "itemOps ${player.name}: '$action' on $name refused: equipment disabled " +
                    "(-Dopennxt.experiment.equip=true to enable)"
            }
            player.client.write(MessageGame(0, "Equipment is disabled on this server."))
            return true
        }
        val definition = SqliteItemCodec.load(itemId)
        val wearSlot = definition?.equipSlotId
        if (wearSlot == null || wearSlot < 0 || wearSlot >= PlayerInventory.WORN_SIZE) {
            logger.info { "itemOps ${player.name}: $name has no valid equip slot; refused" }
            player.client.write(MessageGame(0, "You can't wear that."))
            return true
        }
        val worn = player.worn
        val replaced = worn[wearSlot]
        val held = container[slot]
        if (held == null || held.id != itemId) {
            logger.info { "itemOps ${player.name}: refused $action: backpack slot $slot no longer holds $itemId" }
            return true
        }
        val merge = replaced != null && replaced.id == held.id && container.stacks(held.id)
        if (merge && replaced!!.amount.toLong() + held.amount > Int.MAX_VALUE) {
            logger.info { "itemOps ${player.name}: refused $action: worn stack would overflow (+${held.amount})" }
            player.client.write(MessageGame(0, "You can't carry that many."))
            return true
        }
        container.removeSlot(slot)
        if (merge) {
            worn[wearSlot] = Item(itemId, replaced!!.amount + held.amount)
        } else {
            worn[wearSlot] = Item(itemId, held.amount)
        }
        if (replaced != null && !merge) {
            val back = container.add(replaced)
            if (!back.complete) {
                worn[wearSlot] = replaced
                container[slot] = held
                logger.error { "itemOps ${player.name}: swap aborted: no room for ${replaced.id}" }
                return true
            }
        }
        PlayerInventory.sendBackpack(player)
        PlayerInventory.sendWorn(player)
        player.entity.model.dirty = true
        logger.info {
            "itemOps ${player.name}: $action $name (item $itemId) from backpack slot $slot into worn " +
                "slot $wearSlot" + (if (replaced != null) ", ${replaced.id} back to the backpack" else "")
        }
        return true
    }

    private fun worn(player: WorldPlayer, op: Int, itemId: Int, slot: Int): Boolean {
        if (!PlayerInventory.equipEnabled) return true
        val worn = player.worn
        if (!slotInRange(player, "worn", op, itemId, slot, worn.size)) return true
        val held = worn[slot]
        if (held == null || held.id != itemId) {
            logger.info {
                "itemOps ${player.name}: refused worn op $op: client item $itemId, " +
                    "server has ${held?.id ?: "nothing"} in worn slot $slot"
            }
            return true
        }
        val container = PlayerInventory.backpackOf(player)
        if (container.isFull()) {
            player.client.write(MessageGame(0, "Your backpack is too full."))
            return true
        }
        worn.removeSlot(slot)
        val back = container.add(held)
        if (!back.complete) {
            val left = held.amount - back.added
            worn[slot] = Item(held.id, left)
            logger.warn { "itemOps ${player.name}: only ${back.added} of ${held.amount} x ${held.id} fit in the backpack; $left stay worn" }
        }
        PlayerInventory.sendBackpack(player)
        PlayerInventory.sendWorn(player)
        player.entity.model.dirty = true
        val name = SqliteItemCodec.load(itemId)?.name ?: "item $itemId"
        logger.info { "itemOps ${player.name}: removed $name from worn slot $slot (op $op)" }
        return true
    }
}

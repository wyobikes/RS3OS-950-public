package com.opennxt.content.impl

import com.opennxt.content.ContentPlayer
import com.opennxt.model.items.Item
import com.opennxt.model.items.ItemContainer
import mu.KotlinLogging

object ToolBelt {
    private val logger = KotlinLogging.logger { }

    val enabled: Boolean get() = System.getProperty("opennxt.experiment.toolbelt") != "false"

    const val ADD_ACTION = "Add to tool belt"

    fun beltItemIds(): List<Int> = ItemActions.idsWithAction(ADD_ACTION)

    fun beltItemCount(): Int = beltItemIds().size

    fun isBeltItem(itemId: Int): Boolean = ItemActions.slotsWithAction(itemId, ADD_ACTION).isNotEmpty()

    fun baseTierIds(): Set<Int> = Skilling.TOOLBELT_BASE.values.toSet()

    val CAPACITY: Int get() = maxOf(beltItemCount(), 1)

    const val ADDED_MESSAGE = "You add your %s to your tool belt."

    const val ALREADY_MESSAGE = "You already have that on your tool belt."

    const val NOT_BELT_MESSAGE = "You can't add that to your tool belt."

    const val NO_BELT_MESSAGE = "Your tool belt is unavailable right now."

    const val MESSAGE_TYPE = 0

    @Volatile
    var containerSupplier: (ContentPlayer) -> ItemContainer = { it.inventory }

    @Volatile
    var beltReader: (ContentPlayer) -> Set<Int>? = { null }

    @Volatile
    var beltWriter: (ContentPlayer, Int) -> Boolean = { _, _ -> false }

    @Volatile
    var messageSink: (ContentPlayer, Int, String) -> Unit = { _, _, _ -> }

    @Volatile
    var inventoryResend: (ContentPlayer) -> Boolean = { false }

    fun resetSeams() {
        containerSupplier = { it.inventory }
        beltReader = { null }
        beltWriter = { _, _ -> false }
        messageSink = { _, _, _ -> }
        inventoryResend = { false }
    }

    fun storedIdsFor(player: ContentPlayer): Set<Int> = beltReader(player) ?: emptySet()

    fun hasBelt(player: ContentPlayer): Boolean = beltReader(player) != null

    fun idsFor(player: ContentPlayer): Set<Int> = storedIdsFor(player) + baseTierIds()

    fun holds(player: ContentPlayer, itemId: Int): Boolean = itemId in idsFor(player)

    fun toolIdsFor(player: ContentPlayer, kind: ResourceNodes.Kind): Set<Int> {
        val suffix = when (kind) {
            ResourceNodes.Kind.WOODCUTTING -> Skilling.HATCHET_SUFFIX
            ResourceNodes.Kind.MINING -> Skilling.PICKAXE_SUFFIX
            ResourceNodes.Kind.GATHERING -> return emptySet()
        }
        return idsFor(player).filter { id ->
            (Skilling.itemNameOf(id) ?: ItemActions.nameOf(id))?.endsWith(suffix, ignoreCase = true) == true
        }.toSet()
    }

    enum class Outcome {
        NOT_MINE,

        NOT_BELT_ITEM,

        NO_BELT,

        GONE,

        ALREADY,

        FULL,

        ADDED,
    }

    data class Result(val outcome: Outcome, val itemId: Int, val beltSize: Int = 0)

    @Volatile
    private var added: Int = 0

    fun addedCount(): Int = added
    internal fun resetCounters() { added = 0 }

    fun add(player: ContentPlayer, itemId: Int, itemName: String?, slot: Int, action: String): Result {
        if (!enabled) return Result(Outcome.NOT_MINE, itemId)
        if (!action.equals(ADD_ACTION, ignoreCase = true)) return Result(Outcome.NOT_MINE, itemId)
        val name = itemName ?: ItemActions.nameOf(itemId) ?: "item $itemId"

        if (!isBeltItem(itemId)) {
            messageSink(player, MESSAGE_TYPE, NOT_BELT_MESSAGE)
            logger.info { "toolbelt ${player.name}: $name has no '$ADD_ACTION' option; rejected" }
            return Result(Outcome.NOT_BELT_ITEM, itemId)
        }
        val stored = beltReader(player)
        if (stored == null) {
            messageSink(player, MESSAGE_TYPE, NO_BELT_MESSAGE)
            logger.warn { "toolbelt ${player.name}: no tool belt storage available; $name not added" }
            return Result(Outcome.NO_BELT, itemId)
        }
        if (itemId in stored || itemId in baseTierIds()) {
            messageSink(player, MESSAGE_TYPE, ALREADY_MESSAGE)
            logger.info { "toolbelt ${player.name}: belt already holds $name" }
            return Result(Outcome.ALREADY, itemId, stored.size)
        }
        if (stored.size >= CAPACITY) {
            messageSink(player, MESSAGE_TYPE, NO_BELT_MESSAGE)
            logger.warn { "toolbelt ${player.name}: belt is full (${stored.size}/$CAPACITY)" }
            return Result(Outcome.FULL, itemId, stored.size)
        }

        val container = containerSupplier(player)
        if (slot < 0 || slot >= container.size) {
            logger.info { "toolbelt ${player.name}: slot $slot is outside the backpack; rejected" }
            return Result(Outcome.GONE, itemId, stored.size)
        }
        val held = container[slot]
        if (held == null || held.id != itemId) {
            logger.info {
                "toolbelt ${player.name}: slot $slot holds ${held?.id ?: "nothing"}, not $itemId; rejected"
            }
            return Result(Outcome.GONE, itemId, stored.size)
        }

        val left = held.amount - 1
        if (left > 0) container[slot] = Item(held.id, left) else container.removeSlot(slot)

        if (!beltWriter(player, itemId)) {
            if (left > 0) container[slot] = Item(held.id, held.amount) else container[slot] = held
            messageSink(player, MESSAGE_TYPE, NO_BELT_MESSAGE)
            logger.error { "toolbelt ${player.name}: failed to add $name; returned to slot $slot" }
            return Result(Outcome.NO_BELT, itemId, stored.size)
        }

        inventoryResend(player)
        messageSink(player, MESSAGE_TYPE, ADDED_MESSAGE.format(name.lowercase()))
        added++
        val size = beltReader(player)?.size ?: (stored.size + 1)
        logger.info { "toolbelt ${player.name}: added $name from slot $slot; $size stored, $left left in slot" }
        return Result(Outcome.ADDED, itemId, size)
    }
}

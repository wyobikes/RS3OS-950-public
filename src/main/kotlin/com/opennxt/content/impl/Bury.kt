package com.opennxt.content.impl

import com.opennxt.api.stat.Stat
import com.opennxt.content.ContentPlayer
import com.opennxt.model.items.Item
import com.opennxt.model.items.ItemContainer
import mu.KotlinLogging

object Bury {
    private val logger = KotlinLogging.logger { }

    val enabled: Boolean get() = System.getProperty("opennxt.experiment.bury") != "false"

    const val BURY_ACTION = "Bury"

    const val DIG_MESSAGE = "You dig a hole in the ground."

    const val BURY_MESSAGE = "You bury the bones."

    const val MESSAGE_TYPE = 109

    val BURY_ANIMATION: IntArray = intArrayOf(18008, 18008, 18008, 18008)

    const val BONES_ITEM = 526

    const val BONES_XP_TENTHS = 45

    val STAT: Stat = Stat.PRAYER

    const val XP_SKILL = "Prayer"
    const val XP_CATEGORY = "Bones and Ashes"

    data class Requirement(val xpTenths: Int, val source: String)

    val FIXED: Map<Int, Int> = mapOf(BONES_ITEM to BONES_XP_TENTHS)

    val DEFAULT_REQUIREMENT = Requirement(BONES_XP_TENTHS, "DEFAULT (the Bones rate)")

    private val requirementMemo = java.util.concurrent.ConcurrentHashMap<Int, Requirement>()

    internal fun clearRequirementMemo() = requirementMemo.clear()

    fun refTable(): Map<String, Int> {
        if (!SkillXpTable.enabled) return emptyMap()
        val rows = SkillXpTable.seed.skills[XP_SKILL]?.get(XP_CATEGORY) ?: return emptyMap()
        val out = LinkedHashMap<String, Int>()
        for (row in rows) if (row.name !in out) out[row.name] = row.xpTenths
        return out
    }

    fun requirementFor(itemId: Int, itemName: String?): Requirement = requirementMemo.computeIfAbsent(itemId) {
        FIXED[itemId]?.let { return@computeIfAbsent Requirement(it, "FIXED ($XP_SKILL)") }
        val name = itemName ?: ItemActions.nameOf(itemId)
        val refEntry = name?.let { refTable()[it] }
        if (refEntry != null) Requirement(refEntry, "TABLE ($XP_SKILL, $XP_CATEGORY)")
        else DEFAULT_REQUIREMENT
    }

    fun buryableItemIds(): List<Int> = ItemActions.idsWithAction(BURY_ACTION)

    fun buryableItemCount(): Int = buryableItemIds().size

    fun isBuryable(itemId: Int): Boolean =
        ItemActions.slotsWithAction(itemId, BURY_ACTION).isNotEmpty()

    @Volatile
    var containerSupplier: (ContentPlayer) -> ItemContainer = { it.inventory }

    @Volatile
    var xpSink: (ContentPlayer, Stat, Double) -> Unit = { _, _, _ -> }

    @Volatile
    var messageSink: (ContentPlayer, Int, String) -> Unit = { _, _, _ -> }

    @Volatile
    var animationSink: (ContentPlayer, IntArray) -> Unit = { _, _ -> }

    @Volatile
    var inventoryResend: (ContentPlayer) -> Boolean = { false }

    fun resetSeams() {
        containerSupplier = { it.inventory }
        xpSink = { _, _, _ -> }
        messageSink = { _, _, _ -> }
        animationSink = { _, _ -> }
        inventoryResend = { false }
        clearRequirementMemo()
    }

    enum class Outcome {
        NOT_MINE,

        NOT_BURYABLE,

        GONE,

        BURIED,
    }

    data class Result(
        val outcome: Outcome,
        val itemId: Int,
        val xpTenths: Int = 0,
        val source: String = "",
        val remaining: Int = 0,
    )

    @Volatile
    private var buried: Int = 0

    fun buriedCount(): Int = buried
    internal fun resetCounters() { buried = 0 }

    fun bury(player: ContentPlayer, itemId: Int, itemName: String?, slot: Int, action: String): Result {
        if (!enabled) return Result(Outcome.NOT_MINE, itemId)
        if (!action.equals(BURY_ACTION, ignoreCase = true)) return Result(Outcome.NOT_MINE, itemId)
        if (!isBuryable(itemId)) {
            logger.info { "bury ${player.name}: item $itemId declares no '$BURY_ACTION' row in this cache - refused." }
            return Result(Outcome.NOT_BURYABLE, itemId)
        }
        val container = containerSupplier(player)
        if (slot < 0 || slot >= container.size) {
            logger.info { "bury ${player.name}: slot $slot is outside the backpack - refused." }
            return Result(Outcome.GONE, itemId)
        }
        val held = container[slot]
        if (held == null || held.id != itemId) {
            logger.info {
                "bury ${player.name}: slot $slot holds ${held?.id ?: "nothing"}, the client said $itemId - refused."
            }
            return Result(Outcome.GONE, itemId)
        }
        val left = held.amount - 1
        if (left > 0) container[slot] = Item(held.id, left) else container.removeSlot(slot)

        val requirement = requirementFor(itemId, itemName)
        inventoryResend(player)
        animationSink(player, BURY_ANIMATION)
        messageSink(player, MESSAGE_TYPE, DIG_MESSAGE)
        messageSink(player, MESSAGE_TYPE, BURY_MESSAGE)
        xpSink(player, STAT, requirement.xpTenths / 10.0)
        buried++
        logger.info {
            "bury ${player.name}: ${itemName ?: "item $itemId"} (slot $slot) buried for " +
                "${requirement.xpTenths / 10.0} ${STAT.name} xp [${requirement.source}]; $left left in the slot."
        }
        return Result(Outcome.BURIED, itemId, requirement.xpTenths, requirement.source, left)
    }
}

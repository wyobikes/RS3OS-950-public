package com.opennxt.content.impl

import com.opennxt.model.entity.player.PlayerInventory
import com.opennxt.model.items.Item
import com.opennxt.model.items.ItemContainer
import com.opennxt.model.world.WorldPlayer
import com.opennxt.net.game.serverprot.InvEntry
import com.opennxt.net.game.serverprot.MessageGame
import com.opennxt.net.game.serverprot.RunClientScript
import com.opennxt.net.game.serverprot.UpdateInvFull
import com.opennxt.net.game.serverprot.UpdateInvPartial
import com.opennxt.resources.Names950
import mu.KotlinLogging

object MoneyPouch {
    private val logger = KotlinLogging.logger { }

    val INV = Names950.invId("money_pouch")
    val COINS = Names950.itemId("coins")
    const val ADD_ACTION = "Add to pouch"
    const val MESSAGE_TYPE = 109
    val SCRIPT_TOTAL = Names950.clientscriptId("money_pouch_update_amount")
    const val MAX = Int.MAX_VALUE

    val enabled: Boolean get() = System.getProperty("opennxt.content.moneyPouch") != "off"

    sealed class Outcome {
        data class Deposited(val moved: Int, val left: Int, val total: Int) : Outcome()
        data class Refused(val reason: String) : Outcome()
    }

    fun deposit(container: ItemContainer, slot: Int, pouch: Int): Outcome {
        if (slot !in 0 until container.size) return Outcome.Refused("slot $slot is outside 0..${container.size - 1}")
        if (pouch !in 0..MAX) return Outcome.Refused("pouch amount $pouch is not in 0..$MAX")
        val held = container[slot] ?: return Outcome.Refused("slot $slot is empty")
        if (held.id != COINS) return Outcome.Refused("slot $slot holds item ${held.id}, not coins")
        val room = MAX - pouch
        if (room == 0) return Outcome.Refused("the pouch is full")
        val moved = minOf(held.amount, room)
        val left = held.amount - moved
        container[slot] = if (left == 0) null else Item(COINS, left)
        return Outcome.Deposited(moved, left, pouch + moved)
    }

    fun pouchEntry(total: Int): InvEntry? = if (total > 0) InvEntry(COINS, total) else null

    fun sendLogin(player: WorldPlayer) {
        if (!enabled) return
        val total = player.coinPouch()
        player.client.write(UpdateInvFull(INV, listOf(pouchEntry(total))))
        player.client.write(RunClientScript(script = SCRIPT_TOTAL, args = arrayOf(total.toLong())))
    }

    fun sendTotal(player: WorldPlayer) {
        if (!enabled) return
        val total = player.coinPouch()
        player.client.write(UpdateInvPartial(INV, listOf(0 to pouchEntry(total))))
        player.client.write(RunClientScript(script = SCRIPT_TOTAL, args = arrayOf(total.toLong())))
    }

    val hook: (WorldPlayer, String, Int, String, Int) -> Boolean = { player, action, itemId, _, slot ->
        if (!enabled || itemId != COINS || !action.equals(ADD_ACTION, ignoreCase = true)) false
        else { addToPouch(player, slot); true }
    }

    fun addToPouch(player: WorldPlayer, slot: Int): Outcome {
        val container = PlayerInventory.backpackOf(player)
        val o = deposit(container, slot, player.coinPouch())
        when (o) {
            is Outcome.Deposited -> {
                player.setCoinPouch(o.total)
                PlayerInventory.sendBackpack(player)
                player.client.write(UpdateInvPartial(INV, listOf(0 to pouchEntry(o.total))))
                player.client.write(RunClientScript(script = SCRIPT_TOTAL, args = arrayOf(o.total.toLong())))
                player.client.write(MessageGame(MESSAGE_TYPE, "${o.moved} coins have been added to your money pouch."))
                logger.info { "moneyPouch: ${player.name} added ${o.moved} coins from backpack slot $slot (left ${o.left}); pouch = ${o.total}" }
            }
            is Outcome.Refused -> {
                if (o.reason == "the pouch is full") player.client.write(MessageGame(0, "Your money pouch is full."))
                logger.info { "moneyPouch: ${player.name} Add to pouch on slot $slot REFUSED - ${o.reason}" }
            }
        }
        return o
    }

    @Volatile
    var installed: Boolean = false
        private set

    fun install(): Boolean {
        if (!ItemOps.backpackActionHooks.contains(hook)) ItemOps.backpackActionHooks.add(hook)
        installed = true
        logger.info { "moneyPouch: '$ADD_ACTION' on coins ($COINS) hooked; the pouch is inv $INV" }
        return true
    }

    fun uninstall() {
        ItemOps.backpackActionHooks.remove(hook)
        installed = false
    }
}

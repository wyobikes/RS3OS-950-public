package com.opennxt.model.entity

import com.opennxt.api.stat.StatContainer
import com.opennxt.model.commands.CommandSender
import com.opennxt.model.entity.player.InterfaceManager
import com.opennxt.model.messages.Message
import com.opennxt.model.tick.Tickable
import com.opennxt.net.ConnectedClient
import com.opennxt.net.game.GamePacket
import mu.KotlinLogging

abstract class BasePlayer(var client: ConnectedClient, val name: String): CommandSender, Tickable {
    abstract val interfaces: InterfaceManager
    abstract val stats: StatContainer

    var noTimeouts = 0

    var windowMode = -1
    var windowWidth = -1
    var windowHeight = -1

    var chatMode: ChatMode = ChatMode.UNSET

    private val logger = KotlinLogging.logger { }

    override fun message(message: Message) {
        client.write(message.createPacket())
    }

    override fun message(message: String) {
        client.write(Message.ConsoleMessage(message).createPacket())
    }

    override fun console(message: String) {
        client.write(Message.ConsoleMessage(message).createPacket())
    }

    override fun error(message: String) {
        client.write(Message.ConsoleError(message).createPacket())
    }

    override fun hasPermissions(node: String): Boolean {
        val world = this as? com.opennxt.model.world.WorldPlayer ?: return true
        return world.hasPower(node)
    }

    override fun tick() {
    }

    fun write(message: GamePacket) {
        client.write(message)
    }
}

data class ChatMode(val mode: Int, val arg: Int) {
    val name: String? get() = nameOf(mode)

    override fun toString(): String = "ChatMode($mode${name?.let { "/$it" } ?: ""}, arg=$arg)"

    companion object {
        const val CLAN_AFFINED = 2

        private val NAMES = mapOf(CLAN_AFFINED to "CLAN_AFFINED")

        fun nameOf(mode: Int): String? = NAMES[mode]

        val UNSET = ChatMode(-1, 0)
    }
}

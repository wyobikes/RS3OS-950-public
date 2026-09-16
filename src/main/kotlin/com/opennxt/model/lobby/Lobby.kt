package com.opennxt.model.lobby

import com.opennxt.OpenNXT
import com.opennxt.model.account.AccountStore
import com.opennxt.model.tick.Tickable
import mu.KotlinLogging
import java.util.concurrent.ConcurrentLinkedQueue

class Lobby: Tickable {
    private val logger = KotlinLogging.logger {  }

    private val players = HashSet<LobbyPlayer>()
    private val toAdd = ConcurrentLinkedQueue<LobbyPlayer>()

    private var storeOverride: AccountStore? = null

    val accountStore: AccountStore get() = storeOverride ?: AccountStore.instance

    fun useAccountStore(store: AccountStore) {
        storeOverride = store
    }

    private var deferredToWorld = 0

    fun savesDeferredToWorld(): Int = deferredToWorld

    private inline fun perPlayer(player: LobbyPlayer, phase: String, body: () -> Unit) {
        try {
            body()
        } catch (t: Throwable) {
            val seen = ++perPlayerFailures
            if (seen <= 3 || seen % 500 == 0) {
                logger.error(t) {
                    "Error in $phase for lobby player '${player.name}' (occurrence $seen); continuing tick"
                }
            }
        }
    }

    private var perPlayerFailures = 0

    fun containedPlayerFailures(): Int = perPlayerFailures

    override fun tick() {
        while (true) {
            val player = toAdd.poll() ?: break
            players += player
            perPlayer(player, "added") { player.added() }
        }

        players.forEach { perPlayer(it, "handleIncomingPackets") { it.handleIncomingPackets() } }
        players.forEach { perPlayer(it, "tick") { it.tick() } }
        players.forEach { perPlayer(it, "flush") { it.client.flush() } }

        val iterator = players.iterator()
        while (iterator.hasNext()) {
            val player = iterator.next()
            if (player.client.channel.isActive) continue
            iterator.remove()

            val world = runCatching { OpenNXT.world }.getOrNull()
            if ((world != null && world.isOnline(player.name)) || player.worldSessionOwnsSave()) {
                deferredToWorld++
                logger.info {
                    "Lobby player '${player.name}' disconnected; save skipped, the world session owns it"
                }
                continue
            }

            try {
                accountStore.storeSave(player.name, player.toSave())
                logger.info { "Lobby player '${player.name}' disconnected; stored save on leave" }
            } catch (e: Exception) {
                logger.error(e) { "Failed to store save for lobby player '${player.name}' at cull" }
            }
        }
    }

    fun addPlayer(player: LobbyPlayer) {
        toAdd += player
    }

    fun playerCount(): Int = players.size + toAdd.size
}

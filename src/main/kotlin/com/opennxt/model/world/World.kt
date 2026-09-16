package com.opennxt.model.world

import com.opennxt.model.account.AccountStore
import com.opennxt.model.combat.PlayerCombat
import com.opennxt.model.entity.rendering.PlayerUpdates
import com.opennxt.model.entity.EntityList
import com.opennxt.model.entity.PlayerEntity
import com.opennxt.model.lobby.LobbyPlayer
import com.opennxt.model.tick.Tickable
import mu.KotlinLogging
import java.util.concurrent.ConcurrentLinkedQueue

class World : Tickable {
    private val logger = KotlinLogging.logger { }

    companion object {
        const val AUTOSAVE_INTERVAL_TICKS = 100

        const val TICK_INTERVAL_MS = 600

        const val AUTOSAVE_PROVENANCE =
            "autosave every $AUTOSAVE_INTERVAL_TICKS ticks (${TICK_INTERVAL_MS}ms per tick)"
    }

    private val playerEntities = EntityList<PlayerEntity>(2000)
    private val players = HashSet<WorldPlayer>()

    val npcs = WorldNpcs()

    val groundItems = GroundItems()

    private val toAdd = ConcurrentLinkedQueue<WorldPlayer>()

    private object Reserved

    private val sessions = java.util.concurrent.ConcurrentHashMap<String, Any>()

    fun sessionKey(name: String): String = name.lowercase()

    fun isOnline(name: String): Boolean = sessions.containsKey(sessionKey(name))

    fun onlineAccounts(): Int = sessions.size

    fun reserveSession(name: String): Boolean {
        val key = sessionKey(name)
        if (sessions.putIfAbsent(key, Reserved) != null) return false
        sessionGenerations.merge(key, 1L, Long::plus)
        return true
    }

    fun sessionGeneration(name: String): Long = sessionGenerations[sessionKey(name)] ?: 0L

    private val sessionGenerations = java.util.concurrent.ConcurrentHashMap<String, Long>()

    fun releaseSession(name: String): Boolean = sessions.remove(sessionKey(name)) != null

    private var tickCount = 0L

    val currentTick: Long get() = tickCount

    private var lastAutosaveTick = -1L

    fun ticks(): Long = tickCount

    fun lastAutosaveTick(): Long = lastAutosaveTick

    private var storeOverride: AccountStore? = null

    val accountStore: AccountStore get() = storeOverride ?: AccountStore.instance

    fun useAccountStore(store: AccountStore) {
        storeOverride = store
    }

    private inline fun perPlayer(player: WorldPlayer, phase: String, body: () -> Unit) {
        try {
            body()
        } catch (t: Throwable) {
            val seen = ++perPlayerFailures
            if (seen <= 3 || seen % 500 == 0) {
                logger.error(t) {
                    "Error in $phase for player '${player.name}' (occurrence $seen)"
                }
            }
        }
    }

    private var perPlayerFailures = 0

    fun containedPlayerFailures(): Int = perPlayerFailures

    private var phaseFailures = 0
    fun containedPhaseFailures(): Int = phaseFailures

    private var combatFailures = 0
    fun containedCombatFailures(): Int = combatFailures

    override fun tick() {
        try {
            SpawnActivation.tick(npcs, players.map { it.entity.location })
        } catch (t: Throwable) {
            phaseFailures++
            logger.error(t) { "Error in the spawn activation phase" }
        }
        try {
            npcs.tick()
        } catch (t: Throwable) {
            phaseFailures++
            logger.error(t) { "Error in the npc phase" }
        }
        try {
            groundItems.tick()
        } catch (t: Throwable) {
            phaseFailures++
            logger.error(t) { "Error in the ground-item phase" }
        }

        try {
            com.opennxt.content.impl.Skilling.tick()
        } catch (t: Throwable) {
            logger.error(t) {
                "Error in the skilling respawn phase"
            }
        }

        try {
            com.opennxt.content.impl.Fishing.tick()
        } catch (t: Throwable) {
            logger.error(t) { "Error in the fishing phase" }
        }
        try {
            com.opennxt.content.impl.Thieving.tick()
        } catch (t: Throwable) {
            logger.error(t) { "Error in the thieving phase" }
        }
        try {
            com.opennxt.content.impl.Firemaking.tick()
        } catch (t: Throwable) {
            logger.error(t) { "Error in the firemaking phase" }
        }
        try {
            com.opennxt.content.impl.Cooking.tick()
        } catch (t: Throwable) {
            logger.error(t) { "Error in the cooking phase" }
        }
        try {
            com.opennxt.content.impl.Smithing.tick()
        } catch (t: Throwable) {
            logger.error(t) { "Error in the smithing phase" }
        }
        try {
            com.opennxt.content.impl.Fletching.tick()
        } catch (t: Throwable) {
            logger.error(t) { "Error in the fletching phase" }
        }
        try {
            com.opennxt.content.skills.ProductionActions.tick()
        } catch (t: Throwable) {
            logger.error(t) { "Error in the production phase" }
        }
        try {
            com.opennxt.content.skills.SkillInteractions.tick()
        } catch (t: Throwable) {
            logger.error(t) { "Error in the skill interactions phase" }
        }
        try {
            com.opennxt.content.impl.Lodestones.tick()
        } catch (t: Throwable) {
            logger.error(t) { "Error in the lodestone phase" }
        }

        drainPending()

        players.forEach { perPlayer(it, "handleIncomingPackets") { it.handleIncomingPackets() } }

        try {
            PlayerCombat.tick(npcs, groundItems, players.toList())
        } catch (t: Throwable) {
            combatFailures++
            logger.error(t) {
                "Error in the combat phase"
            }
        }

        try {
            com.opennxt.content.ability.AbilityActivation.tick(npcs, groundItems, players.toList())
        } catch (t: Throwable) {
            combatFailures++
            logger.error(t) { "Error in the ability phase" }
        }

        try {
            com.opennxt.model.combat.BossEncounters.tick(players.toList())
        } catch (t: Throwable) {
            combatFailures++
            logger.error(t) { "Error in the boss phase" }
        }

        try {
            com.opennxt.content.impl.PrayerBook.tick(players.toList())
        } catch (t: Throwable) {
            combatFailures++
            logger.error(t) { "Error in the prayer phase" }
        }

        players.forEach { perPlayer(it, "tick") { it.tick() } }

        players.forEach { PlayerUpdates.clear(it.entity) }

        players.forEach { perPlayer(it, "flush") { it.client.flush() } }

        cullDisconnected()

        tickCount++
        if (tickCount % AUTOSAVE_INTERVAL_TICKS == 0L) {
            lastAutosaveTick = tickCount
            autosave()
        }
    }

    fun drainPending(): Int {
        var added = 0
        while (true) {
            val player = toAdd.poll() ?: break

            if (!playerEntities.add(player.entity)) {
                logger.error {
                    "World is full (${playerEntities.size()}/${playerEntities.capacity}); rejected '${player.name}'"
                }
                sessions.remove(sessionKey(player.name), player)
                runCatching { player.client.channel.close() }
                continue
            }

            players += player

            perPlayer(player, "added") { player.added() }
            added++
        }
        return added
    }

    fun cullDisconnected(): List<String> {
        val culled = ArrayList<String>()
        val iterator = players.iterator()
        while (iterator.hasNext()) {
            val player = iterator.next()
            if (player.client.channel.isActive) continue
            iterator.remove()
            playerEntities.remove(player.entity)
            runCatching { com.opennxt.content.impl.Skilling.stopFor(player.contentPlayer, "logout (culled)") }
                .onFailure { logger.warn(it) { "could not stop ${player.name}'s skilling action at cull" } }
            runCatching { com.opennxt.content.impl.Fishing.stopFor(player.contentPlayer, "logout (culled)") }
                .onFailure { logger.warn(it) { "could not stop ${player.name}'s fishing action at cull" } }
            runCatching { com.opennxt.content.impl.Thieving.cull(player.contentPlayer, "logout (culled)") }
                .onFailure { logger.warn(it) { "could not stop ${player.name}'s pickpocket at cull" } }
            runCatching { com.opennxt.content.impl.Firemaking.cancelFor(player.contentPlayer, "logout (culled)") }
                .onFailure { logger.warn(it) { "could not cancel ${player.name}'s pending fire at cull" } }
            runCatching { com.opennxt.content.impl.Cooking.stopFor(player.contentPlayer, "logout (culled)") }
                .onFailure { logger.warn(it) { "could not stop ${player.name}'s cooking action at cull" } }
            runCatching { com.opennxt.content.impl.Smithing.cull(player.contentPlayer, "logout (culled)") }
                .onFailure { logger.warn(it) { "could not stop ${player.name}'s smithing project at cull" } }
            runCatching { com.opennxt.content.impl.Fletching.stopFor(player.contentPlayer, "logout (culled)") }
                .onFailure { logger.warn(it) { "could not stop ${player.name}'s fletching action at cull" } }
            runCatching { com.opennxt.content.ability.AbilityActivation.cull(player, "logout (culled)") }
                .onFailure { logger.warn(it) { "could not drop ${player.name}'s ability state at cull" } }
            runCatching { com.opennxt.content.impl.PrayerBook.cull(player) }
                .onFailure { logger.warn(it) { "could not drop ${player.name}'s prayer state at cull" } }
            runCatching { com.opennxt.content.skills.ProductionActions.cull(player.contentPlayer, "logout (culled)") }
                .onFailure { logger.warn(it) { "could not stop ${player.name}'s production action at cull" } }
            runCatching { com.opennxt.content.skills.SkillInteractions.cull(player.contentPlayer, "logout (culled)") }
                .onFailure { logger.warn(it) { "could not stop ${player.name}'s skill interaction at cull" } }
            runCatching { com.opennxt.content.ActionSlot.clearFor(player.contentPlayer) }
                .onFailure { logger.warn(it) { "could not release ${player.name}'s action slot at cull" } }
            culled += player.name
            try {
                cullStore(player)
            } finally {
                sessions.remove(sessionKey(player.name), player)
            }
        }
        return culled
    }

    private fun cullStore(player: WorldPlayer) {
            val location = player.entity.location
            val blob = try {
                player.toSave()
            } catch (e: Exception) {
                logger.error(e) {
                    "Could not build the save for '${player.name}' on logout; progress lost"
                }
                return
            }
            try {
                accountStore.storeSave(player.name, blob)
                logger.info {
                    "Player '${player.name}' disconnected and saved at (${location.x}, ${location.y}, ${location.plane})"
                }
            } catch (e: Exception) {
                logger.error(e) { "Failed to store save for '${player.name}' on logout" }
                try {
                    val dir = com.opennxt.Constants.DATA_PATH.resolve("diag").resolve("unsaved")
                    java.nio.file.Files.createDirectories(dir)
                    val f = dir.resolve("${player.name}-${System.currentTimeMillis()}.json")
                    java.nio.file.Files.writeString(f, blob.toJson())
                    logger.error {
                        "Wrote unsaved data for '${player.name}' to $f; restore it manually"
                    }
                } catch (t: Throwable) {
                    logger.error(t) {
                        "Could not write unsaved data for '${player.name}'; progress lost"
                    }
                }
            }
    }

    fun autosave(): Int {
        if (players.isEmpty()) return 0
        var stored = 0
        players.forEach { player ->
            try {
                accountStore.storeSave(player.name, player.toSave())
                stored++
            } catch (e: Exception) {
                logger.error(e) { "Autosave failed for '${player.name}'" }
            }
        }
        logger.info { "Autosave at tick $tickCount: saved $stored of ${players.size} player(s)" }
        return stored
    }

    fun getPlayer(index: Int): PlayerEntity? = playerEntities[index]

    fun playerCount(): Int = players.size + toAdd.size

    fun forEachPlayer(action: (WorldPlayer) -> Unit) {
        players.toList().forEach(action)
    }

    fun addPlayer(player: WorldPlayer): Boolean {
        val key = sessionKey(player.name)
        val previous = sessions.putIfAbsent(key, player)
        if (previous != null && previous !== Reserved && previous !== player) {
            logger.error {
                "Rejected second session for '${player.name}': account is already online"
            }
            return false
        }
        sessions[key] = player
        toAdd += player
        return true
    }
}

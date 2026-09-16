package com.opennxt.content

import mu.KotlinLogging
import java.util.Collections
import java.util.WeakHashMap

object ActionSlot {
    private val logger = KotlinLogging.logger { }

    interface Owner {
        val actionName: String

        fun cancelSlot(player: ContentPlayer, why: String)
    }

    val enabled: Boolean = System.getProperty("opennxt.content.actionslot") != "off"

    private val holder: MutableMap<ContentPlayer, Owner> =
        Collections.synchronizedMap(WeakHashMap<ContentPlayer, Owner>())

    private val lock = Any()

    private var displacements = 0
    private var claims = 0
    private var releases = 0
    private var cancelFailures = 0

    fun displacements(): Int = synchronized(lock) { displacements }
    fun claims(): Int = synchronized(lock) { claims }
    fun releases(): Int = synchronized(lock) { releases }

    fun cancelFailures(): Int = synchronized(lock) { cancelFailures }

    fun holderOf(player: ContentPlayer): Owner? = synchronized(lock) { holder[player] }

    fun heldCount(): Int = synchronized(lock) { holder.size }

    fun claim(player: ContentPlayer, owner: Owner): Owner? {
        if (!enabled) return null
        val previous = synchronized(lock) {
            claims++
            val prev = holder[player]
            holder[player] = owner
            if (prev != null && prev !== owner) displacements++
            prev
        }
        if (previous == null || previous === owner) return null
        runCatching { previous.cancelSlot(player, "started ${owner.actionName}") }.onFailure { t ->
            synchronized(lock) { cancelFailures++ }
            logger.error(t) {
                "action slot: ${previous.actionName}'s cancel threw while ${owner.actionName} took " +
                    "${player.name}'s slot - CONTAINED, ${owner.actionName} still starts"
            }
        }
        logger.info {
            "action slot: ${player.name} stopped ${previous.actionName} and started ${owner.actionName}"
        }
        return previous
    }

    fun release(player: ContentPlayer, owner: Owner): Boolean = synchronized(lock) {
        if (!enabled) return false
        if (holder[player] !== owner) return false
        holder.remove(player)
        releases++
        true
    }

    fun clearFor(player: ContentPlayer): Owner? = synchronized(lock) { holder.remove(player) }

    internal fun clear() = synchronized(lock) {
        holder.clear()
        displacements = 0; claims = 0; releases = 0; cancelFailures = 0
    }
}

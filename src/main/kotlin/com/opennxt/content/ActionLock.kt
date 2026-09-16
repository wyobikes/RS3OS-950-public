package com.opennxt.content

import com.opennxt.model.entity.movement.Movement
import mu.KotlinLogging
import java.util.concurrent.CopyOnWriteArrayList

object ActionLock {
    private val logger = KotlinLogging.logger { }

    fun interface Source {
        fun lockReason(player: ContentPlayer): String?
        val stun: Boolean get() = false
    }

    val enabled: Boolean get() = System.getProperty("opennxt.content.actionlock") != "off"

    private val sources = CopyOnWriteArrayList<Source>()

    private val lock = Any()
    private var refusals = 0

    fun register(source: Source) {
        synchronized(lock) { if (sources.none { it === source }) sources += source }
    }

    fun reasonFor(player: ContentPlayer, ignoreStuns: Boolean = false): String? {
        if (!enabled) return null
        for (s in sources) {
            if (ignoreStuns && s.stun) continue
            s.lockReason(player)?.let { return it }
        }
        return null
    }

    fun isLocked(player: ContentPlayer): Boolean = reasonFor(player) != null

    fun refuse(player: ContentPlayer, what: String): Boolean {
        val reason = reasonFor(player) ?: return false
        synchronized(lock) { refusals++ }
        logger.info { "action lock: ${player.name}'s $what REFUSED - $reason" }
        return true
    }

    fun refusals(): Int = synchronized(lock) { refusals }

    fun holdMovement(player: ContentPlayer, movement: Movement) {
        movement.hold = { reasonFor(player) }
    }
}

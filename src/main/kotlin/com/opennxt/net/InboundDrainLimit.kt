package com.opennxt.net

import mu.KotlinLogging

object InboundDrainLimit {
    private val logger = KotlinLogging.logger { }

    val MAX_PER_TICK: Int = run {
        val raw = System.getProperty("opennxt.net.inboundPerTick")?.toIntOrNull()
        if (raw != null && raw >= 1) raw else 128
    }

    val QUEUE_HIGH_WATER: Int = run {
        val raw = System.getProperty("opennxt.net.inboundQueueHigh")?.toIntOrNull()
        if (raw != null && raw > MAX_PER_TICK * 2) raw else MAX_PER_TICK * 16
    }

    val QUEUE_LOW_WATER: Int = MAX_PER_TICK * 2

    val BYTES_LOW_WATER: Long = 256L * 1024

    val BYTES_HIGH_WATER: Long = run {
        val raw = System.getProperty("opennxt.net.inboundBytesHigh")?.toLongOrNull()
        if (raw != null && raw > BYTES_LOW_WATER) raw else 1L shl 20
    }

    private val warned = java.util.Collections.synchronizedSet(HashSet<String>())

    fun reportBacklog(name: String, handled: Int, remaining: Int) {
        if (!warned.add(name)) return
        logger.warn {
            "inbound cap: '$name' exceeded $MAX_PER_TICK packets in one tick (handled $handled, " +
                "$remaining deferred); -Dopennxt.net.inboundPerTick=<n> raises the cap (logged once)"
        }
    }

    fun forget(name: String) = warned.remove(name)
}

package com.opennxt.net

import io.netty.channel.WriteBufferWaterMark

object OutboundBackpressure {
    val LOW_WATER_BYTES: Int = run {
        val raw = System.getProperty("opennxt.net.outboundLow")?.toIntOrNull()
        if (raw != null && raw >= 1) raw else 512 * 1024
    }

    val HIGH_WATER_BYTES: Int = run {
        val raw = System.getProperty("opennxt.net.outboundHigh")?.toIntOrNull()
        if (raw != null && raw > LOW_WATER_BYTES) raw else maxOf(2 * 1024 * 1024, LOW_WATER_BYTES + 1)
    }

    val MAX_UNWRITABLE_TICKS: Int = run {
        val raw = System.getProperty("opennxt.net.outboundUnwritableTicks")?.toIntOrNull()
        if (raw != null && raw >= 1) raw else 50
    }

    val HARD_CAP_BYTES: Long = run {
        val raw = System.getProperty("opennxt.net.outboundHardCap")?.toLongOrNull()
        if (raw != null && raw >= 1) raw else 16L * 1024 * 1024
    }

    fun waterMark(): WriteBufferWaterMark = WriteBufferWaterMark(LOW_WATER_BYTES, HIGH_WATER_BYTES)

    @Volatile
    var closed: Int = 0
        internal set
}

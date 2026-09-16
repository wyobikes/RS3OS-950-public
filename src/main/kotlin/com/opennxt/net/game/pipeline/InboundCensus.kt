package com.opennxt.net.game.pipeline

import com.opennxt.net.Side
import com.opennxt.net.game.PacketRegistry
import io.netty.buffer.ByteBuf
import io.netty.channel.Channel
import io.netty.util.AttributeKey
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap
import mu.KotlinLogging
import java.util.concurrent.ConcurrentHashMap

object InboundCensus {
    const val SYSTEM_PROPERTY = "opennxt.experiment.net.census"

    const val SUMMARY_PROPERTY = "opennxt.experiment.net.census.summary"

    const val HEX_LIMIT = 64

    val LINE_BUDGET: Int =
        System.getProperty("opennxt.experiment.net.census.maxLines")?.toIntOrNull() ?: 50_000

    private val logger = KotlinLogging.logger { }

    private val ATTR: AttributeKey<Session> = AttributeKey.valueOf("opennxt-inbound-census")

    @Volatile
    private var frameLines = readFlag(SYSTEM_PROPERTY, default = false)

    @Volatile
    private var summaryLine = readFlag(SUMMARY_PROPERTY, default = true)

    private fun readFlag(property: String, default: Boolean): Boolean {
        val raw = System.getProperty(property) ?: return default
        if (raw.isEmpty()) return true
        return raw.equals("true", true) || raw == "1" || raw.equals("yes", true)
    }

    val enabled: Boolean
        get() = frameLines

    val summaryEnabled: Boolean
        get() = summaryLine

    fun refreshFromSystemProperties(): Boolean {
        frameLines = readFlag(SYSTEM_PROPERTY, default = false)
        summaryLine = readFlag(SUMMARY_PROPERTY, default = true)
        return frameLines
    }

    class Session {
        val counts = Int2IntOpenHashMap()
        val unframeable = Int2IntOpenHashMap()
        var frames = 0L
        var payloadBytes = 0L
        var lines = 0
        var truncated = false
        var fault: String? = null
        val startNanos = System.nanoTime()
    }

    private fun session(channel: Channel): Session {
        val attr = channel.attr(ATTR)
        val existing = attr.get()
        if (existing != null) return existing
        val fresh = Session()
        return attr.setIfAbsent(fresh) ?: fresh
    }

    fun begin(channel: Channel) {
        try {
            if (!summaryLine && !frameLines) return
            session(channel)
        } catch (t: Throwable) {
            reportSelfFailure(t)
        }
    }

    fun frame(channel: Channel, side: Side?, opcode: Int, payload: ByteBuf) {
        try {
            if (!summaryLine && !frameLines) return
            val s = session(channel)
            val len = payload.readableBytes()
            s.frames++
            s.payloadBytes += len.toLong()
            s.counts.addTo(opcode, 1)

            if (!frameLines) return
            if (s.lines >= LINE_BUDGET) {
                if (!s.truncated) {
                    s.truncated = true
                    logger.warn {
                        "[census] line limit $LINE_BUDGET reached on ${channel.remoteAddress()}; " +
                            "per-frame logging stopped (-Dopennxt.experiment.net.census.maxLines=N)"
                    }
                }
                return
            }
            s.lines++
            val name = PacketRegistry.describeOpcode(side ?: Side.CLIENT, opcode)
            logger.info {
                "[census] RECV op=$opcode name=$name len=$len side=$side conn=${channel.remoteAddress()} " +
                    "hex=${hex(payload)}"
            }
        } catch (t: Throwable) {
            reportSelfFailure(t)
        }
    }

    fun unframeable(channel: Channel, side: Side?, opcode: Int) {
        try {
            if (!summaryLine && !frameLines) return
            val s = session(channel)
            s.unframeable.addTo(opcode, 1)
            if (s.fault == null) s.fault = "no size-table entry for opcode $opcode (side=$side)"
        } catch (t: Throwable) {
            reportSelfFailure(t)
        }
    }

    fun framingFault(channel: Channel, reason: String) {
        try {
            if (!summaryLine && !frameLines) return
            val s = session(channel)
            if (s.fault == null) s.fault = reason
        } catch (t: Throwable) {
            reportSelfFailure(t)
        }
    }

    fun summarise(channel: Channel, side: Side?) {
        try {
            val s = channel.attr(ATTR).getAndSet(null) ?: return
            if (!summaryLine) return
            val durationMs = (System.nanoTime() - s.startNanos) / 1_000_000L
            val histogram = renderHistogram(s.counts, side)
            val extra = StringBuilder()
            if (!s.unframeable.isEmpty()) {
                extra.append(" unframeable=").append(renderHistogram(s.unframeable, side))
            }
            s.fault?.let { extra.append(" fault='").append(it).append('\'') }
            if (s.truncated) extra.append(" perFrameLinesTruncatedAt=").append(LINE_BUDGET)
            val silence =
                if (s.frames == 0L) " (no frames received)"
                else ""
            logger.info {
                "[census] INBOUND SUMMARY conn=${channel.remoteAddress()} side=$side frames=${s.frames} " +
                    "payloadBytes=${s.payloadBytes} durationMs=$durationMs " +
                    "distinctOpcodes=${s.counts.size} opcodes=$histogram$extra$silence"
            }
        } catch (t: Throwable) {
            reportSelfFailure(t)
        }
    }

    private fun renderHistogram(counts: Int2IntOpenHashMap, side: Side?): String {
        if (counts.isEmpty()) return "{}"
        val entries = counts.keys.toIntArray().toTypedArray()
        entries.sortWith(compareByDescending<Int> { counts.get(it) }.thenBy { it })
        val sb = StringBuilder("{")
        for ((i, op) in entries.withIndex()) {
            if (i > 0) sb.append(", ")
            sb.append(op).append('(').append(PacketRegistry.describeOpcode(side ?: Side.CLIENT, op))
                .append(")x").append(counts.get(op))
        }
        return sb.append('}').toString()
    }

    private val noHandlerSeen = ConcurrentHashMap<String, Int>()

    fun noHandler(packet: Any): Int {
        val name = packet::class.java.simpleName ?: packet::class.java.name
        val seen = noHandlerSeen.merge(name, 1, Int::plus) ?: 1
        if (seen <= 3 || seen % 500 == 0) {
            logger.warn {
                "[census] no handler for $name (occurrence $seen): $packet"
            }
        }
        return seen
    }

    fun resetNoHandlerCounts() = noHandlerSeen.clear()

    private val HEX = "0123456789abcdef".toCharArray()

    fun hex(buf: ByteBuf): String {
        val n = buf.readableBytes()
        val shown = if (n < HEX_LIMIT) n else HEX_LIMIT
        val base = buf.readerIndex()
        val sb = StringBuilder(shown * 3 + 24)
        for (i in 0 until shown) {
            if (i > 0) sb.append(' ')
            val v = buf.getByte(base + i).toInt() and 0xff
            sb.append(HEX[v ushr 4]).append(HEX[v and 0xf])
        }
        if (n > shown) sb.append(" ...(").append(n - shown).append(" more)")
        return sb.toString()
    }

    @Volatile
    private var selfFailureReported = false

    private fun reportSelfFailure(t: Throwable) {
        if (selfFailureReported) return
        selfFailureReported = true
        logger.error(t) { "[census] the inbound census itself threw; census output is unreliable for this run" }
    }
}

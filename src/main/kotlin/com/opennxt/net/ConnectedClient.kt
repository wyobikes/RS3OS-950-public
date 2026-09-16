package com.opennxt.net

import com.opennxt.model.proxy.PacketDumper
import com.opennxt.net.buf.GamePacketBuilder
import com.opennxt.net.buf.GamePacketReader
import com.opennxt.net.game.GamePacket
import com.opennxt.net.game.PacketRegistry
import com.opennxt.net.game.clientprot.UndecodedClientPacket
import com.opennxt.net.game.pipeline.GamePacketCodec
import com.opennxt.net.game.pipeline.OpcodeWithBuffer
import com.opennxt.net.proxy.ProxyChannelAttributes
import com.opennxt.OpenNXT
import com.opennxt.config.ServerConfig
import com.opennxt.net.game.serverprot.variables.VarpLarge
import com.opennxt.net.game.serverprot.variables.VarpSmall
import com.opennxt.net.proxy.UnidentifiedPacket
import io.netty.buffer.Unpooled
import io.netty.channel.Channel
import mu.KotlinLogging
import java.util.concurrent.ConcurrentLinkedQueue

class ConnectedClient(
    val side: Side,
    val channel: Channel,
    var processUnidentifiedPackets: Boolean = false,
    var dumper: PacketDumper? = null
) {
    val logger = KotlinLogging.logger { }

    val incomingQueue = ConcurrentLinkedQueue<GamePacket>()

    private val pending = java.util.concurrent.atomic.AtomicInteger()

    @Volatile var readsPaused: Boolean = false
        private set

    @Volatile var pauseCount: Int = 0
        private set

    fun pendingCount(): Int = pending.get()

    private val pendingBytes = java.util.concurrent.atomic.AtomicLong()
    private val pendingSizes = ConcurrentLinkedQueue<Int>()

    fun pendingByteCount(): Long = pendingBytes.get()

    fun enqueue(packet: GamePacket, bytes: Int = 0) {
        val size = bytes.coerceAtLeast(0)
        pendingSizes.add(size)
        incomingQueue.add(packet)
        val n = pending.incrementAndGet()
        val b = pendingBytes.addAndGet(size.toLong())
        if (!readsPaused && (n >= InboundDrainLimit.QUEUE_HIGH_WATER || b >= InboundDrainLimit.BYTES_HIGH_WATER)) {
            requestBackpressureUpdate()
        }
    }

    fun pollIncoming(): GamePacket? {
        val packet = incomingQueue.poll() ?: return null
        val size = pendingSizes.poll() ?: 0
        val n = pending.decrementAndGet()
        val b = pendingBytes.addAndGet(-size.toLong())
        if (readsPaused && n <= InboundDrainLimit.QUEUE_LOW_WATER && b <= InboundDrainLimit.BYTES_LOW_WATER) {
            requestBackpressureUpdate()
        }
        return packet
    }

    private val backpressureScheduled = java.util.concurrent.atomic.AtomicBoolean(false)

    @Volatile
    var lastBackpressureThread: Thread? = null
        private set

    private fun requestBackpressureUpdate() {
        if (!channel.isRegistered) { applyBackpressure(); return }
        val loop = channel.eventLoop()
        if (loop.inEventLoop()) { applyBackpressure(); return }
        if (!backpressureScheduled.compareAndSet(false, true)) return
        try {
            loop.execute {
                backpressureScheduled.set(false)
                applyBackpressure()
            }
        } catch (e: java.util.concurrent.RejectedExecutionException) {
            backpressureScheduled.set(false)
        }
    }

    private fun applyBackpressure() {
        val n = pending.get()
        val b = pendingBytes.get()
        val pause = if (readsPaused) !(n <= InboundDrainLimit.QUEUE_LOW_WATER && b <= InboundDrainLimit.BYTES_LOW_WATER)
        else (n >= InboundDrainLimit.QUEUE_HIGH_WATER || b >= InboundDrainLimit.BYTES_HIGH_WATER)
        lastBackpressureThread = Thread.currentThread()
        if (pause == readsPaused) return
        readsPaused = pause
        channel.config().isAutoRead = !pause
        if (pause) {
            pauseCount++
            logger.warn {
                "Inbound backpressure: pausing reads from ${channel.remoteAddress()} side=$side " +
                    "($n packets / $b bytes queued); pause #$pauseCount"
            }
        } else {
            logger.info { "Inbound backpressure: resuming reads from ${channel.remoteAddress()} ($n packets / $b bytes queued)" }
        }
    }

    @Volatile
    var unwritableTicks: Int = 0
        private set

    @Volatile
    var closedForBackpressure: Boolean = false
        private set

    fun checkOutbound(): Boolean {
        if (!channel.isActive || closedForBackpressure) return false
        if (channel.isWritable) {
            unwritableTicks = 0
            return false
        }
        val ticks = ++unwritableTicks
        val queued = channel.bytesBeforeWritable()
        if (ticks > OutboundBackpressure.MAX_UNWRITABLE_TICKS || queued > OutboundBackpressure.HARD_CAP_BYTES) {
            closedForBackpressure = true
            OutboundBackpressure.closed++
            logger.warn {
                "Outbound backpressure: closing ${channel.remoteAddress()} side=$side, unwritable for $ticks tick(s) " +
                    "with $queued byte(s) queued"
            }
            channel.close()
            return true
        }
        if (ticks == 1) logger.info { "Outbound backpressure: ${channel.remoteAddress()} side=$side became unwritable ($queued byte(s) queued)" }
        return false
    }

    var initedPlayerList = false

    private val unregisteredSeen = java.util.concurrent.ConcurrentHashMap<Int, Int>()

    @Volatile
    var clientHasSpoken: Boolean = false
        private set

    fun receive(pair: OpcodeWithBuffer) {
        val frameBytes = pair.buf.readableBytes()
        try {
            clientHasSpoken = true
            dumper?.dump(pair.opcode, pair.buf)

            DiagnosticLog.markGameStage(channel)

            val registration = PacketRegistry.getRegistration(side, pair.opcode)
            if (registration == null) {
                DiagnosticLog.unregisteredOpcode(channel, side, pair.opcode, pair.buf)

                val seen = unregisteredSeen.merge(pair.opcode, 1, Int::plus) ?: 1
                if (seen <= 3 || seen % 500 == 0) {
                    logger.warn {
                        "Dropping inbound packet with no registered codec: opcode=${pair.opcode} " +
                            "size=${pair.buf.readableBytes()} side=$side (occurrence $seen); -Dopennxt.diag=true records bytes"
                    }
                }

                if (processUnidentifiedPackets)
                    enqueue(UnidentifiedPacket(OpcodeWithBuffer(pair.opcode, pair.buf.copy())), frameBytes)
                return
            }

            if (registration.name == "REBUILD_NORMAL" && !initedPlayerList) {
                val copy = UnidentifiedPacket(OpcodeWithBuffer(pair.opcode, pair.buf.copy()))
                enqueue(copy, frameBytes)

                val playerIndex = channel.attr(ProxyChannelAttributes.PLAYER_INDEX).get()
                println("Received REBUILD_NORMAL, decoding player list first (player index $playerIndex)")
                initedPlayerList = true

                val reader = GamePacketReader(pair.buf)
                reader.switchToBitAccess()
                reader.getBits(0x1e)
                for (i in 1 until 2048) {
                    if (i == playerIndex) {
                        println("Skipping own player index $i")
                        continue
                    }

                    reader.getBits(0x14)
                }
                reader.switchToByteAccess()

                val decoded = registration.codec.decode(reader)
                if (pair.buf.readableBytes() != 0 ){
                    logger.warn { "Readable bytes in packet ${registration.name}: ${pair.buf.readableBytes()}" }
                }

                logger.info { decoded.toString() }
                return
            }

            val decoded = registration.codec.decode(GamePacketReader(pair.buf))
            if (pair.buf.readableBytes() != 0 ){
                logger.warn { "Readable bytes in packet ${registration.name}: ${pair.buf.readableBytes()}" }
            }

            enqueue(decoded, frameBytes)
        } catch (e: Exception) {
            logger.error(e) {
                "Failed to decode inbound packet opcode=${pair.opcode} side=$side " +
                    "from ${channel.remoteAddress()}; packet dropped"
            }
        } finally {
            pair.buf.release()
        }
    }

    fun write(pair: OpcodeWithBuffer): Boolean {
        channel.write(pair)
        return true
    }

    fun write(packet: GamePacket): Boolean {
        if (packet is UnidentifiedPacket) {
            return write(packet.packet)
        }

        if (packet is VarpSmall &&
            OpenNXT.config.build != ServerConfig.SUPPORTED_BUILD &&
            System.getProperty("opennxt.compat.varpSmallAsLarge") != "off"
        ) {
            return write(VarpLarge(packet.id, packet.value))
        }

        if (packet is UndecodedClientPacket) {
            return write(OpcodeWithBuffer(packet.opcode, Unpooled.wrappedBuffer(packet.payload)))
        }

        try {
            val registration =
                PacketRegistry.getRegistration(if (side == Side.CLIENT) Side.SERVER else Side.CLIENT, packet::class)

            if (registration == null) {
                logger.warn("Registration not found for packet $packet side $side")
                return false
            }

            val buffer = Unpooled.buffer()
            @Suppress("UNCHECKED_CAST")
            (registration.codec as GamePacketCodec<GamePacket>).encode(packet, GamePacketBuilder(buffer))

            channel.write(OpcodeWithBuffer(registration.opcode, buffer))
            return true
        } catch (e: Exception) {
            logger.error(e) {
                "Failed to encode outbound packet ${packet::class.simpleName} side=$side " +
                    "to ${channel.remoteAddress()}; packet dropped"
            }
            return false
        }
    }

    fun flush() {
        channel.flush()
        checkOutbound()
    }
}

package com.opennxt.net

import com.opennxt.Constants
import com.opennxt.OpenNXT
import com.opennxt.net.handshake.HandshakeType
import com.opennxt.net.js5.Js5Session
import com.opennxt.net.js5.packet.Js5Packet
import com.opennxt.net.js5.packet.Js5PacketCodec
import io.netty.buffer.ByteBuf
import io.netty.channel.Channel
import io.netty.channel.ChannelDuplexHandler
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelOutboundHandlerAdapter
import io.netty.channel.ChannelPromise
import io.netty.util.AttributeKey
import mu.KotlinLogging
import java.io.ByteArrayOutputStream
import java.io.Writer
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

object DiagnosticLog {
    const val SYSTEM_PROPERTY = "opennxt.diag"

    const val DUMP_LIMIT = 256

    val PREDICTED_CLIENT_BUILD: Int =
        System.getProperty("opennxt.diag.predictedBuild")?.toIntOrNull()
            ?: readBuildFromStagedClient()
            ?: OpenNXT.config.build

    private fun readBuildFromStagedClient(): Int? = try {
        val exe = Constants.CLIENTS_PATH
            .resolve(OpenNXT.config.build.toString())
            .resolve("win64").resolve("original").resolve("rs2client.exe")
        if (!java.nio.file.Files.exists(exe)) null else {
            val text = String(java.nio.file.Files.readAllBytes(exe), Charsets.UTF_16LE)
            val i = text.indexOf("FileVersion")
            if (i < 0) null else Regex("(\\d{3,4})").find(text, i)?.groupValues?.get(1)?.toIntOrNull()
        }
    } catch (t: Throwable) {
        null
    }

    const val UNKNOWN_OPCODE_SAMPLES = 3

    const val JS5_LINE_LIMIT = 2000

    val OUT_DUMP_LIMIT: Int = System.getProperty("opennxt.diag.outDumpBytes")?.toIntOrNull() ?: 8192
    val OUT_WRITE_LIMIT: Int = System.getProperty("opennxt.diag.outWrites")?.toIntOrNull() ?: 200

    val SEND_LINE_LIMIT: Int = System.getProperty("opennxt.diag.sendLines")?.toIntOrNull() ?: 20000

    private const val JS5_PENDING_LIMIT = 4096

    private val logger = KotlinLogging.logger { }

    private val ATTR: AttributeKey<Connection> = AttributeKey.valueOf("opennxt-diagnostic-connection")

    private val ids = AtomicInteger(0)
    private val processStart = System.nanoTime()

    @Volatile
    private var enabledFlag = readProperty()

    private var writer: Writer? = null
    private var file: Path? = null
    private var writeFailed = false

    private fun readProperty(): Boolean {
        val raw = System.getProperty(SYSTEM_PROPERTY) ?: return false
        return raw.equals("true", true) || raw == "1" || raw.equals("yes", true)
    }

    val enabled: Boolean
        get() = enabledFlag

    fun refreshFromSystemProperty(): Boolean {
        enabledFlag = readProperty()
        return enabledFlag
    }

    fun currentFile(): Path? {
        flushPending()
        return synchronized(this) { file }
    }

    fun directory(): Path = Constants.DATA_PATH.resolve("diag")

    fun arm() {
        if (!enabled) return
        emit(null, null, "Diagnostic recorder started; waiting for a connection")
    }

    fun http(channel: Channel, line: String) {
        if (!enabled) return
        try {
            emit(of(channel), null, "[HTTP] $line")
        } catch (t: Throwable) {
            internalError("http", t)
        }
    }

    enum class Stage { HANDSHAKE, JS5, LOGIN, GAME }

    private fun stamp(): String = String.format("%9.3fs", (System.nanoTime() - processStart) / 1_000_000_000.0)

    private fun emit(conn: Connection?, stage: Stage?, line: String) {
        val prefix = if (conn == null) "[${stamp()}] [-] " else "[${stamp()}] [${conn.peer} #${conn.id}] "
        val text = prefix + (if (stage == null) "" else "[$stage] ") + line
        write(text)
        logger.info { "[diag] $text" }
    }

    private val pending = java.util.concurrent.LinkedBlockingQueue<String>()

    @Volatile
    private var writerThread: Thread? = null

    private fun write(text: String) {
        if (writeFailed) return
        ensureWriterThread()
        pending.add(text)
    }

    private fun ensureWriterThread() {
        if (writerThread != null) return
        synchronized(this) {
            if (writerThread != null) return
            val t = Thread({ writerLoop() }, "diag-writer")
            t.isDaemon = true
            writerThread = t
            t.start()
            Runtime.getRuntime().addShutdownHook(Thread({ drainPending() }, "diag-writer-shutdown"))
        }
    }

    private fun writerLoop() {
        while (true) {
            val line = try {
                pending.take()
            } catch (e: InterruptedException) {
                drainPending()
                return
            }
            writeLine(line)
        }
    }

    private fun drainPending() {
        while (true) writeLine(pending.poll() ?: return)
    }

    fun flushPending(timeoutMs: Long = 2000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (pending.isNotEmpty() && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(2)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
        }
    }

    private fun writeLine(text: String) {
        synchronized(this) {
            if (writeFailed) return
            try {
                var w: Writer? = writer
                if (w == null) {
                    val dir = directory()
                    Files.createDirectories(dir)
                    val name = "diag-" +
                        DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").format(LocalDateTime.now()) +
                        "-" + ProcessHandle.current().pid() + ".log"
                    val path = dir.resolve(name)
                    w = Files.newBufferedWriter(
                        path, StandardOpenOption.CREATE, StandardOpenOption.APPEND, StandardOpenOption.WRITE
                    )
                    writer = w
                    file = path
                    w.write("# OpenNXT diagnostic log - opened ${LocalDateTime.now()}\n")
                    w.write("# server build: ${serverBuild() ?: "unknown (config not loaded)"}\n")
                    w.write("# expected client build: $PREDICTED_CLIENT_BUILD\n")
                    w.write("# enabled by -D$SYSTEM_PROPERTY; dumps capped at $DUMP_LIMIT bytes per event\n")
                    w.flush()
                    logger.warn { "Diagnostic recording enabled; writing to $path" }
                }
                w!!.write(text)
                w.write("\n")
                w.flush()
            } catch (t: Throwable) {
                writeFailed = true
                logger.error(t) { "Diagnostic log write failed; file output disabled" }
            }
        }
    }

    private fun serverBuild(): Int? = try {
        OpenNXT.config.build
    } catch (t: Throwable) {
        null
    }

    fun hexDump(bytes: ByteArray, totalLength: Int = bytes.size, indent: String = "        "): String {
        val shown = minOf(bytes.size, DUMP_LIMIT)
        val sb = StringBuilder()
        var i = 0
        while (i < shown) {
            sb.append(indent).append(String.format("%04x  ", i))
            for (j in 0 until 16) {
                if (i + j < shown) sb.append(String.format("%02x ", bytes[i + j])) else sb.append("   ")
                if (j == 7) sb.append(' ')
            }
            sb.append(" |")
            for (j in 0 until 16) {
                if (i + j >= shown) break
                val c = bytes[i + j].toInt() and 0xff
                sb.append(if (c in 0x20..0x7e) c.toChar() else '.')
            }
            sb.append("|\n")
            i += 16
        }
        if (totalLength > shown) sb.append(indent).append("... (").append(totalLength - shown).append(" more bytes)\n")
        if (sb.isNotEmpty() && sb.last() == '\n') sb.setLength(sb.length - 1)
        return sb.toString()
    }

    fun snapshot(buf: ByteBuf, limit: Int = DUMP_LIMIT): ByteArray {
        val n = minOf(limit, buf.readableBytes())
        val out = ByteArray(n)
        buf.getBytes(buf.readerIndex(), out)
        return out
    }

    class Connection internal constructor(val id: Int, val peer: String) {
        internal val openedNanos = System.nanoTime()
        private val stages = LinkedHashSet<Stage>()
        private val bytesIn = AtomicLong()
        private val bytesOut = AtomicLong()
        private val unknownOpcodes = LinkedHashMap<Int, IntArray>()
        private val js5Pending = LinkedHashMap<Long, Int>()
        private var js5PendingTruncated = false
        private var js5Requests = 0
        private var js5Served = 0
        private var js5Lines = 0
        private var reason: String? = null
        internal val finished = AtomicBoolean(false)

        internal fun addBytesIn(n: Int) { bytesIn.addAndGet(n.toLong()) }
        internal fun addBytesOut(n: Int) { bytesOut.addAndGet(n.toLong()) }

        private var outWrites = 0
        private var outDumped = 0

        internal fun outBudget(size: Int): Int = synchronized(this) {
            outWrites++
            if (outWrites > OUT_WRITE_LIMIT) return -1
            if (outDumped >= OUT_DUMP_LIMIT) return 0
            val allowed = minOf(size, OUT_DUMP_LIMIT - outDumped)
            outDumped += allowed
            allowed
        }

        internal fun outWriteCount(): Int = synchronized(this) { outWrites }

        private var sends = 0

        internal fun sendBudget(): Int = synchronized(this) {
            sends++
            if (sends > SEND_LINE_LIMIT) -1 else sends
        }

        internal fun sendCount(): Int = synchronized(this) { sends }

        internal fun stage(stage: Stage): Boolean = synchronized(this) { stages.add(stage) }
        internal fun currentStage(): Stage = synchronized(this) { stages.lastOrNull() ?: Stage.HANDSHAKE }

        internal fun noteReason(text: String) = synchronized(this) { if (reason == null) reason = text }

        internal fun countUnknownOpcode(opcode: Int): Int = synchronized(this) {
            val slot = unknownOpcodes.getOrPut(opcode) { IntArray(1) }
            slot[0]++
            slot[0]
        }

        internal fun js5LineBudget(): Int = synchronized(this) {
            js5Lines++
            js5Lines
        }

        private val js5ByIndex = java.util.TreeMap<Int, Int>()

        internal fun js5Requested(index: Int, archive: Int) = synchronized(this) {
            js5Requests++
            js5ByIndex[index] = (js5ByIndex[index] ?: 0) + 1
            val key = (index.toLong() shl 32) or (archive.toLong() and 0xffffffffL)
            if (js5Pending.size < JS5_PENDING_LIMIT) js5Pending[key] = (js5Pending[key] ?: 0) + 1
            else js5PendingTruncated = true
        }

        internal fun js5ServedFile(index: Int, archive: Int) = synchronized(this) {
            js5Served++
            val key = (index.toLong() shl 32) or (archive.toLong() and 0xffffffffL)
            val n = js5Pending[key]
            if (n != null) {
                if (n <= 1) js5Pending.remove(key) else js5Pending[key] = n - 1
            }
        }

        internal fun summary(): String = synchronized(this) {
            val ms = (System.nanoTime() - openedNanos) / 1_000_000
            val sb = StringBuilder()
            sb.append("SUMMARY stages=").append(if (stages.isEmpty()) "NONE" else stages.joinToString(">"))
            sb.append(" bytesIn=").append(bytesIn.get())
            sb.append(" bytesOut=").append(bytesOut.get())
            sb.append(" durationMs=").append(ms)
            sb.append(" reason=").append(reason ?: "unknown")
            if (js5Requests > 0 || js5Served > 0) {
                sb.append(" js5Requested=").append(js5Requests).append(" js5Served=").append(js5Served)
                if (js5ByIndex.isNotEmpty())
                    sb.append(" js5ByIndex={")
                        .append(js5ByIndex.entries.joinToString(",") { "${it.key}:${it.value}" })
                        .append("}")
                if (js5Pending.isNotEmpty()) {
                    sb.append(" js5Unserved=")
                    sb.append(js5Pending.keys.take(20).joinToString(",") {
                        "[${(it ushr 32).toInt()},${it.toInt()}]"
                    })
                    if (js5Pending.size > 20) sb.append(",...(${js5Pending.size} distinct)")
                    if (js5PendingTruncated) sb.append(" (pending tracking truncated)")
                }
            }
            if (unknownOpcodes.isNotEmpty()) {
                sb.append(" unregisteredOpcodes={")
                sb.append(unknownOpcodes.entries.joinToString(",") { "${it.key}x${it.value[0]}" })
                sb.append("}")
            }
            sb.toString()
        }
    }

    fun of(channel: Channel): Connection? = if (!enabledFlag) null else channel.attr(ATTR).get()

    fun attach(ctx: ChannelHandlerContext) {
        if (!enabledFlag) return
        try {
            val channel = ctx.channel()
            if (channel.attr(ATTR).get() != null) return
            val conn = Connection(ids.incrementAndGet(), channel.remoteAddress()?.toString() ?: "unknown")
            channel.attr(ATTR).set(conn)
            conn.stage(Stage.HANDSHAKE)
            emit(conn, Stage.HANDSHAKE, "OPENED local=${channel.localAddress()}")
            ctx.pipeline().addFirst("diagnostic-tap", DiagnosticTap(conn))
            channel.closeFuture().addListener { finish(conn, "channel closed") }
        } catch (t: Throwable) {
            internalError("attach", t)
        }
    }

    fun attachJs5(channel: Channel) {
        if (!enabledFlag) return
        try {
            val conn = channel.attr(ATTR).get() ?: return
            if (channel.pipeline().get("diagnostic-js5-tap") != null) return
            channel.pipeline().addLast("diagnostic-js5-tap", DiagnosticJs5ResponseTap(conn))
        } catch (t: Throwable) {
            internalError("attachJs5", t)
        }
    }

    fun stage(channel: Channel, stage: Stage, detail: String = "") {
        val conn = of(channel) ?: return
        try {
            if (conn.stage(stage)) emit(conn, stage, "STAGE ENTERED${if (detail.isEmpty()) "" else " $detail"}")
        } catch (t: Throwable) {
            internalError("stage", t)
        }
    }

    fun note(channel: Channel, stage: Stage, line: String) {
        val conn = of(channel) ?: return
        try {
            emit(conn, stage, line)
        } catch (t: Throwable) {
            internalError("note", t)
        }
    }

    fun reason(channel: Channel, text: String) {
        val conn = of(channel) ?: return
        try {
            conn.noteReason(text)
        } catch (t: Throwable) {
            internalError("reason", t)
        }
    }

    fun bytes(channel: Channel, stage: Stage, label: String, data: ByteArray, totalLength: Int = data.size) {
        val conn = of(channel) ?: return
        try {
            emit(conn, stage, "$label ($totalLength bytes)\n${hexDump(data, totalLength)}")
        } catch (t: Throwable) {
            internalError("bytes", t)
        }
    }

    internal fun finish(conn: Connection, reason: String) {
        if (!conn.finished.compareAndSet(false, true)) return
        try {
            conn.noteReason(reason)
            emit(conn, conn.currentStage(), conn.summary())
        } catch (t: Throwable) {
            internalError("finish", t)
        }
    }

    private fun internalError(where: String, t: Throwable) {
        logger.warn(t) { "Diagnostic recorder failed in $where" }
    }

    fun buildAnnounced(channel: Channel, stage: Stage, clientBuild: Int, context: String) {
        val conn = of(channel) ?: return
        try {
            val server = serverBuild()
            if (server == null) {
                emit(conn, stage, "client build $clientBuild, server build unknown ($context)")
                return
            }
            emit(
                conn, stage,
                if (clientBuild == PREDICTED_CLIENT_BUILD)
                    "client build matches expected build $PREDICTED_CLIENT_BUILD"
                else
                    "unexpected client build: expected $PREDICTED_CLIENT_BUILD, got $clientBuild"
            )
            if (clientBuild != PREDICTED_CLIENT_BUILD) {
                logger.error {
                    "[diag] unexpected client build: expected $PREDICTED_CLIENT_BUILD, got $clientBuild"
                }
                conn.noteReason("client build $clientBuild != expected build $PREDICTED_CLIENT_BUILD")
            }

            if (clientBuild != server) {
                val bar = "!".repeat(78)
                val line = "client build $clientBuild does not match server build $server ($context)"
                emit(
                    conn, stage,
                    "build mismatch: $line; packets may not decode correctly"
                )
                logger.error { "[diag] $line" }
                conn.noteReason("build mismatch: client $clientBuild vs server $server")
            } else {
                emit(conn, stage, "client build $clientBuild matches server build ($context)")
            }
        } catch (t: Throwable) {
            internalError("buildAnnounced", t)
        }
    }

    internal fun js5Frame(channel: Channel, conn: Connection, frame: ByteArray) {
        val opcode = frame[0].toInt() and 0xff
        val lines = conn.js5LineBudget()
        val verbose = lines <= JS5_LINE_LIMIT
        if (lines == JS5_LINE_LIMIT + 1) {
            emit(conn, Stage.JS5, "per-request logging stopped after $JS5_LINE_LIMIT lines")
        }
        when (opcode) {
            Js5PacketCodec.RequestFile.opcodeLow,
            Js5PacketCodec.RequestFile.opcodeHigh,
            Js5PacketCodec.RequestFile.opcodeNxtLow,
            Js5PacketCodec.RequestFile.opcodeNxtHigh1,
            Js5PacketCodec.RequestFile.opcodeNxtHigh2 -> {
                val index = frame[1].toInt() and 0xff
                val archive = ((frame[2].toInt() and 0xff) shl 24) or ((frame[3].toInt() and 0xff) shl 16) or
                    ((frame[4].toInt() and 0xff) shl 8) or (frame[5].toInt() and 0xff)
                val build = ((frame[6].toInt() and 0xff) shl 8) or (frame[7].toInt() and 0xff)
                val priority = opcode != Js5PacketCodec.RequestFile.opcodeNxtLow &&
                    opcode != Js5PacketCodec.RequestFile.opcodeLow
                conn.js5Requested(index, archive)
                if (verbose) {
                    val xor = channel.attr(Js5Session.XOR_KEY).get()
                    emit(
                        conn, Stage.JS5,
                        "REQUEST index=$index archive=$archive priority=$priority opcode=$opcode " +
                            "build=$build xor=${xor ?: "unset"}"
                    )
                }
            }
            Js5PacketCodec.XorRequest.opcode -> {
                val xor = frame[1].toInt() and 0xff
                emit(conn, Stage.JS5, "XOR KEY SET xor=$xor")
            }
            Js5PacketCodec.ConnectionInitialized.opcode ->
                if (verbose) emit(conn, Stage.JS5, "CONNECTION_INITIALIZED\n${hexDump(frame)}")
            Js5PacketCodec.RequestTermination.opcode -> {
                emit(conn, Stage.JS5, "REQUEST_TERMINATION\n${hexDump(frame)}")
                conn.noteReason("client requested js5 termination")
            }
            Js5PacketCodec.LoggedIn.opcode -> if (verbose) emit(conn, Stage.JS5, "LOGGED_IN\n${hexDump(frame)}")
            Js5PacketCodec.LoggedOut.opcode -> if (verbose) emit(conn, Stage.JS5, "LOGGED_OUT\n${hexDump(frame)}")
            else -> emit(
                conn, Stage.JS5,
                "UNKNOWN JS5 OPCODE $opcode, frame skipped\n${hexDump(frame)}"
            )
        }
    }

    internal fun js5Serve(conn: Connection, packet: Js5Packet.RequestFileResponse) {
        conn.js5ServedFile(packet.index, packet.archive)
        if (conn.js5LineBudget() <= JS5_LINE_LIMIT) {
            emit(
                conn, Stage.JS5,
                "SERVED index=${packet.index} archive=${packet.archive} priority=${packet.priority} " +
                    "containerBytes=${packet.data.readableBytes()}"
            )
        }
    }

    fun unregisteredOpcode(channel: Channel, side: Side, opcode: Int, buf: ByteBuf) {
        val conn = of(channel) ?: return
        try {
            conn.stage(Stage.GAME)
            val n = conn.countUnknownOpcode(opcode)
            when {
                n <= UNKNOWN_OPCODE_SAMPLES -> {
                    val size = buf.readableBytes()
                    val data = snapshot(buf)
                    emit(
                        conn, Stage.GAME,
                        "UNREGISTERED OPCODE $opcode side=$side size=$size occurrence=$n (dropped)\n" +
                            hexDump(data, size)
                    )
                }
                n == UNKNOWN_OPCODE_SAMPLES + 1 ->
                    emit(
                        conn, Stage.GAME,
                        "UNREGISTERED OPCODE $opcode: further samples suppressed"
                    )
            }
        } catch (t: Throwable) {
            internalError("unregisteredOpcode", t)
        }
    }

    fun sendingPacket(
        channel: Channel,
        side: Side,
        opcode: Int,
        payload: Int,
        declared: Int,
        resolvedName: String? = null,
        head: ByteArray? = null
    ) {
        val conn = of(channel) ?: return
        try {
            val n = conn.sendBudget()
            if (n < 0) return
            val name = resolvedName ?: run {
                val names =
                    if (side == Side.CLIENT) OpenNXT.protocol.serverProtNames else OpenNXT.protocol.clientProtNames
                names.reversedValues()[opcode]
            } ?: "(unnamed)"
            val from = OpenNXT.protocol.effectiveBuild
            val sizeNote = when (declared) {
                -1 -> "var-byte"
                -2 -> "var-short"
                else -> "fixed $declared"
            }
            val parent = parentOf(name, head)
            val parentNote = if (parent == null) "" else " parent=${parent ushr 16}:${parent and 0xffff}"
            emit(conn, conn.currentStage(),
                "SEND #$n opcode=$opcode name=$name payload=$payload$parentNote ($sizeNote, names from build $from)")
        } catch (t: Throwable) {
            internalError("sendingPacket", t)
        }
    }

    private val PARENT_ORDER: Map<String, String> = mapOf(
        "IF_SETEVENTS" to "intle",
        "IF_SETANIM" to "intle",
        "IF_SETCLICKMASK" to "intle",
        "IF_SETPLAYERHEAD" to "intle",
        "IF_SETPLAYERHEAD_SNAPSHOT" to "intle",
        "IF_SETPLAYERMODEL_SELF" to "intle",
        "IF_SETPLAYERMODEL_SNAPSHOT" to "intle",
        "IF_SETTEXTANTIMACRO" to "intle",
        "IF_CLOSESUB" to "int",
        "IF_SETHIDE" to "intv2",
        "IF_SETOBJECT64" to "intv2",
        "IF_SETMODEL" to "intv1",
    )

    fun wantsHead(channel: Channel, name: String?): Boolean =
        name != null && PARENT_ORDER.containsKey(name) && of(channel) != null

    internal fun parentOf(name: String, head: ByteArray?): Int? {
        val order = PARENT_ORDER[name] ?: return null
        if (head == null || head.size < 4) return null
        val b0 = head[0].toInt() and 0xff; val b1 = head[1].toInt() and 0xff
        val b2 = head[2].toInt() and 0xff; val b3 = head[3].toInt() and 0xff
        return when (order) {
            "intle" -> (b3 shl 24) or (b2 shl 16) or (b1 shl 8) or b0
            "int" -> (b0 shl 24) or (b1 shl 16) or (b2 shl 8) or b3
            "intv1" -> (b0 shl 8) or b1 or (b2 shl 24) or (b3 shl 16)
            "intv2" -> (b0 shl 16) or (b1 shl 24) or b2 or (b3 shl 8)
            else -> null
        }
    }

    fun markGameStage(channel: Channel) {
        val conn = of(channel) ?: return
        try {
            if (conn.stage(Stage.GAME)) emit(conn, Stage.GAME, "STAGE ENTERED")
        } catch (t: Throwable) {
            internalError("markGameStage", t)
        }
    }

    class DiagnosticTap internal constructor(private val conn: Connection) : ChannelDuplexHandler() {
        private var firstByteSeen = false
        private var isJs5 = false
        private var js5HandshakePending = true
        private val carry = ByteArrayOutputStream()

        override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
            if (msg is ByteBuf) {
                conn.addBytesIn(msg.readableBytes())
                try {
                    inspect(ctx, msg)
                } catch (t: Throwable) {
                    internalError("tap.inspect", t)
                }
            }
            ctx.fireChannelRead(msg)
        }

        override fun write(ctx: ChannelHandlerContext, msg: Any, promise: ChannelPromise) {
            if (msg is ByteBuf) {
                conn.addBytesOut(msg.readableBytes())
                try {
                    recordOutbound(msg)
                } catch (t: Throwable) {
                    internalError("tap.recordOutbound", t)
                }
            }
            ctx.write(msg, promise)
        }

        private fun recordOutbound(buf: ByteBuf) {
            val stage = conn.currentStage()
            if (stage == Stage.JS5) return
            val size = buf.readableBytes()
            if (size <= 0) return

            val allowed = conn.outBudget(size)
            if (allowed < 0) return
            val n = conn.outWriteCount()
            if (allowed == 0) {
                if (n == 1 || n % 20 == 0)
                    emit(conn, stage, "OUT #$n $size bytes (dump limit reached)")
                return
            }
            val bytes = ByteArray(minOf(allowed, size))
            buf.getBytes(buf.readerIndex(), bytes)
            emit(conn, stage, "OUT #$n $size bytes" +
                (if (bytes.size < size) " (first ${bytes.size} shown)" else "") + "\n" +
                hexDump(bytes, size))
        }

        override fun channelInactive(ctx: ChannelHandlerContext) {
            finish(conn, "channel went inactive")
            ctx.fireChannelInactive()
        }

        override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
            conn.noteReason("exception: ${cause.javaClass.name}: ${cause.message}")
            emit(conn, conn.currentStage(), "EXCEPTION ${cause.javaClass.name}: ${cause.message}")
            ctx.fireExceptionCaught(cause)
        }

        private fun inspect(ctx: ChannelHandlerContext, buf: ByteBuf) {
            val readable = buf.readableBytes()
            if (readable <= 0) return
            val bytes = ByteArray(readable)
            buf.getBytes(buf.readerIndex(), bytes)

            var offset = 0
            if (!firstByteSeen) {
                firstByteSeen = true
                val id = bytes[0].toInt() and 0xff
                isJs5 = id == HandshakeType.JS_5.id
                offset = 1
            }
            if (!isJs5) return
            carry.write(bytes, offset, bytes.size - offset)
            drain(ctx)
        }

        private fun drain(ctx: ChannelHandlerContext) {
            val data = carry.toByteArray()
            var p = 0
            while (true) {
                if (js5HandshakePending) {
                    if (data.size - p < 1) break
                    val size = data[p].toInt() and 0xff
                    if (data.size - p < 1 + size) break
                    val frame = data.copyOfRange(p, p + 1 + size)
                    emit(
                        conn, Stage.JS5,
                        "HANDSHAKE FRAME ($size bytes)\n" +
                            hexDump(frame, 1 + size)
                    )
                    if (size >= 8) {
                        val major = ((frame[1].toInt() and 0xff) shl 24) or
                            ((frame[2].toInt() and 0xff) shl 16) or
                            ((frame[3].toInt() and 0xff) shl 8) or (frame[4].toInt() and 0xff)
                        val minor = ((frame[5].toInt() and 0xff) shl 24) or
                            ((frame[6].toInt() and 0xff) shl 16) or
                            ((frame[7].toInt() and 0xff) shl 8) or (frame[8].toInt() and 0xff)
                        emit(
                            conn, Stage.JS5,
                            "handshake build: $major-$minor"
                        )
                        buildAnnounced(ctx.channel(), Stage.JS5, major, "js5 handshake, raw frame")
                    } else {
                        emit(
                            conn, Stage.JS5,
                            "handshake frame too short for a build ($size byte(s))"
                        )
                    }
                    p += 1 + size
                    js5HandshakePending = false
                    continue
                }
                if (data.size - p < 10) break
                js5Frame(ctx.channel(), conn, data.copyOfRange(p, p + 10))
                p += 10
            }
            carry.reset()
            if (p < data.size) carry.write(data, p, data.size - p)
        }
    }

    class DiagnosticJs5ResponseTap internal constructor(private val conn: Connection) : ChannelOutboundHandlerAdapter() {
        override fun write(ctx: ChannelHandlerContext, msg: Any, promise: ChannelPromise) {
            if (msg is Js5Packet.RequestFileResponse) {
                try {
                    js5Serve(conn, msg)
                } catch (t: Throwable) {
                    internalError("js5Tap", t)
                }
            }
            ctx.write(msg, promise)
        }
    }
}

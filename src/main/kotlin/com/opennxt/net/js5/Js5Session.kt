package com.opennxt.net.js5

import com.opennxt.Js5Thread
import com.opennxt.OpenNXT
import com.opennxt.net.DiagnosticLog
import com.opennxt.filesystem.Filesystem
import com.opennxt.net.js5.packet.Js5Packet
import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import io.netty.channel.Channel
import io.netty.util.AttributeKey
import mu.KotlinLogging
import java.util.concurrent.ConcurrentLinkedQueue

class Js5Session(val channel: Channel) : AutoCloseable {
    private val logger = KotlinLogging.logger { }

    companion object {
        val ATTR_KEY = AttributeKey.valueOf<Js5Session>("js5-session")
        val XOR_KEY = AttributeKey.valueOf<Int>("js5-xor-key")
        val LOGGED_IN = AttributeKey.valueOf<Boolean>("js5-logged-in")
    }

    val highPriorityRequests = ConcurrentLinkedQueue<Js5Packet.RequestFile>()
    val lowPriorityRequests = ConcurrentLinkedQueue<Js5Packet.RequestFile>()

    var initialized = false

    init {
        channel.attr(ATTR_KEY).set(this)
        channel.attr(XOR_KEY).set(0)
        channel.attr(LOGGED_IN).set(false)
    }

    private fun Js5Packet.RequestFile.loadFileData(): ByteBuf? {
        try {
            return when {
                index == 255 && archive == 255 -> Unpooled.wrappedBuffer(OpenNXT.checksumTable)
                index == 255 -> Unpooled.wrappedBuffer(
                    OpenNXT.filesystem.readReferenceTable(archive) ?: return notFound("reference table absent")
                )
                else -> Unpooled.wrappedBuffer(
                    OpenNXT.filesystem.read(index, archive) ?: return notFound("container absent")
                )
            }
        } catch (e: Throwable) {
            logger.error(e) { "js5 load FAILED for [$index, $archive]" }
            DiagnosticLog.note(
                channel, DiagnosticLog.Stage.JS5,
                "LOAD FAILED index=$index archive=$archive ${e.javaClass.name}: ${e.message}"
            )
            return null
        }
    }

    private fun Js5Packet.RequestFile.notFound(why: String): ByteBuf? {
        DiagnosticLog.note(
            channel, DiagnosticLog.Stage.JS5,
            "not found: index=$index archive=$archive ($why)"
        )
        return null
    }

    private fun writeResponse(priority: Boolean, request: Js5Packet.RequestFile, data: ByteBuf) {
        channel.write(Js5Packet.RequestFileResponse(priority, request.index, request.archive, data))
            .addListener { future ->
                if (!future.isSuccess) {
                    logger.error(future.cause()) {
                        "js5 write FAILED for [${request.index}, ${request.archive}] - " +
                            "the client is never told about this file"
                    }
                    DiagnosticLog.note(
                        channel, DiagnosticLog.Stage.JS5,
                        "WRITE FAILED index=${request.index} archive=${request.archive} " +
                            "${future.cause()?.javaClass?.name}: ${future.cause()?.message}"
                    )
                }
            }
    }

    fun process(limit: Int): Int {
        var bytesSent = 0

        try {
            while (bytesSent < limit && highPriorityRequests.isNotEmpty()) {
                val request = highPriorityRequests.poll()
                val data = request.loadFileData()
                if (data == null) {
                    logger.warn { "Received file request for non-existing file: [${request.index}, ${request.archive}]" }
                } else {
                    writeResponse(true, request, data)
                    bytesSent += data.capacity()
                }
            }

            while (bytesSent < limit && lowPriorityRequests.isNotEmpty()) {
                val request = lowPriorityRequests.poll()
                val data = request.loadFileData()
                if (data == null) {
                    logger.warn { "Received file request for non-existing file: [${request.index}, ${request.archive}]" }
                } else {
                    writeResponse(false, request, data)
                    bytesSent += data.capacity()
                }
            }
        } finally {
            if (bytesSent != 0) {
                channel.flush()
            }
        }

        return bytesSent
    }

    fun initialize() {
        if (initialized) {
            logger.warn("Tried initializing js5 session twice from ${channel.remoteAddress()}. Terminating connection.")
            close()
            return
        }

        initialized = true
        Js5Thread.addSession(this)
    }

    override fun close() {
        initialized = false

        Js5Thread.removeSession(this)

        channel.close()
        highPriorityRequests.clear()
        lowPriorityRequests.clear()
    }
}

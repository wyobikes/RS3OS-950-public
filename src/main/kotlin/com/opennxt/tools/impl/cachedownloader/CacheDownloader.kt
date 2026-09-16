package com.opennxt.tools.impl.cachedownloader

import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int
import com.google.common.util.concurrent.ThreadFactoryBuilder
import com.opennxt.Constants
import com.opennxt.ext.getCrc32
import com.opennxt.filesystem.ChecksumTable
import com.opennxt.filesystem.Container
import com.opennxt.filesystem.Filesystem
import com.opennxt.filesystem.ReferenceTable
import com.opennxt.filesystem.sqlite.SqliteFilesystem
import com.opennxt.tools.Tool
import java.lang.Thread.sleep
import java.nio.ByteBuffer
import java.util.concurrent.*
import kotlin.system.exitProcess

class CacheDownloader : Tool("cache-downloader", "Updates / downloads the cache from Jagex' JS5 servers") {
    private val ip by option(help = "Live js5 server ip").default("content.runescape.com")
    private val port by option(help = "Live js5 server port").int().default(43594)
    private val numJs5Clients by option(help = "The amount of concurrent js5 connections").int().default(1)
    private val numHttpClients by option(help = "The max amount of concurrent HTTP connections").int().default(3)
    private val ioThreads by option(help = "The number of I/O threads for cache-related operations").int().default(8)
    private val checkThreads by option(help = "The number of I/O threads for checking which files require updating").int()
        .default(4)

    private lateinit var cache: Filesystem

    private lateinit var checkerExecutor: ExecutorService

    private lateinit var clientPool: Js5ClientPool
    private lateinit var checksumTable: ChecksumTable

    private lateinit var requestHandler: Js5RequestHandler

    fun request(priority: Boolean, index: Int, archive: Int): Js5RequestHandler.ArchiveRequest {
        if (index == 255) {
            val request = Js5RequestHandler.ArchiveRequest(index, archive, priority)

            return request
        } else {
            TODO("Non-255 requests")
        }
    }

    private fun downloadChecksumTable() {
        val request = clientPool.addRequest(true, 255, 255)
            ?: throw IllegalStateException("failed to request [255,255]")
        if (!request.awaitCompletion(30, TimeUnit.SECONDS)) {
            logger.error { "Took more than 30 seconds to download the checksum table. Exiting." }
            exitProcess(1)
        }

        logger.info { "Finished downloading checksum table!" }
        checksumTable = ChecksumTable.decode(ByteBuffer.wrap(Container.decode(request.buffer!!).data))
        checksumTable.entries.forEachIndexed { index, entry ->
            logger.info { "checksum for index $index = $entry" }
        }
    }

    private fun createNewIndices() {
        var created = 0
        var skipped = 0

        checksumTable.entries.forEachIndexed { index, entry ->
            if (entry.crc == 0 && entry.version == 0) {
                skipped++
                return@forEachIndexed
            }

            val absent = cache.readReferenceTable(index) == null && !cache.exists(index, 0)
            cache.createIndex(index)
            if (absent) {
                created++
                logger.info { "Created missing local index $index." }
            }
        }

        logger.info {
            "Index check done: ${checksumTable.entries.size} on server, $skipped empty, " +
                "$created created, ${cache.numIndices()} local."
        }
    }

    private fun updateReferenceTables() {
        val pending = HashSet<Js5RequestHandler.ArchiveRequest>()

        checksumTable.entries.forEachIndexed { index, entry ->
            if (entry.crc == 0 && entry.version == 0) return@forEachIndexed

            val existingRaw = cache.readReferenceTable(index)
            if (existingRaw == null) {
                logger.info { "Reference table for index $index missing, adding to downloads..." }
                pending += clientPool.addRequest(true, 255, index)
                    ?: throw IllegalStateException("Failed to add reference table request $index")
                return@forEachIndexed
            }

            val crc = existingRaw.getCrc32()
            if (entry.crc != crc) {
                logger.info { "CRC mismatch in reference table for index $index, adding to downloads..." }
                pending += clientPool.addRequest(true, 255, index)
                    ?: throw IllegalStateException("Failed to add reference table request $index")
                return@forEachIndexed
            }

            val existing = ReferenceTable(cache, index)
            existing.decode(ByteBuffer.wrap(Container.decode(existingRaw).data))

            if (existing.version != entry.version) {
                logger.info { "Version mismatch in reference table for index $index, adding to downloads..." }
                pending += clientPool.addRequest(true, 255, index)
                    ?: throw IllegalStateException("Failed to add reference table request $index")
                return@forEachIndexed
            }

            logger.info { "Reference table for index $index is up-to-date." }
        }

        var saved = 0
        val failures = LinkedHashMap<Int, String>()

        pending.forEach { request ->
            val index = request.archive

            if (!request.awaitCompletion(30, TimeUnit.SECONDS)) {
                failures[index] = "timed out after 30s"
                return@forEach
            }

            val buffer = request.buffer
            if (buffer == null) {
                failures[index] = "completed with a null buffer"
                return@forEach
            }

            val crc = buffer.getCrc32()
            val entry = checksumTable.entries[index]
            if (crc != entry.crc) {
                failures[index] = "CRC mismatch (expected ${entry.crc}, got $crc)"
                return@forEach
            }

            val container = try {
                Container.decode(buffer)
            } catch (e: Exception) {
                failures[index] = "container failed to decode: $e"
                return@forEach
            }

            val referenceTable = ReferenceTable(cache, index)
            try {
                referenceTable.decode(ByteBuffer.wrap(container.data))
            } catch (e: Exception) {
                failures[index] = "reference table failed to decode: $e"
                return@forEach
            }

            if (referenceTable.version != entry.version) {
                failures[index] = "version mismatch (expected ${entry.version}, got ${referenceTable.version})"
                return@forEach
            }

            cache.writeReferenceTable(index, buffer.array(), container.version, crc)
            saved++
            logger.info { "Saved reference table for index $index (${referenceTable.archives.size} archives)." }
        }

        logger.info { "Reference tables: ${pending.size} requested, $saved saved, ${failures.size} rejected." }
        if (failures.isNotEmpty()) {
            logger.warn { "These reference tables were not saved:" }
            failures.forEach { (index, why) -> logger.warn { "   index $index: $why" } }
        }
    }

    override fun runTool() {
        check(numJs5Clients > 0) { "num-js5-clients must be greater than 0" }
        check(numHttpClients > 0) { "num-http-clients must be greater than 0" }

        logger.info { "Starting download from $ip:$port" }

        logger.info { "Opening filesystem from ${Constants.CACHE_PATH}" }
        cache = SqliteFilesystem(Constants.CACHE_PATH)

        logger.info { "Setting up client pool with $numJs5Clients js5 clients and $numHttpClients http clients" }
        clientPool = Js5ClientPool(numJs5Clients, numHttpClients, ip, port)

        logger.info { "Grabbing checksum and reference tables..." }
        clientPool.openConnections(amount = 1)
        val client = clientPool.getClient()

        try {
            if (!client.awaitConnected(30, TimeUnit.SECONDS)) {
                logger.error { "Gave up waiting for the js5 connection to become usable after 30s." }
                logger.error { "  socket open : ${client.channel?.isOpen}" }
                logger.error { "  state       : ${client.state}" }
                logger.error { "  last read   : ${System.currentTimeMillis() - client.lastRead}ms ago" }
                when (client.state) {
                    Js5ClientState.HANDSHAKE -> logger.error {
                        "  No handshake response. Check the build (${client.version}) and token against jav_config."
                    }
                    Js5ClientState.PREFETCHES -> logger.error {
                        "  Stalled waiting for ${Js5ClientPipeline.EXPECTED_PREFETCHES} prefetch ints; " +
                            "check -Dopennxt.js5.client.prefetches (should be 0)."
                    }
                    else -> logger.error { "  Reached ${client.state} without ever being notified." }
                }
                exitProcess(1)
            }
        } catch (e: InterruptedException) {
            logger.error { "Lock on connection got interrupted. Exiting." }
            exitProcess(1)
        }

        logger.info { "Connected! Requesting checksum table now..." }
        downloadChecksumTable()
        createNewIndices()

        logger.info { "Setting up request handler" }
        requestHandler = Js5RequestHandler(clientPool, cache, ioThreads)

        logger.info { "Checking tables" }
        updateReferenceTables()

        logger.info { "Starting table checks" }
        checkerExecutor = Executors.newFixedThreadPool(checkThreads, ThreadFactoryBuilder()
            .setNameFormat("table-checker-%d")
            .setUncaughtExceptionHandler { t, e ->
                logger.error { "Uncaught exception in thread ${t.name}: $e" }
                e.printStackTrace()
            }
            .build())

        val musicChecker = IndexCompletionChecker(cache, 40, requestHandler)
        checkerExecutor.submit(musicChecker)

        val completionCheckers = HashSet<IndexCompletionChecker>()
        checksumTable.entries.forEachIndexed { index, entry ->
            if (index == 40 || (entry.crc == 0 && entry.version == 0)) return@forEachIndexed

            val checker = IndexCompletionChecker(cache, index, requestHandler)
            completionCheckers.add(checker)
            checkerExecutor.submit(checker)
        }

        Thread(requestHandler, "js5-request-handler").start()

        var doneJs5 = false
        var doneHttp = false

        val stallLimitTicks = System.getProperty("opennxt.stall.seconds")?.toIntOrNull() ?: 180
        var lastFingerprint = ""
        var stalledTicks = 0

        while (true) {
            val snapshot = requestHandler.createSnapshot()

            val fingerprint = "${snapshot.pendingCount}/${snapshot.processingCount}/" +
                "${snapshot.pendingHttpCount}/${snapshot.pendingIOOPerations}"
            if (fingerprint == lastFingerprint) stalledTicks++ else stalledTicks = 0
            lastFingerprint = fingerprint

            if (stalledTicks >= stallLimitTicks) {
                logger.error { "-------------------------------------------------------------" }
                logger.error { " Stalled: no progress for ${stalledTicks}s" }
                logger.error { "   unassigned js5 : ${snapshot.pendingCount}" }
                logger.error { "   assigned js5   : ${snapshot.processingCount}" }
                logger.error { "   pending http   : ${snapshot.pendingHttpCount}" }
                logger.error { "   pending io     : ${snapshot.pendingIOOPerations}" }
                logger.error { "" }
                if (snapshot.pendingHttpCount > 0 && snapshot.pendingIOOPerations == 0) {
                    logger.error { " ${snapshot.pendingHttpCount} music archives failed to download." }
                    logger.error { " Re-run the tool to fetch only the missing files." }
                } else {
                    logger.error { " Re-run the tool to resume; existing files are kept." }
                }
                logger.error { "-------------------------------------------------------------" }
                exitProcess(2)
            }

            if (!clientPool.closed && completionCheckers.all { it.completed } && snapshot.pendingCount == 0 && snapshot.processingCount == 0) {
                logger.info { "All js5 operations are done, closing js5 client pool" }
                clientPool.close()
                doneJs5 = true
            }

            if (clientPool.closed && musicChecker.completed && snapshot.pendingHttpCount == 0) {
                logger.info { "All http operations are done, preparing to shutdown" }
                doneHttp = true
            }

            if (doneJs5 && doneHttp && snapshot.pendingIOOPerations == 0) {
                logger.info { "No more pending IO operations, shutting down remaining things" }
                logger.error { "We can't close the cache yet. Should probably support that." }
                exitProcess(0)
            }

            logger.info {
                "Unassigned: ${snapshot.pendingCount} (~${snapshot.pendingSize / 1024L / 1024L}MB). Assigned: ${snapshot.processingCount} (~${snapshot.processingSize / 1024L / 1024L}MB). Http pending: ${snapshot.pendingHttpCount} (~${snapshot.pendingHttpSize / 1024L / 1024L}MB). Js5 Bandwidth: ${
                    "%.2f".format(
                        Js5ClientPipeline.getReadThroughput().toDouble() / 1024.0 / 1024.0
                    )
                }MB/s (excludes http). Pending IO ops: ${snapshot.pendingIOOPerations}. Last worker tick: ${System.currentTimeMillis()-snapshot.lastTick}ms ago"
            }
            sleep(1000)
        }
    }
}

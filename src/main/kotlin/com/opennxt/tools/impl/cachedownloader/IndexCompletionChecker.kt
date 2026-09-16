package com.opennxt.tools.impl.cachedownloader

import com.opennxt.ext.getCrc32
import com.opennxt.filesystem.Filesystem
import mu.KotlinLogging

class IndexCompletionChecker(
    private val filesystem: Filesystem,
    private val index: Int,
    private val requests: Js5RequestHandler?
) : Runnable {
    private val logger = KotlinLogging.logger { }

    var started = false
    var completed = false
    var progress = 0L
    var estimatedTotal = 0L

    var skippedNoTable = false

    override fun run() {
        started = true

        val table = filesystem.getReferenceTable(index)
        if (table == null) {
            skippedNoTable = true
            completed = true
            logger.warn {
                "No reference table for index $index after the table pass - nothing to verify here. " +
                    "Archives in this index cannot be checked or repaired."
            }
            return
        }

        estimatedTotal = table.totalCompressedSize()
        logger.info { "Starting completion checker for index $index. Estimated compressed size: ${estimatedTotal / 1024L / 1024L}MB" }

        val start = System.currentTimeMillis()
        var updatesRequired = 0

        val requests = HashSet<Js5RequestHandler.ArchiveRequest>()

        table.archives.forEach { (id, archive) ->
            try {
                if (requests.size > 500) {
                    this.requests?.request(requests)
                    requests.clear()
                }

                val existing = filesystem.read(index, id)
                if (existing == null) {
                    requests.add(
                        Js5RequestHandler.ArchiveRequest(
                            index,
                            id,
                            false,
                            archive.compressedSize,
                            archive.crc,
                            archive.version
                        )
                    )
                    updatesRequired++
                    return@forEach
                }

                val crc = existing.getCrc32(existing.capacity() - 2)
                if (crc != archive.crc) {
                    requests.add(
                        Js5RequestHandler.ArchiveRequest(
                            index,
                            id,
                            false,
                            archive.compressedSize,
                            archive.crc,
                            archive.version
                        )
                    )
                    updatesRequired++
                    return@forEach
                }

                existing.position(existing.capacity() - 2)
                val version = existing.short.toInt() and 0xffff
                if (version != archive.version and 0xffff) {
                    requests.add(
                        Js5RequestHandler.ArchiveRequest(
                            index,
                            id,
                            false,
                            archive.compressedSize,
                            archive.crc,
                            archive.version
                        )
                    )
                    updatesRequired++
                    return@forEach
                }
            } finally {
                progress += archive.compressedSize
            }
        }

        if (requests.isNotEmpty()) {
            this.requests?.request(requests)
            requests.clear()
        }

        completed = true
        logger.info { "Updated required for index $index: $updatesRequired (out of ${table.archives.size}), took ${System.currentTimeMillis() - start}ms" }
    }
}

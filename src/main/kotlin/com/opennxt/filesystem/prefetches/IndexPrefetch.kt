package com.opennxt.filesystem.prefetches

import com.opennxt.filesystem.Filesystem
import mu.KotlinLogging

class IndexPrefetch(private val index: Int) : Prefetch {
    private val logger = KotlinLogging.logger { }

    override fun calculateValue(store: Filesystem): Int {
        val buf = try {
            store.readReferenceTable(index)
        } catch (e: IndexOutOfBoundsException) {
            null
        }
        if (buf == null) {
            logger.warn { "Index $index is not present in this cache - prefetch value 0." }
            return 0
        }

        val table = store.getReferenceTable(index)
        if (table == null) {
            logger.warn { "Index $index has no readable reference table - prefetch value 0." }
            return 0
        }

        var value = 0
        if (table.mask and 0x4 != 0) {
            value += table.totalCompressedSize().toInt()
        } else {
            for (entry in table.archives.keys) {
                val archive = store.read(index, entry)
                if (archive == null) {
                    logger.warn { "Index $index archive $entry is missing - skipped in prefetch." }
                    continue
                }
                value += archive.capacity() - 2
            }
        }

        return value + buf.capacity()
    }
}

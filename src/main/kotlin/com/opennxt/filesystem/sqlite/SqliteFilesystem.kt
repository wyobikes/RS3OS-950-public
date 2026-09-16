package com.opennxt.filesystem.sqlite

import com.opennxt.ext.toFilesystemHash
import com.opennxt.filesystem.Container
import com.opennxt.filesystem.Filesystem
import mu.KotlinLogging
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.CRC32

class SqliteFilesystem(path: Path) : Filesystem(path) {
    val logger = KotlinLogging.logger { }

    var indices: Array<SqliteIndexFile?>

    init {
        if (!Files.exists(path))
            Files.createDirectories(path)

        logger.info { "Opening SQLite filesystem from $path" }

        val found = sortedSetOf<Int>()
        Files.newDirectoryStream(path, "js5-*.jcache").use { stream ->
            stream.forEach {
                val id = it.fileName.toString()
                    .removePrefix("js5-").removeSuffix(".jcache").toIntOrNull()
                if (id != null && id in 0..254) found.add(id)
            }
        }

        if (found.isEmpty()) {
            logger.warn { "No js5-*.jcache files found in $path - filesystem is empty." }
            indices = arrayOfNulls(0)
        } else {
            val highest = found.last()
            logger.info { "Discovered ${found.size} indices (highest $highest): ${found.joinToString(" ")}" }
            if (found.size != highest + 1) {
                logger.info {
                    "Cache is sparse - ${highest + 1 - found.size} of ${highest + 1} slots absent. " +
                            "Normal for a partially downloaded cache; gaps left unmapped."
                }
            }
            indices = Array(highest + 1) { i ->
                if (i !in found) return@Array null
                val file = SqliteIndexFile(path.resolve("js5-$i.jcache"))
                logger.trace("Loading index $i, contains data: ${file.hasReferenceTable()} with max archive: ${file.getMaxArchive()}")
                file
            }
        }
    }

    override fun createIndex(id: Int) {
        if (id < 0 || id > 254) {
            throw IllegalArgumentException("index id out of range: $id (valid 0..254)")
        }

        if (id < indices.size && indices[id] != null) return

        if (id >= indices.size) {
            val tmp = indices
            indices = Array(id + 1) { if (it < tmp.size) tmp[it] else null }
        }

        indices[id] = SqliteIndexFile(path.resolve("js5-$id.jcache"))
    }

    override fun exists(index: Int, archive: Int): Boolean {
        if (index < 0 || index >= indices.size) return false

        return indices[index]?.exists(archive) ?: false
    }

    override fun read(index: Int, archive: Int): ByteBuffer? {
        if (index < 0 || index >= indices.size) return null

        return ByteBuffer.wrap(indices[index]?.getRaw(archive) ?: return null)
    }

    override fun read(index: Int, name: String): ByteBuffer? {
        val table = getReferenceTable(index) ?: return null
        val hash = name.toFilesystemHash()
        val id = (table.archives.entries.firstOrNull { it.value.name == hash } ?: return null).key
        return read(index, id)
    }

    override fun readReferenceTable(index: Int): ByteBuffer? {
        if (index < 0 || index >= indices.size) return null

        return ByteBuffer.wrap(indices[index]?.getRawTable() ?: return null)
    }

    override fun write(index: Int, archive: Int, data: Container) {
        if (index < 0 || index >= indices.size) throw IndexOutOfBoundsException("index out of bounds: $index")

        val compressed = data.compress().array()
        val crc = CRC32()
        crc.update(compressed, 0, compressed.size - 2)
        write(index, archive, compressed, data.version, crc.value.toInt())
    }

    override fun write(index: Int, archive: Int, compressed: ByteArray, version: Int, crc: Int) {
        if (index < 0 || index >= indices.size) throw IndexOutOfBoundsException("index out of bounds: $index")

        indices[index]?.putRaw(archive, compressed, version, crc)
    }

    override fun writeReferenceTable(index: Int, data: Container) {
        if (index < 0 || index >= indices.size) throw IndexOutOfBoundsException("index out of bounds: $index")

        if (data.version == -1) data.version = 100
        val compressed = data.compress().array()
        val crc = CRC32()
        crc.update(compressed, 0, compressed.size - 2)
        writeReferenceTable(index, compressed, data.version, crc.value.toInt())
    }

    override fun writeReferenceTable(index: Int, compressed: ByteArray, version: Int, crc: Int) {
        if (index < 0 || index >= indices.size) throw IndexOutOfBoundsException("index out of bounds: $index")

        indices[index]?.putRawTable(compressed, version, crc)
    }

    override fun numIndices(): Int = indices.size

}

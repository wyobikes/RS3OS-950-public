package com.opennxt.model.map

import com.opennxt.Constants
import com.opennxt.filesystem.Index
import com.opennxt.filesystem.sqlite.SqliteFilesystem
import mu.KotlinLogging
import java.nio.ByteBuffer
import java.util.Collections

object BridgeFlags {
    private val logger = KotlinLogging.logger { }

    private const val CACHE_SIZE = 256
    private val settingsCache = Collections.synchronizedMap(
        object : LinkedHashMap<Int, ByteArray?>(CACHE_SIZE, 0.75f, true) {
            override fun removeEldestEntry(eldest: Map.Entry<Int, ByteArray?>) = size > CACHE_SIZE
        }
    )

    @Volatile
    private var filesystem: SqliteFilesystem? = null

    @Volatile
    private var warnedNoCache = false

    @Volatile
    var bridgeTilesLoaded = 0L
        private set

    fun init(fs: SqliteFilesystem) {
        filesystem = fs
        logger.info { "BridgeFlags: initialized with cache at ${Constants.CACHE_PATH}" }
    }

    fun effectivePlane(x: Int, z: Int, plane: Int): Int {
        if (plane >= 3) return plane
        val settings = settingsAt(x, z, plane + 1) ?: return plane
        return if ((settings.toInt() and 0x2) != 0) plane + 1 else plane
    }

    fun isBridge(x: Int, z: Int, plane: Int): Boolean {
        val settings = settingsAt(x, z, plane) ?: return false
        return (settings.toInt() and 0x2) != 0
    }

    private fun settingsAt(x: Int, z: Int, plane: Int): Byte? {
        if (plane < 0 || plane > 3) return null
        val squareX = x / 64
        val squareZ = z / 64
        val squareId = (squareZ shl 7) or squareX
        val data = loadSettings(squareId) ?: return null
        val lx = x % 64
        val lz = z % 64
        val idx = (plane * 64 + lx) * 64 + lz
        if (idx < 0 || idx >= data.size) return null
        return data[idx]
    }

    private fun loadSettings(squareId: Int): ByteArray? {
        synchronized(settingsCache) {
            if (settingsCache.containsKey(squareId)) return settingsCache[squareId]
        }
        val data = decodeFromCache(squareId)
        settingsCache[squareId] = data
        return data
    }

    private fun decodeFromCache(squareId: Int): ByteArray? {
        val fs = filesystem
        if (fs == null) {
            if (!warnedNoCache) {
                warnedNoCache = true
                logger.warn { "BridgeFlags: no filesystem - bridges will not remap planes" }
            }
            return null
        }
        val table = try { fs.getReferenceTable(Index.MAPS) } catch (e: Exception) { return null }
            ?: return null
        val arc = try { table.loadArchive(squareId) } catch (e: Exception) { return null }
        val rawData = arc?.files?.get(3)?.data ?: return null

        val buf = ByteBuffer.wrap(rawData)
        val wide = rawData.size >= 5 &&
            rawData[0] == 'j'.code.toByte() &&
            rawData[1] == 'a'.code.toByte() &&
            rawData[2] == 'g'.code.toByte() &&
            rawData[3] == 'x'.code.toByte() &&
            rawData[4] == 0x01.toByte()
        if (wide) buf.position(buf.position() + 5)

        val settings = ByteArray(4 * 64 * 64)
        var bridges = 0
        try {
            for (plane in 0 until 4) {
                for (x in 0 until 64) {
                    for (y in 0 until 64) {
                        if (buf.remaining() <= 0) return null
                        val flags = buf.get().toInt() and 0xff
                        val i = (plane * 64 + x) * 64 + y
                        if ((flags and 0x1) != 0) {
                            if (buf.remaining() < 1) return null
                            buf.get()
                            if (buf.remaining() < 1) return null
                            val peek = buf.get(buf.position()).toInt() and 0xff
                            if (peek >= 0x80) {
                                if (buf.remaining() < 2) return null
                                buf.short
                            } else {
                                buf.get()
                            }
                        }
                        if ((flags and 0x2) != 0) {
                            if (buf.remaining() < 1) return null
                            val s = buf.get()
                            settings[i] = s
                            if ((s.toInt() and 0x2) != 0) bridges++
                        }
                        if ((flags and 0x4) != 0) {
                            if (buf.remaining() < 1) return null
                            val peek = buf.get(buf.position()).toInt() and 0xff
                            if (peek >= 0x80) {
                                if (buf.remaining() < 2) return null
                                buf.short
                            } else {
                                buf.get()
                            }
                        }
                        if ((flags and 0x8) != 0) {
                            if (wide) {
                                if (buf.remaining() < 2) return null
                                buf.short
                            } else {
                                if (buf.remaining() < 1) return null
                                buf.get()
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            logger.warn { "BridgeFlags: failed to decode square $squareId: ${e.message}" }
            return null
        }
        if (bridges > 0) bridgeTilesLoaded += bridges
        return settings
    }

    fun loadedSquares(): Int = settingsCache.size
}

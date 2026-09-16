package com.opennxt.model.entity.rendering.blocks

import com.opennxt.model.entity.Entity
import com.opennxt.net.buf.DataType
import com.opennxt.net.buf.GamePacketBuilder
import java.util.Collections
import java.util.IdentityHashMap

class PlayerSpotanimBlock950 private constructor(private val bytes: ByteArray) {
    fun encode950(buffer: GamePacketBuilder) {
        for (b in bytes) buffer.put(DataType.BYTE, b.toInt() and 0xff)
    }

    fun length(): Int = bytes.size

    fun bytes(): ByteArray = bytes.copyOf()

    companion object {
        const val MASK_BIT_950 = 26

        const val LEVEL_UP_SPOTANIM = 2457

        private val LEVEL_UP_BYTES: ByteArray = (
            "0003" +
                "80" + "1909" + "0000e600" + "78" + "fffb5f" +
                "7f" + "1909" + "0a000e01" + "7b" + "fffb5f" +
                "7e" + "1909" + "1e002c01" + "7e" + "fffb5f"
            ).chunked(2).map { it.toInt(16).toByte() }.toByteArray()

        val LEVEL_UP = PlayerSpotanimBlock950(LEVEL_UP_BYTES)

        fun single(spotanimId: Int): PlayerSpotanimBlock950 {
            require(spotanimId in 0..0xfffe) { "spotanim $spotanimId is not a u16 id (0xffff is the clear)" }
            val lo = ((spotanimId and 0xff) + 0x80) and 0xff
            val hi = (spotanimId ushr 8) and 0xff
            val bytes = byteArrayOf(0x00, 0x01, 0x80.toByte(), lo.toByte(), hi.toByte(), 0, 0, 0, 0, 0x78, 0xff.toByte(), 0xfb.toByte(), 0x5f)
            return PlayerSpotanimBlock950(bytes)
        }

        private val pending: MutableMap<Entity, PlayerSpotanimBlock950> = Collections.synchronizedMap(IdentityHashMap())

        @Volatile
        private var queuedCount = 0

        fun queue(entity: Entity, block: PlayerSpotanimBlock950) {
            pending[entity] = block
            queuedCount++
        }

        fun pendingFor(entity: Entity): PlayerSpotanimBlock950? = pending[entity]

        fun clear(entity: Entity) {
            pending.remove(entity)
        }

        fun queuedCount(): Int = queuedCount
    }
}

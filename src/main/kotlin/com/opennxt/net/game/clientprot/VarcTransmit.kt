package com.opennxt.net.game.clientprot

import com.opennxt.net.buf.GamePacketBuilder
import com.opennxt.net.buf.GamePacketReader
import com.opennxt.net.game.GamePacket
import com.opennxt.net.game.pipeline.GamePacketCodec
import com.opennxt.resources.config.vars.BaseVarType
import com.opennxt.resources.config.vars.ScriptVarType
import com.opennxt.resources.sqlite.RsDatabase
import mu.KotlinLogging

class VarcTransmit(val allSent: Int, val values: Map<Int, Any>) : GamePacket {
    object Codec : GamePacketCodec<VarcTransmit> {
        private val logger = KotlinLogging.logger { }

        private val baseTypes: Map<Int, BaseVarType> by lazy {
            if (!RsDatabase.available) {
                logger.error { "VARC_TRANSMIT: rs3.sqlite unavailable, varc types unknown" }
                return@lazy emptyMap()
            }
            val out = HashMap<Int, BaseVarType>()
            RsDatabase.queryAll("SELECT id, type FROM vars_client") { rs ->
                rs.getInt("id") to rs.getInt("type")
            }.forEach { (id, type) ->
                ScriptVarType.values().firstOrNull { it.id == type }?.let { out[id] = it.type }
            }
            logger.info { "VARC_TRANSMIT: ${out.size} varc types read from vars_client" }
            out
        }

        override fun decode(buf: GamePacketReader): VarcTransmit {
            val b = buf.buffer
            val allSent = b.readUnsignedByte().toInt()
            val values = LinkedHashMap<Int, Any>()
            while (b.readableBytes() >= 2) {
                val id = b.readUnsignedShort()
                when (baseTypes[id]) {
                    BaseVarType.INTEGER -> {
                        if (b.readableBytes() < 4) break
                        values[id] = b.readInt()
                    }
                    BaseVarType.LONG -> {
                        if (b.readableBytes() < 8) break
                        values[id] = b.readLong()
                    }
                    BaseVarType.STRING -> values[id] = buf.getString()
                    else -> {
                        logger.warn {
                            "VARC_TRANSMIT: varc $id has no known type (${baseTypes[id]}); stopped after " +
                                "${values.size} entry(s) with ${b.readableBytes()} byte(s) unread"
                        }
                        b.skipBytes(b.readableBytes())
                        break
                    }
                }
            }
            return VarcTransmit(allSent, values)
        }

        override fun encode(packet: VarcTransmit, buf: GamePacketBuilder) {
            buf.put(com.opennxt.net.buf.DataType.BYTE, packet.allSent.toLong())
            for ((id, v) in packet.values) {
                buf.put(com.opennxt.net.buf.DataType.SHORT, id.toLong())
                when (v) {
                    is Int -> buf.put(com.opennxt.net.buf.DataType.INT, v.toLong())
                    is Long -> buf.put(com.opennxt.net.buf.DataType.LONG, v)
                    is String -> buf.putString(v)
                    else -> error("VARC_TRANSMIT: cannot encode ${v.javaClass.simpleName} for varc $id")
                }
            }
        }
    }

    override fun toString(): String =
        "VarcTransmit(allSent=$allSent, ${values.size} varc(s)" +
            (values.entries.take(3).joinToString(prefix = ": ", postfix = if (values.size > 3) ", ..." else "") {
                "${it.key}=${it.value}"
            }) + ")"
}

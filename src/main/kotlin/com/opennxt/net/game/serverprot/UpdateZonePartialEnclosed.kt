package com.opennxt.net.game.serverprot

import com.opennxt.model.entity.rendering.UpdateBlockType
import com.opennxt.net.buf.DataType
import com.opennxt.net.buf.GamePacketBuilder
import com.opennxt.net.buf.GamePacketReader
import com.opennxt.net.game.GamePacket
import com.opennxt.net.game.pipeline.GamePacketCodec
import com.opennxt.net.game.protocol.PacketFieldDeclaration

data class UpdateZonePartialEnclosed(
    val plane: Int,
    val zoneX: Int,
    val zoneY: Int,
    val subPackets: List<ZoneSubPacket>
) : GamePacket {
    init {
        require(plane in 0..3) { "plane must be 0..3, was $plane" }
        require(zoneX in -128..127 && zoneY in -128..127) { "zone index does not fit a signed byte: ($zoneX,$zoneY)" }
    }

    object Codec : GamePacketCodec<UpdateZonePartialEnclosed> {
        private const val NAME = "UPDATE_ZONE_PARTIAL_ENCLOSED"

        private val header: Array<PacketFieldDeclaration> by lazy {
            ZoneSubProtocol950.declaration(NAME).also { h ->
                require(h.map { it.key } == listOf("plane", "zonex", "zonez")) {
                    "$NAME.txt header fields must be plane/zonex/zonez, got ${h.map { it.key }}"
                }
            }
        }

        @Suppress("UNCHECKED_CAST")
        private fun PacketFieldDeclaration.write(buf: GamePacketBuilder, v: Int) =
            (dataCodec as PacketFieldDeclaration.DataCodec<Any>).write(buf, v)

        override fun encode(packet: UpdateZonePartialEnclosed, buf: GamePacketBuilder) {
            require(UpdateBlockType.experimentalBuild()) {
                "$NAME is only encodable on build 950"
            }
            encodeUnchecked(packet, buf)
        }

        fun encodeUnchecked(packet: UpdateZonePartialEnclosed, buf: GamePacketBuilder) {
            header[0].write(buf, packet.plane)
            header[1].write(buf, packet.zoneX)
            header[2].write(buf, packet.zoneY)
            for (sub in packet.subPackets) {
                buf.put(DataType.BYTE, sub.subOpcode)
                when (sub) {
                    is ZoneSubPacket.Raw -> {
                        when (sub.lengthPrefix) {
                            0 -> {}
                            1 -> buf.put(DataType.BYTE, sub.bytes.size)
                            2 -> buf.put(DataType.SHORT, sub.bytes.size)
                            else -> error("length prefix width ${sub.lengthPrefix}")
                        }
                        buf.putBytes(sub.bytes)
                    }
                    is ZoneSubPacket.Declared -> {
                        val decl = ZoneSubProtocol950.declaration(sub.name)
                        val map = sub.fields()
                        for (f in decl) {
                            val v = map[f.key] ?: throw IllegalStateException("${sub.name}: field '${f.key}' missing")
                            f.write(buf, v)
                        }
                    }
                }
            }
        }

        override fun decode(buf: GamePacketReader): UpdateZonePartialEnclosed {
            val plane = header[0].dataCodec.read(buf) as Int
            val zoneX = header[1].dataCodec.read(buf) as Int
            val zoneY = header[2].dataCodec.read(buf) as Int
            val subs = ArrayList<ZoneSubPacket>()
            while (buf.buffer.readableBytes() > 0) {
                val sub = buf.getUnsigned(DataType.BYTE).toInt()
                val name = ZoneSubProtocol950.NAME_OF[sub]
                if (name != null) {
                    val decl = ZoneSubProtocol950.declaration(name)
                    val map = LinkedHashMap<String, Int>()
                    for (f in decl) map[f.key] = f.dataCodec.read(buf) as Int
                    subs += ZoneSubPacket.Declared.fromMap(name, map)
                } else {
                    val size = ZoneSubProtocol950.sizes[sub]
                        ?: throw IllegalStateException("zone sub-opcode $sub is outside the 18-entry table")
                    val (prefix, n) = when {
                        size >= 0 -> 0 to size
                        size == -1 -> 1 to buf.getUnsigned(DataType.BYTE).toInt()
                        size == -2 -> 2 to buf.getUnsigned(DataType.SHORT).toInt()
                        else -> error("size class $size")
                    }
                    require(buf.buffer.readableBytes() >= n) { "zone sub-opcode $sub: $n bytes declared, ${buf.buffer.readableBytes()} left" }
                    val bytes = ByteArray(n); buf.getBytes(bytes)
                    subs += ZoneSubPacket.Raw(sub, bytes, prefix)
                }
            }
            return UpdateZonePartialEnclosed(plane, zoneX, zoneY, subs)
        }
    }
}

sealed class ZoneSubPacket {
    abstract val subOpcode: Int

    class Raw(override val subOpcode: Int, val bytes: ByteArray, val lengthPrefix: Int = 0) : ZoneSubPacket() {
        override fun equals(other: Any?) = other is Raw && other.subOpcode == subOpcode &&
            other.lengthPrefix == lengthPrefix && other.bytes.contentEquals(bytes)
        override fun hashCode() = subOpcode * 31 + bytes.contentHashCode()
        override fun toString() = "Raw(sub=$subOpcode, ${bytes.size} bytes)"
    }

    sealed class Declared(val name: String) : ZoneSubPacket() {
        override val subOpcode: Int get() = ZoneSubProtocol950.SUB_OPCODES.getValue(name)
        abstract fun fields(): Map<String, Int>

        companion object {
            fun fromMap(name: String, m: Map<String, Int>): Declared = when (name) {
                "MAP_PROJANIM" -> MapProjanim(
                    coord = m.getValue("coord"), deltax = m.getValue("deltax"), deltaz = m.getValue("deltaz"),
                    target = m.getValue("target"), spotanim = m.getValue("spotanim"),
                    startheight = m.getValue("startheight"), endheight = m.getValue("endheight"),
                    startdelay = m.getValue("startdelay"), enddelay = m.getValue("enddelay"),
                    angle = m.getValue("angle"), startdistance = m.getValue("startdistance"), unread = m.getValue("unread")
                )
                "MAP_PROJANIM_HALFSQ_949" -> MapProjanimHalfsq949(
                    coord = m.getValue("coord"), flags = m.getValue("flags"), deltax = m.getValue("deltax"),
                    deltaz = m.getValue("deltaz"), source = m.getValue("source"), target = m.getValue("target"),
                    spotanim = m.getValue("spotanim"), startheight = m.getValue("startheight"),
                    endheight = m.getValue("endheight"), startdelay = m.getValue("startdelay"),
                    enddelay = m.getValue("enddelay"), angle = m.getValue("angle"),
                    startdistance = m.getValue("startdistance"), subtilesrc = m.getValue("subtilesrc"),
                    subtiledst = m.getValue("subtiledst")
                )
                "MAP_ANIM" -> MapAnim(
                    coord = m.getValue("coord"), spotanim = m.getValue("spotanim"), height = m.getValue("height"),
                    delay = m.getValue("delay"), rotation = m.getValue("rotation"), unread = m.getValue("unread")
                )
                else -> throw IllegalArgumentException("no typed zone sub-packet for $name")
            }
        }
    }

    data class MapProjanim(
        val coord: Int, val deltax: Int, val deltaz: Int, val target: Int, val spotanim: Int,
        val startheight: Int, val endheight: Int, val startdelay: Int, val enddelay: Int,
        val angle: Int, val startdistance: Int, val unread: Int = ZoneSubProtocol950.UNREAD_TAIL
    ) : Declared("MAP_PROJANIM") {
        override fun fields() = mapOf(
            "coord" to coord, "deltax" to deltax, "deltaz" to deltaz, "target" to target, "spotanim" to spotanim,
            "startheight" to startheight, "endheight" to endheight, "startdelay" to startdelay, "enddelay" to enddelay,
            "angle" to angle, "startdistance" to startdistance, "unread" to unread
        )
    }

    data class MapProjanimHalfsq949(
        val coord: Int, val flags: Int, val deltax: Int, val deltaz: Int, val source: Int, val target: Int,
        val spotanim: Int, val startheight: Int, val endheight: Int, val startdelay: Int, val enddelay: Int,
        val angle: Int, val startdistance: Int,
        val subtilesrc: Int = ZoneSubProtocol950.TRIPLE_NEUTRAL, val subtiledst: Int = ZoneSubProtocol950.TRIPLE_NEUTRAL
    ) : Declared("MAP_PROJANIM_HALFSQ_949") {
        override fun fields() = mapOf(
            "coord" to coord, "flags" to flags, "deltax" to deltax, "deltaz" to deltaz, "source" to source,
            "target" to target, "spotanim" to spotanim, "startheight" to startheight, "endheight" to endheight,
            "startdelay" to startdelay, "enddelay" to enddelay, "angle" to angle, "startdistance" to startdistance,
            "subtilesrc" to subtilesrc, "subtiledst" to subtiledst
        )
    }

    data class MapAnim(
        val coord: Int, val spotanim: Int, val height: Int, val delay: Int, val rotation: Int,
        val unread: Int = ZoneSubProtocol950.UNREAD_TAIL
    ) : Declared("MAP_ANIM") {
        override fun fields() = mapOf(
            "coord" to coord, "spotanim" to spotanim, "height" to height, "delay" to delay,
            "rotation" to rotation, "unread" to unread
        )
        companion object {
            const val REMOVE = 0xffff
        }
    }
}

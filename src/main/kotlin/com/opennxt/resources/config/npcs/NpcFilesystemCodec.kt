package com.opennxt.resources.config.npcs

import com.opennxt.ext.getMedium
import com.opennxt.ext.getSmallSmartInt
import com.opennxt.ext.getSmartInt
import com.opennxt.ext.getString
import com.opennxt.ext.skip
import com.opennxt.filesystem.Filesystem
import com.opennxt.resources.ResourceType
import com.opennxt.resources.config.shared.ModelSwapBlock
import java.nio.ByteBuffer

object NpcFilesystemCodec {
    private const val INDEX = 18
    private const val SHIFT = 7

    fun defaultBuild950(): Boolean =
        com.opennxt.config.ServerConfig.experimentalBuildOverride() == 950 ||
            com.opennxt.model.entity.rendering.UpdateBlockType.experimentalBuild()

    fun detectBuild950(fs: Filesystem, limit: Int = 2000): Boolean? {
        val max = minOf(getMaxId(fs), limit)
        for (id in 0..max) {
            val data = raw(fs, id) ?: continue
            val as949 = runCatching { decode(id, ByteBuffer.wrap(data), build950 = false) }.isSuccess
            val as950 = runCatching { decode(id, ByteBuffer.wrap(data), build950 = true) }.isSuccess
            if (as949 != as950) return as950
        }
        return null
    }

    fun getMaxId(fs: Filesystem): Int {
        val table = fs.getReferenceTable(INDEX) ?: return 0
        val maxArchive = table.highestEntry() - 1
        val files = table.archives[maxArchive]!!.files.lastKey()
        return maxArchive * (1 shl SHIFT) + files
    }

    fun raw(fs: Filesystem, id: Int): ByteArray? {
        val table = fs.getReferenceTable(INDEX) ?: return null
        val archive = table.loadArchive(ResourceType.getArchive(id, SHIFT)) ?: return null
        return archive.files[ResourceType.getFile(id, SHIFT)]?.data
    }

    fun load(fs: Filesystem, id: Int, build950: Boolean = defaultBuild950()): NpcRecord? {
        val data = raw(fs, id) ?: return null
        return decode(id, ByteBuffer.wrap(data), build950)
    }

    fun decode(id: Int, buf: ByteBuffer, build950: Boolean = defaultBuild950()): NpcRecord =
        decodeInto(NpcRecord(id), buf, build950)

    fun decodeInto(r: NpcRecord, buf: ByteBuffer, build950: Boolean = defaultBuild950()): NpcRecord {
        while (buf.hasRemaining()) {
            val op = buf.get().toInt() and 0xff
            if (op == 0) return r
            read(r, op, buf, build950)
            r.trail.add(op)
        }
        return r
    }

    private fun ByteBuffer.bigSmart(): Int = getSmartInt()

    private fun ByteBuffer.signedSmart(): Int =
        if ((get(position()).toInt() and 0x80) != 0) (short.toInt() and 0x7fff) - 0x4000
        else (get().toInt() and 0xff) - 0x40

    private fun readMorph(buf: ByteBuffer, twoSelectors: Boolean, extraByte: Boolean): Morph {
        val m = Morph()
        if (extraByte) m.leading950 = buf.get().toInt() and 0xff
        m.varbit = buf.short.toInt() and 0xffff
        m.varp = buf.short.toInt() and 0xffff
        if (twoSelectors) m.extra = buf.short.toInt() and 0xffff
        val n = buf.getSmallSmartInt()
        m.options = IntArray(n) { buf.short.toInt() and 0xffff }
        m.default = buf.short.toInt() and 0xffff
        m.trailing = buf.short.toInt() and 0xffff
        return m
    }

    private fun wrongBuild(r: NpcRecord, op: Int, buf: ByteBuffer, opBuild: Int): Nothing =
        throw IllegalStateException(
            "npc ${r.id}: opcode $op at ${buf.position() - 1} belongs to build $opBuild, " +
                "and this record is being read as build ${if (opBuild == 949) 950 else 949}")

    private fun read(r: NpcRecord, op: Int, buf: ByteBuffer, build950: Boolean) {
        when (op) {
            1 -> r.models = IntArray(buf.get().toInt() and 0xff) { buf.bigSmart() }
            2 -> r.name = buf.getString()
            11 -> r.unknown[op] = buf.get().toInt() and 0xff
            12 -> r.boundSize = buf.get().toInt() and 0xff
            in 30..34 -> r.actions[op - 30] = buf.getString()
            in 35..39 -> r.membersActions[op - 35] = buf.getString()
            40 -> repeat(buf.get().toInt() and 0xff) {
                r.colourReplacements.add((buf.short.toInt() and 0xffff) to (buf.short.toInt() and 0xffff))
            }
            41 -> repeat(buf.get().toInt() and 0xff) {
                r.materialReplacements.add((buf.short.toInt() and 0xffff) to (buf.short.toInt() and 0xffff))
            }
            42 -> { val n = buf.get().toInt() and 0xff; buf.skip(n) }
            44, 45 -> buf.skip(2)
            60 -> r.headModels = IntArray(buf.get().toInt() and 0xff) { buf.bigSmart() }
            93 -> r.drawMapDot = false
            95 -> r.combat = buf.short.toInt() and 0xffff
            97 -> r.scaleXZ = buf.short.toInt() and 0xffff
            98 -> r.scaleY = buf.short.toInt() and 0xffff
            99 -> r.unknown[op] = 1
            100 -> r.ambience = buf.get().toInt()
            101 -> r.contrast = buf.get().toInt()
            102 -> r.headIconData = buf.short.toInt() and 0xffff
            103 -> r.unknown[op] = buf.short.toInt() and 0xffff
            106, 118 -> {
                if (build950) wrongBuild(r, op, buf, 949)
                val m = readMorph(buf, twoSelectors = op == 118, extraByte = false)
                if (op == 106) r.morph1 = m else r.morph2 = m
            }
            187, 188 -> {
                if (!build950) wrongBuild(r, op, buf, 950)
                val m = readMorph(buf, twoSelectors = op == 188, extraByte = true)
                if (op == 187) r.morph1 = m else r.morph2 = m
            }
            107 -> r.unknown[op] = 1
            109 -> r.slowWalk = false
            111 -> r.unknown[op] = 1
            113 -> buf.skip(4)
            114 -> buf.skip(2)
            119 -> r.movementCapabilities = buf.get().toInt()
            121 -> { val n = buf.get().toInt() and 0xff; buf.skip(n * 4) }
            122 -> buf.bigSmart()
            123 -> buf.skip(2)
            125 -> buf.skip(1)
            127 -> r.animationGroup = buf.short.toInt() and 0xffff
            128 -> r.movementType = buf.get().toInt() and 0xff
            134 -> r.ambientSound = IntArray(5) {
                if (it == 4) buf.get().toInt() and 0xff else buf.short.toInt() and 0xffff
            }
            135, 136 -> { buf.skip(1); buf.skip(2) }
            137 -> r.attackCursor = buf.short.toInt() and 0xffff
            140 -> buf.skip(1)
            141 -> r.unknown[op] = 1
            142 -> buf.skip(2)
            143 -> r.unknown[op] = 1
            in 150..154 -> r.membersActions[op - 150] = buf.getString()
            155 -> buf.skip(4)
            158, 159 -> r.unknown[op] = 1
            160 -> { val n = buf.get().toInt() and 0xff; buf.skip(n * 2) }
            162 -> r.unknown[op] = 1
            163 -> buf.skip(1)
            164 -> buf.skip(4)
            165 -> buf.skip(1)
            168 -> buf.skip(1)
            169 -> r.unknown[op] = 1
            in 170..174 -> r.actionCursors[op - 170] = buf.short.toInt() and 0xffff
            175 -> buf.skip(2)
            178 -> r.unknown[op] = 1
            183 -> r.unknown[op] = buf.get().toInt() and 0xff
            179 -> repeat(6) { buf.signedSmart() }
            180 -> buf.skip(2)
            182 -> r.unknown[op] = 1
            184 -> r.unknown[op] = buf.get().toInt() and 0xff
            185 -> r.unknown[op] = 1
            186 -> {
                if (build950) wrongBuild(r, op, buf, 949)
                r.modelSwapAt = buf.position()
                r.modelSwapLength = with(ModelSwapBlock) { buf.readModelSwapBlock(build950 = false) }
            }
            189 -> {
                if (!build950) wrongBuild(r, op, buf, 950)
                r.modelSwapAt = buf.position()
                r.modelSwapLength = with(ModelSwapBlock) { buf.readModelSwapBlock(build950 = true) }
            }
            219 -> r.unknown[op] = buf.get().toInt() and 0xff
            249 -> {
                val n = buf.get().toInt() and 0xff
                repeat(n) {
                    val isString = buf.get().toInt() == 1
                    val prop = buf.getMedium()
                    r.params.add(prop to (if (isString) buf.getString() else buf.int))
                }
            }
            253 -> buf.skip(1)
            else -> throw IllegalStateException("npc $${r.id}: unknown opcode $op at ${buf.position() - 1}")
        }
    }
}

class NpcRecord(val id: Int) {
    var name: String? = null
    var boundSize: Int? = null
    var combat: Int? = null
    var movementType: Int? = null
    var movementCapabilities: Int? = null
    var animationGroup: Int? = null
    var attackCursor: Int? = null
    var scaleXZ: Int? = null
    var scaleY: Int? = null
    var ambience: Int? = null
    var contrast: Int? = null
    var drawMapDot: Boolean? = null
    var slowWalk: Boolean? = null
    var headIconData: Int? = null
    var ambientSound: IntArray? = null
    var morph1: Morph? = null
    var morph2: Morph? = null
    var modelSwapLength: Int? = null

    var modelSwapAt: Int? = null
    val colourReplacements = ArrayList<Pair<Int, Int>>()
    val materialReplacements = ArrayList<Pair<Int, Int>>()
    var models: IntArray? = null
    var headModels: IntArray? = null
    val actions = arrayOfNulls<String>(5)
    val membersActions = arrayOfNulls<String>(5)
    val actionCursors = arrayOfNulls<Int>(5)
    val params = ArrayList<Pair<Int, Any>>()
    val unknown = LinkedHashMap<Int, Int>()

    val trail = ArrayList<Int>()
}

class Morph {
    var leading950: Int? = null
    var varbit: Int = 0
    var varp: Int = 0
    var extra: Int? = null
    var options: IntArray = IntArray(0)
    var default: Int = 0
    var trailing: Int = 0
}

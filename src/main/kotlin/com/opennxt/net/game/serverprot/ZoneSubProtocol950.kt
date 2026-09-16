package com.opennxt.net.game.serverprot

import com.opennxt.Constants
import com.opennxt.net.game.protocol.PacketFieldDeclaration
import java.nio.file.Files
import java.nio.file.Path

object ZoneSubProtocol950 {
    const val BUILD = 950

    val SUB_OPCODES: Map<String, Int> = linkedMapOf(
        "MAP_ANIM" to 11,
        "MAP_PROJANIM" to 15,
        "MAP_PROJANIM_HALFSQ_949" to 17
    )

    val EXTRA_SUB_OPCODES: Map<String, Int> = linkedMapOf(
        "SOUND_AREA" to 4,
        "OBJ_ADD_949" to 8,
        "OBJ_COUNT_949" to 9,
        "OBJ_DEL_949" to 10,
        "LOC_DEL" to 12
    )

    val NAME_OF: Map<Int, String> = SUB_OPCODES.entries.associate { (n, op) -> op to n }

    private val WIDTH = mapOf(
        "ubyte" to 1, "ubyte128" to 1, "u128byte" to 1, "ubytec" to 1,
        "sbyte" to 1, "sbyte128" to 1, "s128byte" to 1, "sbytec" to 1,
        "ushort" to 2, "ushort128" to 2, "ushortle" to 2, "ushortle128" to 2,
        "umedium" to 3, "umediumle" to 3, "umediumx1" to 3, "umediumx2" to 3,
        "int" to 4, "intle" to 4, "intv1" to 4, "intv2" to 4
    )

    private val PROT: Path get() = Constants.PROT_PATH.resolve(BUILD.toString())
    private val TABLE: Path get() = PROT.resolve("loginserverprot_950.toml")

    val sizes: Map<Int, Int> by lazy { parseTable(TABLE) }

    const val ENTRIES = 18

    fun parseTable(path: Path): Map<Int, Int> {
        require(Files.isRegularFile(path)) { "zone sub-opcode table missing: $path" }
        val out = LinkedHashMap<Int, Int>()
        var inValues = false
        for (raw in Files.readAllLines(path)) {
            val line = raw.substringBefore('#').trim()
            if (line.isEmpty()) continue
            if (line.startsWith("[")) { inValues = line == "[values]"; continue }
            if (!inValues) continue
            val m = Regex("^(\\d+)\\s*=\\s*(-?\\d+)$").find(line)
                ?: throw IllegalStateException("$path: unparseable line '$raw'")
            out[m.groupValues[1].toInt()] = m.groupValues[2].toInt()
        }
        require(out.size == ENTRIES && (0 until ENTRIES).all { it in out }) {
            "$path: expected sub-opcodes 0..${ENTRIES - 1}, got ${out.keys.sorted()}"
        }
        return out
    }

    private val declarations = HashMap<String, Array<PacketFieldDeclaration>>()

    @Synchronized
    fun declaration(name: String): Array<PacketFieldDeclaration> = declarations.getOrPut(name) {
        val file = PROT.resolve("serverProt").resolve("$name.txt")
        require(Files.isRegularFile(file)) { "no build-$BUILD declaration for $name at $file" }
        val lines = Files.readAllLines(file)
            .mapIndexed { i, l -> (i + 1) to l }
            .filter { it.second.isNotBlank() && !it.second.trimStart().startsWith("#") }
        val fields = lines.map { (n, l) -> PacketFieldDeclaration.fromString(l, "$file:$n") }.toTypedArray()
        val width = lines.sumOf { (n, l) ->
            val type = l.trim().split(Regex("\\s+"))[1]
            WIDTH[type] ?: throw IllegalStateException("$file:$n: '$type' is not a fixed-width zone field type")
        }
        SUB_OPCODES[name]?.let { sub ->
            val size = sizes.getValue(sub)
            require(size == width) {
                "$name declares $width bytes but zone sub-opcode $sub is $size bytes in $TABLE"
            }
        }
        fields
    }

    fun width(name: String): Int = declaration(name).let { decl ->
        val file = PROT.resolve("serverProt").resolve("$name.txt")
        Files.readAllLines(file).filter { it.isNotBlank() && !it.trimStart().startsWith("#") }
            .sumOf { WIDTH.getValue(it.trim().split(Regex("\\s+"))[1]) }
            .also { require(it > 0 && decl.isNotEmpty()) }
    }

    fun projanimCoord(localX: Int, localZ: Int, flag: Boolean = false): Int {
        require(localX in 0..7 && localZ in 0..7) { "zone-local tile out of 0..7: ($localX,$localZ)" }
        return (if (flag) 0x80 else 0) or (localX shl 3) or localZ
    }

    fun projanimX(coord: Int) = (coord ushr 3) and 7
    fun projanimZ(coord: Int) = coord and 7

    fun mapAnimCoord(localX: Int, localZ: Int): Int {
        require(localX in 0..7 && localZ in 0..7) { "zone-local tile out of 0..7: ($localX,$localZ)" }
        return ZoneCoord.coord(localX, localZ)
    }

    fun mapAnimX(coord: Int) = (coord ushr 4) and 7
    fun mapAnimZ(coord: Int) = coord and 7

    fun halfsqCoord(xHalf: Int, zHalf: Int): Int {
        require(xHalf in 0..15 && zHalf in 0..15) { "half-square index out of 0..15: ($xHalf,$zHalf)" }
        return (xHalf shl 4) or zHalf
    }

    fun halfsqX(coord: Int) = (coord ushr 4) and 0xf
    fun halfsqZ(coord: Int) = coord and 0xf

    const val TRIPLE_NEUTRAL = 0x5ffbff

    fun packTriple(dx: Int, dz: Int, flag: Boolean): Int {
        require(dx in -0x3ff..0x400 && dz in -0x3ff..0x400) { "sub-tile delta out of 11 bits: ($dx,$dz)" }
        return ((dx + 0x3ff) and 0x7ff) or (((dz + 0x3ff) and 0x7ff) shl 11) or (if (flag) 0x400000 else 0)
    }

    fun tripleDx(v: Int) = (v and 0x7ff) - 0x3ff
    fun tripleDz(v: Int) = ((v ushr 11) and 0x7ff) - 0x3ff
    fun tripleFlag(v: Int) = (v.toLong() and 0xffc00000L) == 0x400000L

    const val KIND_NPC = 1
    const val KIND_PLAYER = 2
    const val TAG_NONE = 0x0a0000

    const val UNREAD_TAIL = 0xff0000

    fun npcTag(index: Int): Int { require(index in 0..0xffff); return (KIND_NPC shl 16) or index }
    fun playerTag(index: Int): Int { require(index in 0..0xffff); return (KIND_PLAYER shl 16) or index }
}

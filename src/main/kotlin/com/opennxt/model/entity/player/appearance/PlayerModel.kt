package com.opennxt.model.entity.player.appearance

import com.opennxt.model.entity.PlayerEntity
import com.opennxt.net.buf.DataType
import com.opennxt.net.buf.GamePacketBuilder
import com.opennxt.resources.FilesystemResources
import com.opennxt.resources.defaults.wearpos.WearposDefaults
import com.opennxt.util.MD5
import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled

class PlayerModel(val player: PlayerEntity) {
    private val CONVERSION_MAP = intArrayOf(0, 1, 2, 3, 4, 5, -1, 7, -1, 9, 10, -1, -1, -1, -1, -1, -1, -1, 18)

    var gender = Gender.MALE
    var renderType = RenderType.PLAYER

    var npcId = -1
    var showSkillLevel = false

    val idkit = intArrayOf(3, 14, 18, 26, 34, 38, 42)
    val colours = intArrayOf(3, 16, 16, 0, 0, 0, 0, 0, 0, 0)

    var data = ByteArray(0)
    var hash = ByteArray(0)

    var dirty = true

    private fun ByteBuf.toByteArray(): ByteArray {
        val readable = readableBytes()
        val out = ByteArray(readable)
        readBytes(out)
        return out
    }

    fun refresh() {
        dirty = false

        val buf = Unpooled.buffer()
        val builder = GamePacketBuilder(buf)

        var flags = 0x0
        if (gender == Gender.FEMALE) flags = flags or 0x1
        if (showSkillLevel) flags = flags or 0x4
        builder.put(DataType.BYTE, flags)

        builder.put(DataType.BYTE, 0)
        appendAppearance(builder)
        builder.putString(player.controllingPlayer?.name ?: "null")
        builder.put(DataType.BYTE, 3)
        if (showSkillLevel) {
            builder.put(DataType.SHORT, 1000)
        } else {
            builder.put(DataType.BYTE, 0)
            builder.put(DataType.BYTE, -1)
        }
        builder.put(DataType.BYTE, 0)

        this.data = buf.toByteArray()
        this.hash = MD5.hash(data)

        buf.release()
    }

    private fun getLookIndex(slot: Int): Int = lookIndexOf(slot)

    fun kitSlotValue(index: Int): Int {
        val lookIndex = getLookIndex(index)
        return if (lookIndex != -1 && idkit[lookIndex] > 0) KIT_BIAS + idkit[lookIndex] else SLOT_EMPTY
    }

    private fun putVarInt(builder: GamePacketBuilder, value: Int) {
        require(value >= 0) { "varint value must be non-negative, was $value" }
        var v = value
        while (v > 0x7f) {
            builder.put(DataType.BYTE, (v and 0x7f) or 0x80)
            v = v ushr 7
        }
        builder.put(DataType.BYTE, v)
    }

    private fun appendAppearance(builder: GamePacketBuilder) {
        val wearpos = FilesystemResources.instance.defaults.get<WearposDefaults>().slots

        for (index in 0 until wearpos.size) {
            if (wearpos[index] == SLOT_SKIPPED_BY_CLIENT) continue

            val equipSlot = CONVERSION_MAP[index]
            val cosmetic = if (equipSlot != -1) player.controllingPlayer?.cosmeticFor(equipSlot) else null
            if (cosmetic != null) {
                putVarInt(builder, OBJ_BIAS + cosmetic)
                continue
            }
            val item = if (equipSlot != -1) player.controllingPlayer?.worn?.get(equipSlot) else null
            if (item != null) {
                putVarInt(builder, OBJ_BIAS + item.id)
                continue
            }

            putVarInt(builder, kitSlotValue(index))
        }

        builder.put(DataType.SHORT, CUSTOMISATION_NONE)

        for (i in colours)
            builder.put(DataType.BYTE, i)
        for (i in 0 until 10)
            builder.put(DataType.BYTE, 0)

        builder.put(DataType.SHORT, 2699)
    }

    companion object {
        fun lookIndexOf(slot: Int): Int = when (slot) {
            4 -> 2
            6 -> 3
            7 -> 5
            8 -> 0
            9 -> 4
            10 -> 6
            11 -> 1
            else -> -1
        }

        const val SLOT_EMPTY = 0

        const val KIT_BIAS = 2

        const val OBJ_BIAS = 0x800

        const val SLOT0_MODEL_OVERRIDE = 1

        const val SLOT_SKIPPED_BY_CLIENT = 1

        const val CUSTOMISATION_NONE = 0

        const val COLOUR_COUNT = 10
    }
}

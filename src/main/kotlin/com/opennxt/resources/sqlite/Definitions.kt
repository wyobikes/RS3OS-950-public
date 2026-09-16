package com.opennxt.resources.sqlite

data class ItemDefinition(
    val id: Int,
    val name: String?,
    val members: Boolean,
    val tradeable: Boolean,
    val stackable: Boolean,
    val equipSlotId: Int?,
    val equipId: Int?,
    val buyLimit: Int?,
    val category: Int?,
    val dummyItem: Int?,
    val inventoryActions: Array<String?>
) {
    override fun equals(other: Any?) = other is ItemDefinition && other.id == id
    override fun hashCode() = id
    override fun toString() = "ItemDefinition($id, ${name ?: "unnamed"})"
}

data class NpcDefinition(
    val id: Int,
    val name: String?,
    val size: Int,
    val combatLevel: Int?,
    val movementType: Int?,
    val animationGroup: Int?,
    val drawMapDot: Boolean,
    val actions: Array<String?>
) {
    override fun equals(other: Any?) = other is NpcDefinition && other.id == id
    override fun hashCode() = id
    override fun toString() = "NpcDefinition($id, ${name ?: "unnamed"}, size=$size)"
}

data class LocDefinition(
    val id: Int,
    val name: String?,
    val width: Int,
    val length: Int,
    val blocksMovement: Boolean,
    val walkable: Boolean,
    val allowsLineOfSight: Boolean,
    val animation: Int?,
    val isMembers: Boolean,
    val actions: Array<String?>
) {
    override fun equals(other: Any?) = other is LocDefinition && other.id == id
    override fun hashCode() = id
    override fun toString() = "LocDefinition($id, ${name ?: "unnamed"}, ${width}x$length)"
}

data class VarBitDefinition(
    val id: Int,
    val varId: Int,
    val bitStart: Int,
    val bitEnd: Int
) {
    val bitCount: Int get() = bitEnd - bitStart + 1

    val mask: Int get() = if (bitCount >= 32) -1 else (1 shl bitCount) - 1

    val maxValue: Int get() = mask

    val maxValueUnsigned: Long get() = if (bitCount in 1..32) (1L shl bitCount) - 1 else 0L

    val isWellFormed: Boolean
        get() = bitStart in 0..31 && bitEnd in 0..31 && bitStart <= bitEnd

    fun fits(value: Int): Boolean = if (bitCount >= 32) true else value in 0..maxValue

    fun read(varpValue: Int): Int = (varpValue ushr bitStart) and mask

    fun write(varpValue: Int, value: Int): Int {
        require(fits(value)) {
            "varbit $id holds $bitCount bit(s) (0..$maxValueUnsigned); refusing to write $value"
        }
        return (varpValue and (mask shl bitStart).inv()) or ((value and mask) shl bitStart)
    }

    override fun toString() = "VarBitDefinition($id, varp=$varId, bits=$bitStart..$bitEnd)"
}

data class SequenceDefinition(
    val id: Int,
    val gameId: Int,
    val skeletalAnimation: Int?,
    val unknown_02: Int?,
    val unknown_05: Int?,
    val leftHandItem: Int?,

    val rightHandItem: Int?,
    val unknown_09: Int?,
    val unknown_0A: Int?,
    val unknown_0B: Int?,
    val unknown_0F: Int?,
    val unknown_12: Int?,
    val unknown_18: Int?
) {
    override fun toString() = "SequenceDefinition($id, gameId=$gameId)"
}

data class SpotAnimDefinition(
    val id: Int,
    val ambient: Int?,
    val contrast: Int?,
    val model: Int,
    val sequence: Int?,
    val unk0a: Int?,
    val unk2e: Int?
) {
    override fun toString() = "SpotAnimDefinition($id, model=$model)"
}

data class AnimGroupDefinition(
    val id: Int,
    val run: Int?,
    val turnonspot1: Int?,
    val turnonspot2: Int?,
    val unknown_32: Int?,
    val unknown_33: Int?,
    val walkBack: Int?,
    val walkLeft: Int?,
    val walkRight: Int?
) {
    override fun toString() = "AnimGroupDefinition($id)"
}

data class QuestDefinition(
    val id: Int,
    val name: String?,
    val members: Boolean,
    val questDifficulty: Int?,
    val questItemSprite: Int?,
    val questListName: String?,
    val questPoints: Int?
) {
    override fun toString() = "QuestDefinition($id, ${name ?: "unnamed"})"
}

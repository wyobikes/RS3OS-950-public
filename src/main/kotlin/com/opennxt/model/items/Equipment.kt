package com.opennxt.model.items

import com.opennxt.resources.sqlite.ItemDefinition
import com.opennxt.resources.sqlite.RsDatabase

enum class EquipmentSlot(val id: Int, val label: String) {
    HEAD(0, "Head"),

    CAPE(1, "Cape"),

    NECK(2, "Neck"),

    MAIN_HAND(3, "Main hand"),

    TORSO(4, "Torso"),

    OFF_HAND(5, "Off hand"),

    LEGS(7, "Legs"),

    HANDS(9, "Hands"),

    FEET(10, "Feet"),

    RING(12, "Ring"),

    AMMO(13, "Ammunition"),

    AURA(14, "Aura"),

    POCKET(17, "Pocket"),

    WINGS(18, "Wings");

    companion object {
        const val SLOT_ID_COUNT = 19

        private val byId = arrayOfNulls<EquipmentSlot>(SLOT_ID_COUNT).also { arr ->
            values().forEach { arr[it.id] = it }
        }

        fun forId(id: Int?): EquipmentSlot? =
            if (id == null || id < 0 || id >= SLOT_ID_COUNT) null else byId[id]

        val unoccupiedIds: List<Int> = (0 until SLOT_ID_COUNT).filter { byId[it] == null }

        fun of(def: ItemDefinition?): EquipmentSlot? = forId(def?.equipSlotId)

        fun canEquip(def: ItemDefinition?): Boolean = of(def) != null

        fun distributionFromDatabase(): Map<Int, Int> =
            RsDatabase.queryAll(
                "SELECT equipSlotId AS s, COUNT(*) AS n FROM items WHERE equipSlotId IS NOT NULL GROUP BY equipSlotId"
            ) { rs -> rs.getInt("s") to rs.getInt("n") }.toMap()
    }
}

class Equipment {
    private val container = ItemContainer(EquipmentSlot.SLOT_ID_COUNT)

    operator fun get(slot: EquipmentSlot): Item? = container[slot.id]

    operator fun set(slot: EquipmentSlot, item: Item?) {
        container[slot.id] = item
    }

    fun isEmpty(slot: EquipmentSlot): Boolean = get(slot) == null

    fun equip(item: Item): Item? {
        val def = item.definition
            ?: throw IllegalArgumentException("item ${item.id} has no definition, cannot be equipped")
        val slot = EquipmentSlot.of(def)
            ?: throw IllegalArgumentException("${def.name ?: def.id} is not equippable (equipSlotId=${def.equipSlotId})")

        val displaced = container[slot.id]
        if (displaced != null && displaced.id == item.id && ItemStacking.isStackable(item.id)) {
            container[slot.id] = displaced.plus(item.amount)
            return null
        }
        container[slot.id] = item
        return displaced
    }

    fun unequip(slot: EquipmentSlot): Item? = container.removeSlot(slot.id)

    fun worn(): Map<EquipmentSlot, Item> =
        EquipmentSlot.values().mapNotNull { s -> container[s.id]?.let { s to it } }.toMap()

    fun usedSlots(): Int = container.usedSlots()

    fun clear() = container.clear()

    override fun toString() = "Equipment(${worn()})"
}

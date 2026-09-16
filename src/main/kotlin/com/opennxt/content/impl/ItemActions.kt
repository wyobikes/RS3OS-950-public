package com.opennxt.content.impl

import com.opennxt.resources.sqlite.SqliteItemCodec

object ItemActions {
    val ALL_SLOTS: List<Int> = listOf(0, 1, 2, 3, 4)

    const val ATTR_SLOT = 3

    val isAvailable: Boolean get() = com.opennxt.resources.sqlite.RsDatabase.available

    fun actionsOf(itemId: Int): Array<String?> =
        SqliteItemCodec.load(itemId)?.inventoryActions ?: arrayOfNulls(ALL_SLOTS.size)

    fun actionAt(itemId: Int, slot: Int): String? = actionsOf(itemId).getOrNull(slot)

    fun slotsWithAction(itemId: Int, action: String): List<Int> {
        val actions = actionsOf(itemId)
        return ALL_SLOTS.filter { actions.getOrNull(it)?.equals(action, ignoreCase = true) == true }
    }

    fun nameOf(itemId: Int): String? = SqliteItemCodec.load(itemId)?.name

    @Volatile
    private var built: Map<String, List<Int>>? = null

    private fun build(): Map<String, List<Int>> {
        if (!isAvailable) return emptyMap()
        val out = HashMap<String, MutableList<Int>>()
        for ((id, def) in SqliteItemCodec.listAll()) {
            for (action in def.inventoryActions) {
                if (action.isNullOrBlank()) continue
                out.getOrPut(action.lowercase()) { ArrayList() }.add(id)
            }
        }
        return out.mapValues { (_, ids) -> ids.distinct().sorted() }
    }

    private fun table(): Map<String, List<Int>> = built ?: build().also { built = it }

    fun invalidate() { built = null }

    fun idsWithAction(action: String): List<Int> = table()[action.lowercase()] ?: emptyList()

    fun countWithAction(action: String): Int = idsWithAction(action).size

    fun distinctActionCount(): Int = table().size
}

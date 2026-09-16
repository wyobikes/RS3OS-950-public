package com.opennxt.content.impl

import com.opennxt.resources.sqlite.RsDatabase
import mu.KotlinLogging

object GroundOptions {
    private val logger = KotlinLogging.logger { }

    const val ROWS = 5

    const val TAKE_ROW = 3

    const val TAKE_LABEL = "Take"

    val DEFAULT_ROWS: List<String?> = System.getProperty("opennxt.groundItems.defaultRows")
        ?.split('|')
        ?.map { it.trim().ifEmpty { null } }
        ?.let { supplied ->
            if (supplied.size != ROWS) {
                logger.warn {
                    "opennxt.groundItems.defaultRows needs exactly $ROWS |-separated segments; " +
                        "got ${supplied.size}; using the default"
                }
                null
            } else supplied
        }
        ?: List(ROWS) { if (it == TAKE_ROW - 1) TAKE_LABEL else null }

    private const val SQL_ONE =
        "SELECT field, value FROM items_attr WHERE id = ? AND field LIKE 'ground_actions_%'"
    private const val SQL_ALL =
        "SELECT id, field, value FROM items_attr WHERE field LIKE 'ground_actions_%'"

    private fun slotOf(field: String): Int =
        field.removePrefix("ground_actions_").toIntOrNull()?.takeIf { it in 0 until ROWS } ?: -1

    private fun unquote(raw: String): String =
        if (raw.length >= 2 && raw.first() == '"' && raw.last() == '"') raw.substring(1, raw.length - 1)
        else raw

    fun authoredRowsOf(itemId: Int): Map<Int, String>? {
        if (!RsDatabase.available) return null
        return RsDatabase.queryAll(SQL_ONE, itemId) {
            slotOf(it.getString("field")) to unquote(it.getString("value"))
        }.filter { it.first >= 0 }.toMap()
    }

    fun rowsFor(itemId: Int): List<String?> {
        val authored = authoredRowsOf(itemId) ?: return DEFAULT_ROWS
        if (authored.isEmpty()) return DEFAULT_ROWS
        return (0 until ROWS).map { slot ->
            val a = authored[slot]
            when {
                a == null -> DEFAULT_ROWS[slot]
                a.isEmpty() || a == "null" -> null
                else -> a
            }
        }
    }

    fun optionFor(itemId: Int, option: Int): String? =
        if (option !in 1..ROWS) null else rowsFor(itemId).getOrNull(option - 1)

    fun isTake(itemId: Int, option: Int): Boolean =
        optionFor(itemId, option)?.equals(TAKE_LABEL, ignoreCase = true) == true

    fun authoredCensus(): Map<Int, Map<String, Int>> {
        if (!RsDatabase.available) return emptyMap()
        val out = HashMap<Int, HashMap<String, Int>>()
        RsDatabase.queryAll(SQL_ALL) {
            slotOf(it.getString("field")) to unquote(it.getString("value"))
        }.forEach { (slot, label) ->
            if (slot >= 0) out.getOrPut(slot) { HashMap() }.merge(label, 1, Int::plus)
        }
        return out
    }

    fun authoredItemCount(): Int {
        if (!RsDatabase.available) return 0
        return RsDatabase.queryAll(SQL_ALL) {
            it.getInt("id") to slotOf(it.getString("field"))
        }.filter { it.second >= 0 }.map { it.first }.toSet().size
    }
}

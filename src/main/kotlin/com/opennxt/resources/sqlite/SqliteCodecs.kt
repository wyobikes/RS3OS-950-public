package com.opennxt.resources.sqlite

import com.opennxt.filesystem.Filesystem
import com.opennxt.resources.FilesystemResourceCodec
import com.opennxt.resources.sqlite.RsDatabase.intOrNull
import it.unimi.dsi.fastutil.ints.Int2ObjectAVLTreeMap
import java.sql.ResultSet

private fun ResultSet.str(column: String): String? = getString(column)

private fun ResultSet.bool(column: String): Boolean {
    val v = getInt(column)
    return !wasNull() && v != 0
}

private fun ResultSet.actions(prefix: String, count: Int = 5): Array<String?> =
    Array(count) { i ->
        try {
            getString("${prefix}_$i")
        } catch (e: Exception) {
            null
        }
    }

private fun unquoteAttr(raw: String): String {
    if (raw.length < 2 || raw[0] != '"' || raw[raw.length - 1] != '"') return raw
    val body = raw.substring(1, raw.length - 1)
    if ('\\' !in body) return body
    val sb = StringBuilder(body.length)
    var i = 0
    while (i < body.length) {
        val c = body[i]
        if (c == '\\' && i + 1 < body.length) {
            i++
            when (val e = body[i]) {
                'n' -> sb.append('\n')
                't' -> sb.append('\t')
                'r' -> sb.append('\r')
                'u' -> {
                    sb.append(body.substring(i + 1, i + 5).toInt(16).toChar())
                    i += 4
                }
                else -> sb.append(e)
            }
        } else sb.append(c)
        i++
    }
    return sb.toString()
}

private fun mergeActions(base: Array<String?>, extra: List<Pair<Int, String>>): Array<String?> {
    if (extra.isEmpty()) return base
    val out = base.copyOf()
    for ((slot, value) in extra) if (slot in out.indices && out[slot] == null) out[slot] = value
    return out
}

abstract class SqliteResourceCodec<T : Any>(
    private val table: String,
    private val columns: String
) : FilesystemResourceCodec<T> {
    protected abstract fun map(rs: ResultSet): T

    open fun load(id: Int): T? =
        RsDatabase.queryOne("SELECT $columns FROM \"$table\" WHERE id = ?", id) { map(it) }

    open fun listAll(): Map<Int, T> {
        val out = Int2ObjectAVLTreeMap<T>()
        RsDatabase.queryAll("SELECT $columns FROM \"$table\"") { rs -> rs.getInt("id") to map(rs) }
            .forEach { (id, def) -> out[id] = def }
        return out
    }

    override fun load(fs: Filesystem, id: Int): T? = load(id)

    override fun list(fs: Filesystem): Map<Int, T> = listAll()

    override fun getMaxId(fs: Filesystem): Int = RsDatabase.maxId(table)

    override fun store(fs: Filesystem, id: Int, data: T) {
        throw UnsupportedOperationException(
            "$table is read-only; regenerate rs3.sqlite with buildall.sh instead of writing to it"
        )
    }
}

object SqliteItemCodec : SqliteResourceCodec<ItemDefinition>(
    "items",
    "id, name, members, tradeable, stackable_1, equipSlotId, equipId, buy_limit, category, dummyItem, " +
        "widget_actions_0, widget_actions_1, widget_actions_2, widget_actions_4"
) {
    override fun map(rs: ResultSet) = ItemDefinition(
        id = rs.getInt("id"),
        name = rs.str("name"),
        members = rs.bool("members"),
        tradeable = rs.bool("tradeable"),
        stackable = rs.bool("stackable_1"),
        equipSlotId = rs.intOrNull("equipSlotId"),
        equipId = rs.intOrNull("equipId"),
        buyLimit = rs.intOrNull("buy_limit"),
        category = rs.intOrNull("category"),
        dummyItem = rs.intOrNull("dummyItem"),
        inventoryActions = rs.actions("widget_actions")
    )

    private const val ATTR_WHERE = "FROM items_attr WHERE field LIKE 'widget_actions_%'"
    private const val ATTR_ONE = "SELECT field, value $ATTR_WHERE AND id = ?"
    private const val ATTR_ALL = "SELECT id, field, value $ATTR_WHERE"

    private fun slotOf(field: String): Int =
        field.removePrefix("widget_actions_").toIntOrNull() ?: -1

    override fun load(id: Int): ItemDefinition? {
        val def = super.load(id) ?: return null
        val extra = RsDatabase.queryAll(ATTR_ONE, id) {
            slotOf(it.getString("field")) to unquoteAttr(it.getString("value"))
        }.filter { it.first >= 0 }
        return if (extra.isEmpty()) def
        else def.copy(inventoryActions = mergeActions(def.inventoryActions, extra))
    }

    override fun listAll(): Map<Int, ItemDefinition> {
        val base = super.listAll()
        val extra = HashMap<Int, MutableList<Pair<Int, String>>>()
        RsDatabase.queryAll(ATTR_ALL) {
            Triple(it.getInt("id"), slotOf(it.getString("field")), unquoteAttr(it.getString("value")))
        }.forEach { (id, slot, value) ->
            if (slot >= 0) extra.getOrPut(id) { ArrayList(1) }.add(slot to value)
        }
        if (extra.isEmpty()) return base
        val out = Int2ObjectAVLTreeMap<ItemDefinition>()
        for ((id, def) in base) {
            val rows = extra[id]
            out[id] = if (rows == null) def
            else def.copy(inventoryActions = mergeActions(def.inventoryActions, rows))
        }
        return out
    }

    fun attrActionRowCount(): Int =
        RsDatabase.queryAll(ATTR_ALL) { slotOf(it.getString("field")) }.count { it >= 0 }
}

object SqliteNpcCodec : SqliteResourceCodec<NpcDefinition>(
    "npcs",
    "id, name, boundSize, combat, movementType, animation_group, drawMapDot, actions_0, actions_1, actions_2"
) {
    override fun map(rs: ResultSet) = NpcDefinition(
        id = rs.getInt("id"),
        name = rs.str("name"),
        size = rs.intOrNull("boundSize") ?: 1,
        combatLevel = rs.intOrNull("combat"),
        movementType = rs.intOrNull("movementType"),
        animationGroup = rs.intOrNull("animation_group"),
        drawMapDot = rs.bool("drawMapDot"),
        actions = rs.actions("actions")
    )

    private const val ATTR_WHERE = "FROM npcs_attr WHERE field LIKE 'actions_%'"
    private const val ATTR_ONE = "SELECT field, value $ATTR_WHERE AND id = ?"
    private const val ATTR_ALL = "SELECT id, field, value $ATTR_WHERE"

    private fun slotOf(field: String): Int =
        field.removePrefix("actions_").toIntOrNull() ?: -1

    private val memo = java.util.concurrent.ConcurrentHashMap<Int, java.util.Optional<NpcDefinition>>()

    override fun load(id: Int): NpcDefinition? {
        memo[id]?.let { return it.orElse(null) }
        val answer = loadUncached(id)
        memo[id] = java.util.Optional.ofNullable(answer)
        return answer
    }

    private fun loadUncached(id: Int): NpcDefinition? {
        val def = super.load(id) ?: return null
        val extra = RsDatabase.queryAll(ATTR_ONE, id) {
            slotOf(it.getString("field")) to unquoteAttr(it.getString("value"))
        }.filter { it.first >= 0 }
        return if (extra.isEmpty()) def else def.copy(actions = mergeActions(def.actions, extra))
    }

    fun warm(ids: Collection<Int>): Long {
        val before = RsDatabase.queryCount()
        for (id in ids) load(id)
        return RsDatabase.queryCount() - before
    }

    fun memoSize(): Int = memo.size

    fun forgetMemo() = memo.clear()

    override fun listAll(): Map<Int, NpcDefinition> {
        val base = super.listAll()
        val extra = HashMap<Int, MutableList<Pair<Int, String>>>()
        RsDatabase.queryAll(ATTR_ALL) {
            Triple(it.getInt("id"), slotOf(it.getString("field")), unquoteAttr(it.getString("value")))
        }.forEach { (id, slot, value) ->
            if (slot >= 0) extra.getOrPut(id) { ArrayList(2) }.add(slot to value)
        }
        if (extra.isEmpty()) return base
        val out = LinkedHashMap<Int, NpcDefinition>(base.size)
        base.forEach { (id, def) ->
            val e = extra[id]
            out[id] = if (e == null) def else def.copy(actions = mergeActions(def.actions, e))
        }
        return out
    }
}

object SqliteLocCodec : SqliteResourceCodec<LocDefinition>(
    "locs",
    "id, name, width, length, blocks_movement, walkable, allows_lineofsight, animation, is_members, actions_0"
) {
    override fun map(rs: ResultSet) = LocDefinition(
        id = rs.getInt("id"),
        name = rs.str("name"),
        width = rs.intOrNull("width") ?: 1,
        length = rs.intOrNull("length") ?: 1,
        blocksMovement = rs.bool("blocks_movement"),
        walkable = rs.bool("walkable"),
        allowsLineOfSight = rs.bool("allows_lineofsight"),
        animation = rs.intOrNull("animation"),
        isMembers = rs.bool("is_members"),
        actions = rs.actions("actions")
    )

    private const val ATTR_WHERE = "FROM locs_attr WHERE field LIKE 'actions_%'"
    private const val ATTR_ONE = "SELECT field, value $ATTR_WHERE AND id = ?"
    private const val ATTR_ALL = "SELECT id, field, value $ATTR_WHERE"

    private fun slotOf(field: String): Int =
        field.removePrefix("actions_").toIntOrNull() ?: -1

    override fun load(id: Int): LocDefinition? {
        val def = super.load(id) ?: return null
        val extra = RsDatabase.queryAll(ATTR_ONE, id) {
            slotOf(it.getString("field")) to unquoteAttr(it.getString("value"))
        }.filter { it.first >= 0 }
        return if (extra.isEmpty()) def else def.copy(actions = mergeActions(def.actions, extra))
    }

    override fun listAll(): Map<Int, LocDefinition> {
        val base = super.listAll()
        val extra = HashMap<Int, MutableList<Pair<Int, String>>>()
        RsDatabase.queryAll(ATTR_ALL) {
            Triple(it.getInt("id"), slotOf(it.getString("field")), unquoteAttr(it.getString("value")))
        }.forEach { (id, slot, value) ->
            if (slot >= 0) extra.getOrPut(id) { ArrayList(2) }.add(slot to value)
        }
        if (extra.isEmpty()) return base
        val out = LinkedHashMap<Int, LocDefinition>(base.size)
        base.forEach { (id, def) ->
            val e = extra[id]
            out[id] = if (e == null) def else def.copy(actions = mergeActions(def.actions, e))
        }
        return out
    }
}

object SqliteVarBitCodec : SqliteResourceCodec<VarBitDefinition>(
    "varbits",
    "id, varid, bit_start, bit_end"
) {
    override fun map(rs: ResultSet) = VarBitDefinition(
        id = rs.getInt("id"),
        varId = rs.getInt("varid"),
        bitStart = rs.getInt("bit_start"),
        bitEnd = rs.getInt("bit_end")
    )
}

private const val SEQUENCE_COLUMNS =
    "id, game_id, skeletal_animation, unknown_02, unknown_05, left_hand_item, right_hand_item, " +
        "unknown_09, unknown_0A, unknown_0B, unknown_0F, unknown_12, unknown_18"

object SqliteSequenceCodec : SqliteResourceCodec<SequenceDefinition>("sequences", SEQUENCE_COLUMNS) {
    override fun map(rs: ResultSet) = SequenceDefinition(
        id = rs.getInt("id"),
        gameId = rs.getInt("game_id"),
        skeletalAnimation = rs.intOrNull("skeletal_animation"),
        unknown_02 = rs.intOrNull("unknown_02"),
        unknown_05 = rs.intOrNull("unknown_05"),
        leftHandItem = rs.intOrNull("left_hand_item"),
        rightHandItem = rs.intOrNull("right_hand_item"),
        unknown_09 = rs.intOrNull("unknown_09"),
        unknown_0A = rs.intOrNull("unknown_0A"),
        unknown_0B = rs.intOrNull("unknown_0B"),
        unknown_0F = rs.intOrNull("unknown_0F"),
        unknown_12 = rs.intOrNull("unknown_12"),
        unknown_18 = rs.intOrNull("unknown_18")
    )

    fun loadByGameId(gameId: Int): SequenceDefinition? =
        RsDatabase.queryOne("SELECT $SEQUENCE_COLUMNS FROM \"sequences\" WHERE game_id = ?", gameId) { map(it) }
}

object SqliteSpotAnimCodec : SqliteResourceCodec<SpotAnimDefinition>(
    "spotanims",
    "id, ambient, contrast, model, sequence, unk0a, unk2e"
) {
    override fun map(rs: ResultSet) = SpotAnimDefinition(
        id = rs.getInt("id"),
        ambient = rs.intOrNull("ambient"),
        contrast = rs.intOrNull("contrast"),
        model = rs.getInt("model"),
        sequence = rs.intOrNull("sequence"),
        unk0a = rs.intOrNull("unk0a"),
        unk2e = rs.intOrNull("unk2e")
    )
}

object SqliteAnimGroupCodec : SqliteResourceCodec<AnimGroupDefinition>(
    "animgroups",
    "id, run, turnonspot1, turnonspot2, unknown_32, unknown_33, walk_back, walk_left, walk_right"
) {
    override fun map(rs: ResultSet) = AnimGroupDefinition(
        id = rs.getInt("id"),
        run = rs.intOrNull("run"),
        turnonspot1 = rs.intOrNull("turnonspot1"),
        turnonspot2 = rs.intOrNull("turnonspot2"),
        unknown_32 = rs.intOrNull("unknown_32"),
        unknown_33 = rs.intOrNull("unknown_33"),
        walkBack = rs.intOrNull("walk_back"),
        walkLeft = rs.intOrNull("walk_left"),
        walkRight = rs.intOrNull("walk_right")
    )
}

private val QUEST_FIELDS = listOf(
    "id" to listOf("id"),
    "name" to listOf("name"),
    "members" to listOf("members"),
    "quest_difficulty" to listOf("quest_difficulty", "difficulty"),
    "quest_points" to listOf("quest_points", "points"),
    "quest_item_sprite" to listOf("quest_item_sprite", "item_sprite"),
    "quest_list_name" to listOf("quest_list_name", "list_name")
)

private fun questSelect(present: Set<String>): String =
    QUEST_FIELDS.joinToString(", ") { (alias, candidates) ->
        val found = candidates.firstOrNull { it in present }
        if (found == null) "NULL AS \"$alias\"" else "\"$found\" AS \"$alias\""
    }

object SqliteQuestCodec : SqliteResourceCodec<QuestDefinition>(
    "quests",
    questSelect(RsDatabase.columnsOf("quests"))
) {
    val unsourcedFields: List<String> by lazy {
        val present = RsDatabase.columnsOf("quests")
        QUEST_FIELDS.filter { (_, candidates) -> candidates.none { it in present } }.map { it.first }
    }

    override fun map(rs: ResultSet) = QuestDefinition(
        id = rs.getInt("id"),
        name = rs.str("name"),
        members = rs.bool("members"),
        questDifficulty = rs.intOrNull("quest_difficulty"),
        questItemSprite = rs.intOrNull("quest_item_sprite"),
        questListName = rs.str("quest_list_name"),
        questPoints = rs.intOrNull("quest_points")
    )
}

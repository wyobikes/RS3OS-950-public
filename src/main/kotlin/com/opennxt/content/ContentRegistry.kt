package com.opennxt.content

import com.opennxt.resources.sqlite.LocDefinition
import com.opennxt.resources.sqlite.NpcDefinition
import com.opennxt.resources.sqlite.SqliteLocCodec
import com.opennxt.resources.sqlite.SqliteNpcCodec
import mu.KotlinLogging

typealias LocHandler = (LocContext) -> Any?
typealias NpcHandler = (NpcContext) -> Any?

data class ItemOnLocContext(
    val player: ContentPlayer,
    val definition: LocDefinition,
    val itemId: Int,
    val slot: Int,
    val action: String,
    val actionSlot: Int,
    val x: Int,
    val z: Int,
    val plane: Int
) {
    val locId: Int get() = definition.id
    override fun toString() =
        "ItemOnLocContext(item $itemId slot $slot on ${definition.id} '${definition.name}' " +
            "$action@$actionSlot at $x,$z,$plane)"
}

typealias ItemOnLocHandler = (ItemOnLocContext) -> Any?

sealed class DispatchResult {
    data class Handled(val value: Any?) : DispatchResult()

    data class NoHandler(val id: Int, val action: String) : DispatchResult() {
        override fun toString() = "NoHandler(no content bound to $id/'$action')"
    }

    data class Rejected(val id: Int, val action: String, val declared: List<String>) : DispatchResult() {
        override fun toString() =
            "Rejected($id does not declare '$action'; it declares ${if (declared.isEmpty()) "nothing" else declared})"
    }

    data class UnknownTarget(val id: Int) : DispatchResult()

    val handled: Boolean get() = this is Handled
}

interface DefinitionSource {
    fun loc(id: Int): LocDefinition?
    fun npc(id: Int): NpcDefinition?

    fun locsDeclaring(action: String): Set<Int>

    fun npcsDeclaring(action: String): Set<Int>

    val locCount: Int
    val npcCount: Int
}

object SqliteDefinitions : DefinitionSource {
    private val logger = KotlinLogging.logger { }

    private val locs: Map<Int, LocDefinition> by lazy {
        SqliteLocCodec.listAll().also { logger.info { "content: indexed ${it.size} locs" } }
    }
    private val npcs: Map<Int, NpcDefinition> by lazy {
        SqliteNpcCodec.listAll().also { logger.info { "content: indexed ${it.size} npcs" } }
    }

    private val locsByAction: Map<String, Set<Int>> by lazy { index(locs.values.map { it.id to it.actions }) }
    private val npcsByAction: Map<String, Set<Int>> by lazy { index(npcs.values.map { it.id to it.actions }) }

    private fun index(rows: List<Pair<Int, Array<String?>>>): Map<String, Set<Int>> {
        val out = HashMap<String, MutableSet<Int>>()
        for ((id, actions) in rows) {
            for (a in actions) {
                if (a == null) continue
                out.getOrPut(a) { HashSet() }.add(id)
            }
        }
        return out
    }

    override fun loc(id: Int): LocDefinition? = locs[id]
    override fun npc(id: Int): NpcDefinition? = npcs[id]
    override fun locsDeclaring(action: String): Set<Int> = locsByAction[action] ?: emptySet()
    override fun npcsDeclaring(action: String): Set<Int> = npcsByAction[action] ?: emptySet()
    override val locCount: Int get() = locs.size
    override val npcCount: Int get() = npcs.size

    fun locActionHistogram(): Map<String, Int> = locsByAction.mapValues { it.value.size }

    fun npcActionHistogram(): Map<String, Int> = npcsByAction.mapValues { it.value.size }
}

object ContentRegistry {
    private val logger = KotlinLogging.logger { }

    private data class Key(val id: Int, val action: String)

    private val locById = HashMap<Key, LocHandler>()
    private val locByAction = HashMap<String, LocHandler>()
    private val npcById = HashMap<Key, NpcHandler>()
    private val npcByAction = HashMap<String, NpcHandler>()

    var source: DefinitionSource = SqliteDefinitions

    fun onLoc(id: Int, action: String, handler: LocHandler) {
        val def = source.loc(id) ?: throw IllegalArgumentException("no loc $id in the definitions")
        require(def.actions.any { it == action }) {
            "loc $id ('${def.name}') does not declare '$action'; it declares ${def.actions.filterNotNull()}"
        }
        locById[Key(id, action)] = handler
    }

    fun onLocAction(action: String, handler: LocHandler): Int {
        val n = source.locsDeclaring(action).size
        require(n > 0) { "no loc in the definitions declares '$action'" }
        locByAction[action] = handler
        logger.info { "content: bound loc action '$action' across $n loc ids" }
        return n
    }

    fun onNpc(id: Int, action: String, handler: NpcHandler) {
        val def = effectiveDefinition(id) ?: throw IllegalArgumentException("no npc $id in the definitions")
        require(def.actions.any { it == action }) {
            "npc $id ('${def.name}') does not declare '$action'; it declares ${def.actions.filterNotNull()}"
        }
        npcById[Key(id, action)] = handler
    }

    fun onNpcAction(action: String, handler: NpcHandler): Int {
        val n = source.npcsDeclaring(action).size
        require(n > 0) { "no npc in the definitions declares '$action'" }
        npcByAction[action] = handler
        logger.info { "content: bound npc action '$action' across $n npc ids" }
        return n
    }

    fun dispatchLoc(
        player: ContentPlayer,
        locId: Int,
        action: String,
        x: Int,
        z: Int,
        plane: Int = 0
    ): DispatchResult {
        val def = source.loc(locId) ?: return DispatchResult.UnknownTarget(locId)
        val slot = def.actions.indexOfFirst { it == action }
        if (slot < 0) return DispatchResult.Rejected(locId, action, def.actions.filterNotNull())

        val handler = locById[Key(locId, action)] ?: locByAction[action]
        ?: return DispatchResult.NoHandler(locId, action)

        return DispatchResult.Handled(handler(LocContext(player, def, action, slot, x, z, plane)))
    }

    fun dispatchNpc(
        player: ContentPlayer,
        npcId: Int,
        action: String,
        npcIndex: Int = -1,
        x: Int = 0,
        z: Int = 0,
        plane: Int = 0
    ): DispatchResult {
        val def = effectiveDefinition(npcId, player) ?: return DispatchResult.UnknownTarget(npcId)
        val slot = def.actions.indexOfFirst { it == action }
        if (slot < 0) return DispatchResult.Rejected(npcId, action, def.actions.filterNotNull())

        val handler = npcById[Key(npcId, action)] ?: npcById[Key(def.id, action)] ?: npcByAction[action]
        ?: return DispatchResult.NoHandler(npcId, action)

        return DispatchResult.Handled(handler(NpcContext(player, def, action, slot, npcIndex, x, z, plane)))
    }

    fun effectiveDefinition(npcId: Int, player: ContentPlayer? = null): NpcDefinition? {
        val base = source.npc(npcId) ?: return null
        if (!com.opennxt.model.world.NpcMorph.isStub(npcId)) return base
        val target =
            if (player == null) com.opennxt.model.world.NpcMorph.effectiveId(npcId)
            else com.opennxt.model.world.NpcMorph.effectiveIdFor(player, npcId)
        if (target == npcId) return base
        return com.opennxt.model.world.NpcMorph.definition(base, target) { source.npc(it) }
    }

    private val itemOnLocByPair = HashMap<Pair<Int, Int>, ItemOnLocHandler>()
    private val itemOnLocByLoc = HashMap<Int, ItemOnLocHandler>()
    private val itemOnLocByItem = HashMap<Int, ItemOnLocHandler>()

    fun onItemOnLoc(itemId: Int?, locId: Int?, action: String = "Use", handler: ItemOnLocHandler) {
        require(itemId != null || locId != null) { "onItemOnLoc needs an item id, a loc id, or both" }
        if (locId != null) {
            val def = source.loc(locId) ?: throw IllegalArgumentException("no loc $locId in the definitions")
            require(def.actions.any { it == action }) {
                "loc $locId ('${def.name}') does not declare '$action'; it declares ${def.actions.filterNotNull()}"
            }
        }
        when {
            itemId != null && locId != null -> itemOnLocByPair[itemId to locId] = handler
            locId != null -> itemOnLocByLoc[locId] = handler
            else -> itemOnLocByItem[itemId!!] = handler
        }
    }

    fun dispatchItemOnLoc(
        player: ContentPlayer,
        itemId: Int,
        locId: Int,
        slot: Int,
        x: Int,
        z: Int,
        plane: Int = 0,
        action: String = "Use"
    ): DispatchResult {
        val def = source.loc(locId) ?: return DispatchResult.UnknownTarget(locId)
        if (itemId <= 0 || itemId == 0xffffff)
            return DispatchResult.Rejected(locId, "$action item $itemId", def.actions.filterNotNull())
        val actionSlot = def.actions.indexOfFirst { it == action }
        if (actionSlot < 0) return DispatchResult.Rejected(locId, action, def.actions.filterNotNull())

        val handler = itemOnLocByPair[itemId to locId] ?: itemOnLocByLoc[locId] ?: itemOnLocByItem[itemId]
        ?: return DispatchResult.NoHandler(locId, "$action item $itemId")

        return DispatchResult.Handled(
            handler(ItemOnLocContext(player, def, itemId, slot, action, actionSlot, x, z, plane))
        )
    }

    fun boundItemOnLoc(): Int = itemOnLocByPair.size + itemOnLocByLoc.size + itemOnLocByItem.size

    fun locCoverage(action: String): Int =
        if (action in locByAction) source.locsDeclaring(action).size else 0

    fun npcCoverage(action: String): Int =
        if (action in npcByAction) source.npcsDeclaring(action).size else 0

    fun boundLocActions(): Set<String> = locByAction.keys.toSet()
    fun boundNpcActions(): Set<String> = npcByAction.keys.toSet()
    fun boundLocIds(): Int = locById.size
    fun boundNpcIds(): Int = npcById.size

    fun clear() {
        locById.clear(); locByAction.clear(); npcById.clear(); npcByAction.clear()
        itemOnLocByPair.clear(); itemOnLocByLoc.clear(); itemOnLocByItem.clear()
    }
}

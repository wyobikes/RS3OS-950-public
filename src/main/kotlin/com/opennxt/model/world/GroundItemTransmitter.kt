package com.opennxt.model.world

import com.opennxt.OpenNXT
import com.opennxt.net.game.serverprot.ObjAdd
import com.opennxt.net.game.serverprot.ObjAdd949
import com.opennxt.net.game.serverprot.ObjDel949
import com.opennxt.net.game.serverprot.ObjDel
import com.opennxt.net.game.serverprot.UpdateZonePartialFollows
import com.opennxt.net.game.serverprot.ZoneCoord
import mu.KotlinLogging
import java.util.Collections
import java.util.WeakHashMap

object GroundItemTransmitter {
    private val logger = KotlinLogging.logger { }

    val enabled: Boolean = System.getProperty("opennxt.experiment.groundItems") != "false"

    val demoEnabled: Boolean = System.getProperty("opennxt.experiment.groundItems.demo") != "false"

    private val iconProbeDrops: List<DemoDrop> = listOf(
        DemoDrop(1205, "Bronze dagger", 1, 3222, 3222),
        DemoDrop(1277, "Bronze sword", 1, 3223, 3222),
        DemoDrop(2309, "Bread", 1, 3222, 3223),
        DemoDrop(315, "Shrimps", 1, 3223, 3223)
    )

    val demoDrops: List<DemoDrop> = listOf(
        DemoDrop(995, "Coins", 25, 3224, 3222),
        DemoDrop(1265, "Bronze pickaxe", 1, 3224, 3223),
        DemoDrop(1351, "Bronze hatchet", 1, 3223, 3224),
        DemoDrop(590, "Tinderbox", 1, 3225, 3222)
    ) + if (System.getProperty("opennxt.experiment.iconProbeDrops") == "true") iconProbeDrops
        else emptyList()

    data class DemoDrop(val itemId: Int, val name: String, val quantity: Int, val x: Int, val y: Int)

    const val DEMO_DESPAWN_TICKS = Int.MAX_VALUE / 2

    private val sent: MutableMap<WorldPlayer, SentState> =
        Collections.synchronizedMap(WeakHashMap<WorldPlayer, SentState>())

    private class SentState {
        var epoch: Int = -1
        val quantities: MutableMap<GroundItem, Int> = java.util.IdentityHashMap()
    }

    @Volatile
    private var demoSpawned = false

    @Synchronized
    fun spawnDemo(items: GroundItems): List<GroundItem> {
        if (demoSpawned || !demoEnabled) return emptyList()
        demoSpawned = true
        val spawned = demoDrops.map {
            items.spawnItem(
                itemId = it.itemId,
                itemName = it.name,
                quantity = it.quantity,
                tile = TileLocation(it.x, it.y, 0),
                owner = null,
                source = "demo drop",
                ticksRemaining = DEMO_DESPAWN_TICKS
            )
        }
        logger.warn {
            "Spawned ${spawned.size} demo ground item(s): " +
                spawned.joinToString(", ") { "${it.itemName} x${it.quantity} @(${it.tile.x},${it.tile.y})" } +
                "; -Dopennxt.experiment.groundItems.demo=false to disable"
        }
        return spawned
    }

    internal fun resetDemoLatch() {
        demoSpawned = false
    }

    fun sync(player: WorldPlayer) {
        if (!enabled) return

        try {
            com.opennxt.content.impl.LootWindow.refresh(player)
        } catch (t: Throwable) {
            if (!lootFailureWarned) {
                lootFailureWarned = true
                logger.error(t) {
                    "Loot window refresh failed for ${player.name}; " +
                        "-Dopennxt.experiment.lootWindow=false disables it (logged once)"
                }
            }
        }

        if (!opcodesAvailable()) return

        val world = runCatching { OpenNXT.world }.getOrNull() ?: return
        spawnDemo(world.groundItems)

        try {
            syncUnguarded(player, world.groundItems)
        } catch (t: Throwable) {
            if (!failureWarned) {
                failureWarned = true
                logger.error(t) {
                    "Ground item sync failed for ${player.name}; " +
                        "-Dopennxt.experiment.groundItems=false disables it (logged once)"
                }
            }
        }
    }

    private fun syncUnguarded(player: WorldPlayer, items: GroundItems) {
        val viewport = player.viewport
        val state = sent.getOrPut(player) { SentState() }

        if (state.epoch != viewport.sceneEpoch) {
            if (state.epoch != -1 && state.quantities.isNotEmpty()) {
                logger.info {
                    "Scene rebuilt for ${player.name} (epoch ${state.epoch} -> ${viewport.sceneEpoch}); " +
                        "re-sending ${state.quantities.size} ground item(s)"
                }
            }
            state.epoch = viewport.sceneEpoch
            state.quantities.clear()
        }

        val plane = player.entity.location.plane
        val visible = items.all().filter {
            it.tile.plane == plane && viewport.containsTile(it.tile.x, it.tile.y) &&
                (it.isPublic || it.owner == player.name)
        }
        val visibleSet = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<GroundItem, Boolean>())
        visibleSet.addAll(visible)

        val gone = state.quantities.keys.filter { it !in visibleSet }
        for (item in gone) {
            sendDel(player, item)
            state.quantities.remove(item)
        }

        for (item in visible) {
            val known = state.quantities[item]
            if (known == item.quantity) continue
            if (known != null) {
                sendDel(player, item)
            }
            sendAdd(player, item)
            state.quantities[item] = item.quantity
        }
    }

    private fun sendAdd(player: WorldPlayer, item: GroundItem) {
        val v = player.viewport
        for (packet in addPackets(v, item)) player.client.write(packet)
        addsSent++
        if (adds < 8) {
            adds++
            logger.info {
                "OBJ_ADD #$adds to ${player.name}: ${item.itemName} (obj ${item.itemId}) x${item.quantity} at " +
                    "(${item.tile.x},${item.tile.y},p${item.tile.plane}) -> zone " +
                    "(${v.zoneX(item.tile.x)},${v.zoneY(item.tile.y)}) coord " +
                    "0x%02x, scene chunk (${v.chunkX},${v.chunkY})".format(
                        ZoneCoord.coord(item.tile.x, item.tile.y)
                    )
            }
        }
    }

    private fun sendDel(player: WorldPlayer, item: GroundItem) {
        val v = player.viewport
        if (!v.containsTile(item.tile.x, item.tile.y)) return
        for (packet in delPackets(v, item)) player.client.write(packet)
        delsSent++
    }

    internal fun addPackets(v: com.opennxt.model.entity.player.Viewport, item: GroundItem): List<com.opennxt.net.game.GamePacket> = listOf(
        UpdateZonePartialFollows(item.tile.plane, v.zoneX(item.tile.x), v.zoneY(item.tile.y)),
        if (useTwins()) ObjAdd949(id = item.itemId, count = item.quantity.coerceAtMost(0xFFFF), coord = ZoneCoord.coord(item.tile.x, item.tile.y))
        else ObjAdd(
            coord = ZoneCoord.coord(item.tile.x, item.tile.y),
            count = item.quantity.coerceAtMost(0xFFFF),
            id = item.itemId
        )
    )

    internal fun delPackets(v: com.opennxt.model.entity.player.Viewport, item: GroundItem): List<com.opennxt.net.game.GamePacket> = listOf(
        UpdateZonePartialFollows(item.tile.plane, v.zoneX(item.tile.x), v.zoneY(item.tile.y)),
        if (useTwins()) ObjDel949(item.itemId, ZoneCoord.coord(item.tile.x, item.tile.y))
        else ObjDel(ZoneCoord.coord(item.tile.x, item.tile.y), item.itemId)
    )

    private fun useTwins(): Boolean {
        val names = OpenNXT.protocol.serverProtNames.values
        return names["OBJ_ADD"] == null && names["OBJ_ADD_949"] != null && names["OBJ_DEL_949"] != null
    }

    fun addsSent(): Int = addsSent

    fun delsSent(): Int = delsSent

    fun resetSendCounters() {
        addsSent = 0
        delsSent = 0
    }

    @Volatile
    private var addsSent = 0

    @Volatile
    private var delsSent = 0

    private fun opcodesAvailable(): Boolean {
        val names = OpenNXT.protocol.serverProtNames.values
        val missing = if (useTwins()) listOf("UPDATE_ZONE_PARTIAL_FOLLOWS").filter { names[it] == null }
            else listOf("UPDATE_ZONE_PARTIAL_FOLLOWS", "OBJ_ADD", "OBJ_DEL").filter { names[it] == null }
        if (missing.isEmpty()) return true
        if (!unmappedWarned) {
            unmappedWarned = true
            logger.warn {
                "Build ${OpenNXT.protocol.effectiveBuild} has no opcode for ${missing.joinToString(", ")} in " +
                    "data/prot/<build>/serverProtNames.toml; ground items disabled (logged once)"
            }
        }
        return false
    }

    fun forget(player: WorldPlayer) {
        sent.remove(player)
    }

    @Volatile
    private var unmappedWarned = false

    @Volatile
    private var failureWarned = false

    @Volatile
    private var lootFailureWarned = false

    @Volatile
    private var adds = 0
}

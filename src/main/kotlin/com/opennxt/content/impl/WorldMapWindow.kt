package com.opennxt.content.impl

import com.opennxt.model.world.WorldPlayer
import com.opennxt.net.game.clientprot.IfButtonN
import com.opennxt.net.game.serverprot.RunClientScript
import com.opennxt.net.game.serverprot.variables.ClientSetvarcLarge
import mu.KotlinLogging
import java.util.Collections
import java.util.WeakHashMap

object WorldMapWindow {
    private val logger = KotlinLogging.logger { }

    const val MINIMAP_IFACE = 1465
    const val MAP_BUTTON = 11

    const val OPEN_MAP_ROW = 1

    const val MAP_BUTTON_MASK = 62

    const val GAMEFRAME = 1477

    const val MAP_IFACE = 1421
    const val MAP_DOCK = 31

    const val GAME_VIEW_IFACE = 1482

    const val MAP_UI_IFACE = 1422
    const val MAP_UI_DOCK = 800

    const val UPSELL_IFACE = 698
    const val UPSELL_DOCK = 75
    const val CONFIRM_IFACE = 1612
    const val CONFIRM_DOCK = 76

    const val MAP_CLOSE_BUTTON = 111

    const val CONFIRM_BUTTON_LAYER = 11

    const val CONFIRM_ARM_FROM = 1
    const val CONFIRM_ARM_TO = 35

    const val SCRIPT_BUTTON = 8060
    const val SCRIPT_BUILD = 9332
    const val SCRIPT_TEARDOWN = 8105

    val CENTRE_VARCS = listOf(674, 622)

    val MAP_UI_ARMED_COMPONENTS = listOf(20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 31)

    val HIDDEN_WHILE_OPEN = listOf(42, 44, 45, 824, 638, 43, 743, 728)

    val enabled: Boolean get() = System.getProperty("opennxt.experiment.worldMap") != "false"

    private val open: MutableSet<WorldPlayer> =
        Collections.newSetFromMap(Collections.synchronizedMap(WeakHashMap<WorldPlayer, Boolean>()))

    fun isOpen(player: WorldPlayer): Boolean = player in open

    fun packCoord(x: Int, y: Int, plane: Int): Int = (plane shl 28) or (x shl 14) or y

    fun buttonHash(): Int = (MINIMAP_IFACE shl 16) or MAP_BUTTON

    fun open(player: WorldPlayer): Boolean {
        if (!enabled) return false
        if (player in open) {
            close(player, "the world-map button was clicked again")
            return true
        }
        val here = player.entity.location
        val centre = packCoord(here.x, here.y, here.plane)

        try {
        player.client.write(RunClientScript(SCRIPT_BUTTON, arrayOf(buttonHash(), -1)))
        player.client.write(ClientSetvarcLarge(CENTRE_VARCS[0], centre))

        player.interfaces.open(id = MAP_IFACE, parent = GAMEFRAME, component = MAP_DOCK,
            walkable = true, native949 = true)
        player.interfaces.open(id = MAP_UI_IFACE, parent = GAMEFRAME, component = MAP_UI_DOCK,
            walkable = false, native949 = true)
        player.interfaces.open(id = UPSELL_IFACE, parent = MAP_UI_IFACE, component = UPSELL_DOCK,
            walkable = true, native949 = true)

        for (c in MAP_UI_ARMED_COMPONENTS) {
            player.interfaces.events(id = MAP_UI_IFACE, component = c, from = 2, to = 2, mask = 2)
        }
        player.client.write(RunClientScript(SCRIPT_BUILD, emptyArray()))

        player.interfaces.open(id = CONFIRM_IFACE, parent = MAP_UI_IFACE, component = CONFIRM_DOCK,
            walkable = true, native949 = true)
        player.interfaces.events(id = CONFIRM_IFACE, component = CONFIRM_BUTTON_LAYER,
            from = CONFIRM_ARM_FROM, to = CONFIRM_ARM_TO, mask = 2)

        player.client.write(ClientSetvarcLarge(CENTRE_VARCS[1], centre))
        player.interfaces.events(id = MAP_UI_IFACE, component = 54, from = 0, to = 19, mask = 2)
        player.interfaces.hide(id = MAP_UI_IFACE, component = 33, hidden = true)
        for (c in HIDDEN_WHILE_OPEN) player.interfaces.hide(id = GAMEFRAME, component = c, hidden = true)
        } catch (t: Throwable) {
            open.remove(player)
            logger.error(t) { "worldmap ${player.name}: could not open the map; nothing is mounted." }
            return false
        }

        open.add(player)
        logger.info {
            "worldmap ${player.name}: opened, centred on (${here.x},${here.y},p${here.plane}) " +
                "= packed $centre; ${HIDDEN_WHILE_OPEN.size} gameframe component(s) hidden"
        }
        return true
    }

    fun close(player: WorldPlayer, reason: String) {
        if (!open.remove(player)) return
        player.interfaces.close(id = GAMEFRAME, component = MAP_UI_DOCK, native949 = true)
        player.client.write(RunClientScript(SCRIPT_TEARDOWN, emptyArray()))
        player.interfaces.close(id = GAMEFRAME, component = MAP_DOCK, native949 = true)
        player.interfaces.close(id = MAP_UI_IFACE, component = UPSELL_DOCK, native949 = true)
        player.interfaces.close(id = MAP_UI_IFACE, component = CONFIRM_DOCK, native949 = true)
        player.interfaces.open(id = GAME_VIEW_IFACE, parent = GAMEFRAME, component = MAP_DOCK,
            walkable = true, native949 = true)
        for (c in HIDDEN_WHILE_OPEN) player.interfaces.hide(id = GAMEFRAME, component = c, hidden = false)
        logger.info { "worldmap ${player.name}: closed ($reason)" }
    }

    fun arm(player: WorldPlayer) {
        player.interfaces.events(id = MINIMAP_IFACE, component = MAP_BUTTON,
            from = 65535, to = 65535, mask = MAP_BUTTON_MASK)
    }

    fun handleButton(player: WorldPlayer, packet: IfButtonN): Boolean {
        if (!enabled) return false
        if (packet.interfaceId == MINIMAP_IFACE && packet.component == MAP_BUTTON) {
            val row = ItemOps.rowForOp(player, MINIMAP_IFACE, MAP_BUTTON, packet.buttonOp)
            if (row != OPEN_MAP_ROW - 1) {
                logger.info {
                    "worldmap ${player.name}: 1465:11 op ${packet.buttonOp} maps to row " +
                        "${row?.plus(1) ?: "nothing"}, which is not 'Open World Map' - ignored."
                }
                return true
            }
            return open(player)
        }
        if (packet.interfaceId == MAP_UI_IFACE && packet.component == MAP_CLOSE_BUTTON) {
            close(player, "the map's Close was clicked")
            return true
        }
        if (packet.interfaceId == CONFIRM_IFACE && packet.component == CONFIRM_BUTTON_LAYER) {
            val teleported = Lodestones.handleConfirmClick(player, packet.arg2)
            close(player, if (teleported) "a lodestone was chosen (slot ${packet.arg2})"
            else "the map's confirm dialog was answered")
            return true
        }
        return false
    }
}

package com.opennxt.content.impl

import com.opennxt.model.world.WorldPlayer
import com.opennxt.net.game.clientprot.IfButtonN
import com.opennxt.net.game.serverprot.RunClientScript
import mu.KotlinLogging

object PanelToggles {
    private val logger = KotlinLogging.logger { }

    private val enabled: Boolean
        get() = System.getProperty("opennxt.experiment.ui.panelToggles") != "false"

    const val LAYOUT_IFACE = 1475
    const val DISPLAY_WINDOWS_LIST = 3

    private const val MAIN_BAR_IFACE = 1430
    private const val MAIN_BAR_DOCK = 70
    private const val GAMEFRAME = 1477

    val BARS: Map<Int, Pair<Int, Int>> = linkedMapOf(
        1032 to (1670 to 75),
        1033 to (1671 to 80),
        1034 to (1672 to 85),
        1035 to (1673 to 90),
    )

    fun describe(panel: Int): String =
        "Additional Action Bar ${panel - 1031} (bar ${panel - 1030} in game)"

    fun varpFor(panel: Int): Int {
        require(panel in BARS) { "panel $panel is not an Additional Action Bar (expected one of ${BARS.keys})" }
        return 10092 + (panel - 1032)
    }

    fun isBarEnabled(player: WorldPlayer, panel: Int): Boolean =
        player.varpOverride(varpFor(panel)) == 1

    const val VARP_BAR_LAYOUT = 10160

    const val VARP_BAR_MEMORY = 10244

    private const val FIELD_BITS = 5
    private const val FIELD_MASK = 31

    private const val MAX_ASSIGNED_BAR = 5

    val layoutVarpEnabled: Boolean
        get() = System.getProperty("opennxt.experiment.ui.panelToggles.layoutVarp") != "false"

    fun layoutField(panel: Int): Int {
        require(panel in BARS) { "panel $panel is not an Additional Action Bar" }
        return 1 + (panel - 1032)
    }

    fun memoryField(panel: Int): Int {
        require(panel in BARS) { "panel $panel is not an Additional Action Bar" }
        return panel - 1032
    }

    fun fieldOf(value: Int, index: Int): Int = (value ushr (FIELD_BITS * index)) and FIELD_MASK

    fun withField(value: Int, index: Int, field: Int): Int {
        val shift = FIELD_BITS * index
        return (value and (FIELD_MASK shl shift).inv()) or ((field and FIELD_MASK) shl shift)
    }

    fun reconcileLayout(layout: Int, memory: Int, enabled: Set<Int>): Int {
        var out = layout
        val used = HashSet<Int>()
        used += fieldOf(layout, 0).let { if (it == 0) 1 else it }
        val needNumber = ArrayList<Int>()
        for (panel in BARS.keys.sorted()) {
            val idx = layoutField(panel)
            if (panel !in enabled) { out = withField(out, idx, 0); continue }
            val have = fieldOf(layout, idx)
            if (have != 0 && used.add(have)) continue
            needNumber += panel
        }
        for (panel in needNumber) {
            val remembered = fieldOf(memory, memoryField(panel))
            val number = if (remembered != 0 && remembered !in used) remembered
                else (1..MAX_ASSIGNED_BAR).firstOrNull { it !in used } ?: 0
            if (number != 0) used += number
            out = withField(out, layoutField(panel), number)
        }
        return out
    }

    fun rememberBarNumber(memory: Int, panel: Int, oldNumber: Int): Int =
        if (oldNumber == 0) memory else withField(memory, memoryField(panel), oldNumber)

    fun panelForBarMount(iface: Int, dock: Int): Int? =
        BARS.entries.firstOrNull { it.value.first == iface && it.value.second == dock }?.key

    fun enabledPanels(player: WorldPlayer): List<Int> = BARS.keys.filter { isBarEnabled(player, it) }

    fun handleButton(player: WorldPlayer, packet: IfButtonN): Boolean {
        if (!enabled) return false
        if (packet.interfaceId != LAYOUT_IFACE) return false
        if (packet.component != DISPLAY_WINDOWS_LIST) return false

        if (!player.interfaces.isOpened(LAYOUT_IFACE)) {
            logger.info {
                "panelToggle: ignored toggle from ${player.name}, interface $LAYOUT_IFACE is not open"
            }
            return true
        }
        val panel = packet.arg2
        val seat = BARS[panel]
        if (seat == null) {
            logger.info {
                "panelToggle: display window $panel is not supported"
            }
            return false
        }

        if (!player.allowPanelToggleThisTick()) {
            logger.info {
                "panelToggle: dropped extra toggle from ${player.name} this tick"
            }
            return true
        }

        val (iface, dock) = seat
        val varp = varpFor(panel)
        val turningOn = !isBarEnabled(player, panel)
        val layoutBefore = player.varpValue(VARP_BAR_LAYOUT)

        player.setVarpOverride(varp, if (turningOn) 1 else 0)

        if (layoutVarpEnabled) {
            var memory = player.varpValue(VARP_BAR_MEMORY)
            if (!turningOn) {
                memory = rememberBarNumber(memory, panel, fieldOf(layoutBefore, layoutField(panel)))
                player.setVarpOverride(VARP_BAR_MEMORY, memory)
            }
            player.setVarpOverride(VARP_BAR_LAYOUT, reconcileLayout(layoutBefore, memory, enabledPanels(player).toSet()))
        }

        if (turningOn) {
            player.interfaces.open(
                id = MAIN_BAR_IFACE, parent = GAMEFRAME, component = MAIN_BAR_DOCK,
                walkable = true, native949 = true
            )
            player.interfaces.open(
                id = iface, parent = GAMEFRAME, component = dock,
                walkable = true, native949 = true
            )
            player.client.write(RunClientScript(script = 8310, args = arrayOf(panel)))
        } else {
            player.interfaces.open(
                id = MAIN_BAR_IFACE, parent = GAMEFRAME, component = MAIN_BAR_DOCK,
                walkable = true, native949 = true
            )
            player.interfaces.close(id = GAMEFRAME, component = dock, native949 = true)
            player.client.write(RunClientScript(script = 8320, args = arrayOf(panel)))
        }

        ActionBarArm.arm(player, enabledPanels(player))

        logger.info {
            "panelToggle: ${describe(panel)} (panel $panel) " +
                "${if (turningOn) "on" else "off"} for ${player.name}, varp $varp = " +
                "${if (turningOn) 1 else 0}" +
                (if (layoutVarpEnabled) ", varp $VARP_BAR_LAYOUT ${layoutBefore} -> ${player.varpValue(VARP_BAR_LAYOUT)}" else "") +
                (if (turningOn) ", opened $iface at $GAMEFRAME:$dock"
                 else ", closed $GAMEFRAME:$dock")
        }
        return true
    }

    fun sendLoginState(player: WorldPlayer) {
        if (!enabled) return
        if (layoutVarpEnabled) {
            val current = player.varpValue(VARP_BAR_LAYOUT)
            val layout = reconcileLayout(current, player.varpValue(VARP_BAR_MEMORY), enabledPanels(player).toSet())
            player.setVarpOverride(VARP_BAR_LAYOUT, layout, store = layout != current)
            logger.info { "panelToggle: login varp $VARP_BAR_LAYOUT $current -> $layout for ${player.name} (bar fields ${(1..4).map { fieldOf(layout, it) }})" }
        }
        var opened = 0
        for ((panel, seat) in BARS) {
            val on = isBarEnabled(player, panel)
            player.setVarpOverride(varpFor(panel), if (on) 1 else 0, store = false)
            if (!on) continue
            val (iface, dock) = seat
            player.interfaces.open(
                id = iface, parent = GAMEFRAME, component = dock,
                walkable = true, native949 = true
            )
            player.client.write(RunClientScript(script = 8310, args = arrayOf(panel)))
            opened++
        }
        if (opened > 0) ActionBarArm.arm(player, enabledPanels(player))
        logger.info {
            "panelToggle: restored $opened of ${BARS.size} additional action bar(s) for ${player.name}"
        }
    }
}

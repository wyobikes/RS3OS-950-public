package com.opennxt.content.impl

import com.opennxt.model.InterfaceHash
import com.opennxt.model.world.WorldPlayer
import com.opennxt.net.game.clientprot.IfButtonN
import com.opennxt.net.game.serverprot.RunClientScript
import com.opennxt.net.game.serverprot.generated.IfSetgraphic
import com.opennxt.net.game.serverprot.ifaces.IfSethide
import mu.KotlinLogging

object OptionsMenu {
    private val logger = KotlinLogging.logger { }

    private val enabled: Boolean
        get() = System.getProperty("opennxt.experiment.ui.optionsMenu") != "false"

    const val OPTIONS_IFACE = 1433
    const val EDIT_LAYOUT_BUTTON = 22

    const val LAYOUT_IFACE = 1475
    const val LAYOUT_PARENT = 1477
    const val LAYOUT_MOUNT = 752

    const val LAYOUT_SAVE_AND_EXIT = 44
    const val LAYOUT_CLOSE = 20

    const val LAYOUT_SAVE_AND_EXIT_950 = 43
    private val c950: Boolean get() = com.opennxt.model.entity.rendering.UpdateBlockType.experimentalBuild()
    fun saveAndExitComponent(build950: Boolean = c950): Int = if (build950) LAYOUT_SAVE_AND_EXIT_950 else LAYOUT_SAVE_AND_EXIT
    fun editorEventComponents(build950: Boolean = c950): List<Int> = if (build950) listOf(48, 3, 39) else listOf(49, 3, 40)
    fun editorHiddenComponent(build950: Boolean = c950): Int = if (build950) 33 else 34
    fun editorGraphicComponent(build950: Boolean = c950): Int = if (build950) 34 else 35

    val LAYOUT_OWNERSHIP_VARPS: IntArray = intArrayOf(10096, 12578, 12579, 12580, 12581)

    fun layoutPersists(): Boolean = System.getProperty("opennxt.experiment.ui.varcReplay") != "false"

    fun applyLayoutDefaults(player: WorldPlayer): Int {
        if (!enabled) return 0
        var defaulted = 0
        for (id in LAYOUT_OWNERSHIP_VARPS) {
            val own = player.varpOverride(id)
            if (own == null) defaulted++
            player.setVarpOverride(id, own ?: 0, store = false)
        }
        logger.info { "layout defaults: ${player.name} - $defaulted of ${LAYOUT_OWNERSHIP_VARPS.size} layout varps defaulted, the rest from the save" }
        return defaulted
    }

    const val SAVE_CONFIRM_IFACE = 26
    const val SAVE_CONFIRM_MOUNT = 880
    const val SAVE_CONFIRM_BUTTON = 11
    const val VARP_SAVING_LAYOUT = 3813
    const val VARP_LAYOUT_IN_USE = 10096
    val VARP_CUSTOM_SLOT_SAVED = intArrayOf(12578, 12579, 12580, 12581)
    const val SCRIPT_LAYOUT_SAVED = 8743
    const val SCRIPT_LEAVE_EDITOR = 8745
    const val VARCSTR_EDITOR = 8264
    const val CHAT_IFACE = 590

    private val chatArmRows: List<IntArray> by lazy {
        val path = com.opennxt.Constants.DATA_PATH.resolve("config").resolve("login-mounts.tsv")
        if (!java.nio.file.Files.isRegularFile(path)) return@lazy emptyList()
        java.nio.file.Files.readAllLines(path).mapNotNull { line ->
            val f = line.trim().split('\t')
            if (f.size >= 6 && f[0] == "events" && f[1].toIntOrNull() == CHAT_IFACE)
                intArrayOf(f[2].toInt(), f[3].toInt(), f[4].toInt(), f[5].toInt()) else null
        }
    }
    fun chatArmRowCount(): Int = chatArmRows.size

    private val pendingSave = java.util.Collections.synchronizedMap(java.util.WeakHashMap<WorldPlayer, Boolean>())

    fun handleSaveConfirm(player: WorldPlayer, packet: IfButtonN): Boolean {
        if (!enabled) return false
        if (packet.interfaceId != SAVE_CONFIRM_IFACE || packet.component != SAVE_CONFIRM_BUTTON) return false
        if (pendingSave.remove(player) != true || !player.interfaces.isOpened(LAYOUT_IFACE) || !player.interfaces.isOpened(SAVE_CONFIRM_IFACE)) {
            logger.info { "options menu: ignored save confirmation from ${player.name}; no save pending" }
            return true
        }
        val w = player.client
        val persist = layoutPersists()
        fun own(id: Int, value: Int) { if (persist) player.setVarpOverride(id, value) else w.write(com.opennxt.net.game.serverprot.variables.VarpSmall(id, value)) }
        own(VARP_LAYOUT_IN_USE, 1)
        own(VARP_LAYOUT_IN_USE, 3)
        own(VARP_CUSTOM_SLOT_SAVED[0], 1)
        own(VARP_CUSTOM_SLOT_SAVED[1], 1)
        own(VARP_CUSTOM_SLOT_SAVED[2], 0)
        own(VARP_CUSTOM_SLOT_SAVED[3], 0)
        w.write(com.opennxt.net.game.serverprot.variables.VarpSmall(VARP_SAVING_LAYOUT, 0))
        if (!persist) logger.warn { "options menu: ${player.name}'s layout is not persisted (ui.varcReplay=false)" }
        w.write(RunClientScript(script = SCRIPT_LAYOUT_SAVED, args = arrayOf(6)))
        player.interfaces.close(id = LAYOUT_PARENT, component = SAVE_CONFIRM_MOUNT)
        sendEditorExitHalf(player)
        logger.info {
            "options menu: ${player.name} saved the interface layout"
        }
        return true
    }

    fun sendEditorExitHalf(player: WorldPlayer) {
        val w = player.client
        player.interfaces.close(id = LAYOUT_PARENT, component = LAYOUT_MOUNT)
        w.write(RunClientScript(script = SCRIPT_LEAVE_EDITOR, args = arrayOf(0)))
        w.write(com.opennxt.net.game.serverprot.variables.ClientSetvarcstrSmall(VARCSTR_EDITOR, ""))
        w.write(IfSethide(InterfaceHash(LAYOUT_IFACE, editorHiddenComponent()), true))
        w.write(IfSetgraphic(graphic = -1, component = InterfaceHash(LAYOUT_IFACE, editorGraphicComponent()).hash))
        for (r in chatArmRows) player.interfaces.events(id = CHAT_IFACE, component = r[0], from = r[1], to = r[2], mask = r[3])
    }

    const val LAYOUT_DROPDOWN_EDITOR_950 = 56
    const val LAYOUT_DROPDOWN_OPTIONS = 26
    const val DROPDOWN_HOST_IFACE = 1477
    const val DROPDOWN_HOST = 896
    const val LAYOUT_ENUM = 7711
    val LAYOUT_ROWS: IntRange = 0..5
    const val VARP_DROPDOWN_OWNER = 4735
    const val VARP_DROPDOWN_ENUM = 4734
    const val VARP_DROPDOWN_SELECTED = 4736
    const val VARP_DROPDOWN_TYPE = 7754
    const val LAYOUT_DROPDOWN_TYPE = 5
    val DROPDOWN_CLOSE_VARPS: List<IntArray> = listOf(
        intArrayOf(12049, -1), intArrayOf(12050, 0), intArrayOf(7754, -1), intArrayOf(4736, -1), intArrayOf(4734, -1), intArrayOf(4735, -1),
    )

    private val openDropdown = java.util.Collections.synchronizedMap(java.util.WeakHashMap<WorldPlayer, Int>())
    fun openDropdownOwner(player: WorldPlayer): Int? = openDropdown[player]

    fun handleLayoutDropdown(player: WorldPlayer, packet: IfButtonN, build950: Boolean = c950): Boolean {
        if (!enabled) return false
        val iface = packet.interfaceId
        val comp = packet.component
        val isOwner = (iface == LAYOUT_IFACE && comp == LAYOUT_DROPDOWN_EDITOR_950 && build950) ||
            (iface == OPTIONS_IFACE && comp == LAYOUT_DROPDOWN_OPTIONS)
        if (isOwner) {
            if (!player.interfaces.isOpened(iface)) {
                logger.info { "options menu: ignored layout dropdown $iface:$comp from ${player.name}; interface not open" }
                return true
            }
            val owner = InterfaceHash(iface, comp).hash
            val w = player.client
            w.write(com.opennxt.net.game.serverprot.variables.VarpLarge(VARP_DROPDOWN_OWNER, owner))
            w.write(com.opennxt.net.game.serverprot.variables.VarpLarge(VARP_DROPDOWN_ENUM, LAYOUT_ENUM))
            w.write(com.opennxt.net.game.serverprot.variables.VarpSmall(VARP_DROPDOWN_SELECTED, 0))
            w.write(com.opennxt.net.game.serverprot.variables.VarpSmall(VARP_DROPDOWN_TYPE, LAYOUT_DROPDOWN_TYPE))
            openDropdown[player] = owner
            logger.info { "options menu: ${player.name} opened the layout dropdown ($iface:$comp)" }
            return true
        }
        if (iface != DROPDOWN_HOST_IFACE || comp != DROPDOWN_HOST) return false
        val owner = openDropdown[player] ?: return false
        if (packet.arg2 !in LAYOUT_ROWS) {
            logger.info { "options menu: ignored invalid layout row ${packet.arg2} from ${player.name} (valid $LAYOUT_ROWS)" }
            return true
        }
        openDropdown.remove(player)
        for (v in DROPDOWN_CLOSE_VARPS) player.client.write(com.opennxt.net.game.serverprot.variables.VarpSmall(v[0], v[1]))
        logger.info {
            "options menu: ${player.name} picked layout row ${packet.arg2} from ${owner ushr 16}:${owner and 0xffff}"
        }
        return true
    }

    const val SETTINGS_BUTTON = 15
    const val CUSTOMISATIONS_BUTTON = 6

    fun handleButton(player: WorldPlayer, packet: IfButtonN): Boolean {
        if (!enabled) return false
        if (packet.interfaceId != OPTIONS_IFACE) return false
        if (packet.component == LAYOUT_DROPDOWN_OPTIONS) return handleLayoutDropdown(player, packet)
        if (packet.component == CUSTOMISATIONS_BUTTON) {
            if (!player.interfaces.isOpened(OPTIONS_IFACE)) {
                logger.info { "options menu: ignored Customisations from ${player.name}; interface $OPTIONS_IFACE not open" }
                return true
            }
            val opened = ParentWindows.open(player, ParentWindows.CATEGORY_CUSTOMISATIONS, null)
            logger.info { "options menu: Customisations (1433:6) -> category ${ParentWindows.CATEGORY_CUSTOMISATIONS}: ${if (opened) "opened" else "not opened"}" }
            return true
        }
        if (packet.component == SETTINGS_BUTTON) {
            if (!player.interfaces.isOpened(OPTIONS_IFACE)) {
                logger.info { "options menu: ignored Settings from ${player.name}; interface $OPTIONS_IFACE not open" }
                return true
            }
            val opened = ParentWindows.open(player, ParentWindows.CATEGORY_SETTINGS, null)
            logger.info { "options menu: Settings ($OPTIONS_IFACE:$SETTINGS_BUTTON) -> category ${ParentWindows.CATEGORY_SETTINGS}: ${if (opened) "opened" else "not opened"}" }
            return true
        }
        if (packet.component != EDIT_LAYOUT_BUTTON) return false

        if (!player.interfaces.isOpened(OPTIONS_IFACE)) {
            logger.info {
                "options menu: ignored Edit Layout Mode from ${player.name}; interface $OPTIONS_IFACE not open"
            }
            return true
        }

        player.client.write(IfSethide(InterfaceHash(LAYOUT_IFACE, editorHiddenComponent()), true))
        player.client.write(IfSetgraphic(graphic = -1, component = InterfaceHash(LAYOUT_IFACE, editorGraphicComponent()).hash))

        player.interfaces.open(
            id = LAYOUT_IFACE, parent = LAYOUT_PARENT, component = LAYOUT_MOUNT,
            walkable = false, native949 = true
        )

        if (c950) {
            player.interfaces.events(id = LAYOUT_IFACE, component = 48, from = 0, to = 20, mask = 2)
            player.interfaces.events(id = LAYOUT_IFACE, component = 3, from = 0, to = 2008, mask = 2)
            player.interfaces.events(id = LAYOUT_IFACE, component = 39, from = 0, to = 20, mask = 2)
        } else {
            player.interfaces.events(id = LAYOUT_IFACE, component = 49, from = 0, to = 20, mask = 2)
            player.interfaces.events(id = LAYOUT_IFACE, component = 3, from = 0, to = 2008, mask = 2)
            player.interfaces.events(id = LAYOUT_IFACE, component = 40, from = 0, to = 20, mask = 2)
        }

        logger.info {
            "options menu: Edit Layout Mode ($OPTIONS_IFACE:${packet.component}) - opened " +
                "$LAYOUT_IFACE at $LAYOUT_PARENT:$LAYOUT_MOUNT"
        }
        return true
    }

    fun handleLayoutExit(player: WorldPlayer, packet: IfButtonN): Boolean {
        if (!enabled) return false
        if (packet.interfaceId != LAYOUT_IFACE) return false

        val saveAndExit = when (packet.component) {
            saveAndExitComponent() -> true
            LAYOUT_CLOSE -> false
            else -> return false
        }

        if (!player.interfaces.isOpened(LAYOUT_IFACE)) {
            logger.info {
                "options menu: ignored ${if (saveAndExit) "Save & Exit" else "Close"} from " +
                    "${player.name}; interface $LAYOUT_IFACE not open"
            }
            return true
        }

        if (saveAndExit) {
            player.client.write(com.opennxt.net.game.serverprot.variables.VarpSmall(VARP_SAVING_LAYOUT, 1))
            player.interfaces.open(
                id = SAVE_CONFIRM_IFACE, parent = LAYOUT_PARENT, component = SAVE_CONFIRM_MOUNT,
                walkable = true, native949 = true
            )
            player.interfaces.events(id = SAVE_CONFIRM_IFACE, component = 6, from = 65535, to = 65535, mask = 2)
            pendingSave[player] = true
            logger.info { "options menu: ${player.name} pressed Save & Exit; awaiting confirmation" }
            return true
        }
        if (pendingSave.remove(player) == true || player.interfaces.isOpened(SAVE_CONFIRM_IFACE))
            player.interfaces.close(id = LAYOUT_PARENT, component = SAVE_CONFIRM_MOUNT)
        sendEditorExitHalf(player)
        logger.info {
            "options menu: ${player.name} closed the layout editor without saving"
        }
        return true
    }
}

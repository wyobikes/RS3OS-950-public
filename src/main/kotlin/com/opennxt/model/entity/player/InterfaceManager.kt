package com.opennxt.model.entity.player

import com.opennxt.OpenNXT
import it.unimi.dsi.fastutil.ints.IntOpenHashSet

import com.opennxt.content.interfaces.InterfaceSlot
import com.opennxt.model.InterfaceHash
import com.opennxt.model.entity.BasePlayer
import com.opennxt.net.game.serverprot.ifaces.*
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap
import mu.KotlinLogging

class InterfaceManager(val player: BasePlayer) {
    companion object {
        val logger = KotlinLogging.logger {  }

        const val GAMEFRAME_949 = 1477

        const val PANEL_INTERACT_BITS =
            (1 shl 18) or (1 shl 19) or (1 shl 21) or (1 shl 23)

        const val PANEL_INTERACT_MASK = 9175040

        const val DROPPED = -1

        private val panelMount949: Map<Int, Int> = mapOf(
            98 to 103,
            109 to 114,
            284 to 300,
            295 to 311,
            327 to 343,
            338 to 354,
            360 to 376,
            371 to 387,
            382 to 398,
            393 to 409,
            475 to 501,
            486 to 512,
            497 to 523,
            508 to 545,
            519 to 556,
            404 to 420,
            415 to 431,
            425 to 441,
            435 to 451,
            445 to 461,
            455 to 471,
            465 to 481,
            142 to 136,
            153 to 147,
            164 to 158,
            175 to 169,
            186 to 180,
            197 to 191,
            208 to 202,
            219 to 213,
            230 to 224,
            241 to 235,
            252 to 246,
            263 to 257,
        )

        private val backpackGateEnabled: Boolean =
            System.getProperty("opennxt.experiment.backpackGate") != "false"

        fun remapKeys949(): Set<Int> = panelMount949.keys + hudRows.map { it.from }

        fun mountFor(parent: Int, component: Int): Int {
            if (!backpackGateEnabled || parent != GAMEFRAME_949) return component
            return panelMount949[component] ?: hudMountFor(component)
        }

        val hudMountMode: String =
            (System.getProperty("opennxt.experiment.hudMount") ?: "dock").trim().lowercase()

        private data class HudRow(val name: String, val from: Int, val slot: Int, val dock: Int, val iface: Int)

        private val hudRows: List<HudRow> = listOf(
            HudRow("ribbon", 59, 61, 64, 1431),
            HudRow("mainbar", 65, 67, 70, 1430),
            HudRow("bars", 70, 72, 75, 1670),
            HudRow("bars", 75, 77, 80, 1671),
            HudRow("bars", 80, 82, 85, 1672),
            HudRow("bars", 85, 87, 90, 1673),
            HudRow("minimap", 90, 94, 95, 1465),
            HudRow("camera", 91, 98, 96, 1919),
            HudRow("debuff", 568, 611, 613, 291),
            HudRow("slayer", 627, 674, 676, 1639),
            HudRow("death", 576, 619, 621, 1483),
            HudRow("area", 589, 632, 634, 745),
            HudRow("buff", 572, 615, 617, 284),
            HudRow("xp", 619, 598, 668, 1213),
            HudRow("target", 760, 594, 814, 1488),
        )

        val hudAnnounce: Boolean = System.getProperty("opennxt.experiment.hudMount.announce") == "true"

        val loginSuppressed: Map<Int, String> = linkedMapOf(
            ((568 shl 16) or 691) to "toplevel_v2_ribbon_extra - popup, behind the 'popups' row gate",
            ((598 shl 16) or 638) to "repoverlay_farming - player-owned farm overlay, not part of the login HUD",
            ((634 shl 16) or 739) to "Premier Pass - a Central Interface window, not a HUD panel",
            ((635 shl 16) or 615) to "Undead Army - a Necromancy Central Interface window",
            ((653 shl 16) or 797) to "event_crafting / Travelling Artisan - full-screen window with no working close button"
        )

        fun loginSuppressionReason(iface: Int, component: Int): String? =
            loginSuppressed[(iface shl 16) or component]

        val hudRowsEnabled: Set<String> = run {
            val raw = (System.getProperty("opennxt.experiment.hudMount.rows") ?: "all,popups").trim().lowercase()
            val given = raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
            val names = if ("all" in given) given - "all" + hudRows.map { it.name }.toSet() else given
            if ("bars" in names) names + "mainbar" else names
        }

        fun hudMountTable(): Map<Int, Int> = when (hudMountMode) {
            "slot" -> hudRows.filter { it.name in hudRowsEnabled }.associate { it.from to it.slot }
            "dock" -> hudRows.filter { it.name in hudRowsEnabled }.associate { it.from to it.dock }
            else -> emptyMap()
        }

        fun hudRowIfaces(): Map<Int, Int> = hudRows.associate { it.iface to it.dock }

        fun hudMountTableAllRows(): Map<Int, Int> = when (hudMountMode) {
            "slot" -> hudRows.associate { it.from to it.slot }
            "dock" -> hudRows.associate { it.from to it.dock }
            else -> emptyMap()
        }

        private fun hudMountFor(component: Int): Int = hudMountTable()[component] ?: component

        fun panelMountTable(): Map<Int, Int> = panelMount949

        val panelRemap949: Map<Int, Int> = mapOf(
            142 to 1458,
            153 to 1460,
            164 to 1452,
            175 to 1461,
            186 to 1884,
            197 to 1885,
            208 to 1887,
            219 to 1886,
            230 to 1219,
            241 to 1220,
            252 to 1221,
            263 to 1883,
        )

        val panelDrop949: Set<Int> = setOf(131)

        val panelRemapEnabled: Boolean
            get() = System.getProperty("opennxt.world.panelRemap") != "false"

        val mountArmEnabled: Boolean
            get() = System.getProperty("opennxt.experiment.ui.mountArm") == "true"

        const val MOUNT_WALKABLE_MASK = 1

        val MOUNT_ARM_SKIP_1477: Set<Int> = setOf(8, 24, 25, 47, 793, 798, 809)

        const val MOUNT_SLOT_SELF = 65535

        val defensivePanel1880: Boolean
            get() = System.getProperty("opennxt.experiment.defensivePanel1880") == "true"

        const val DEFENSIVE_PANEL_PROVENANCE: String =
            "Defensive panel at 1477:263 uses interface 1883; -Dopennxt.experiment.defensivePanel1880=true selects 1880."

        fun panelRemapTable(): Map<Int, Int> =
            if (defensivePanel1880) panelRemap949 + (263 to 1880) else panelRemap949

        val panelReplay919: Map<Int, Int> = mapOf(
            131 to 1458,
            142 to 1460,
            153 to 1881,
            164 to 1888,
            175 to 1452,
            186 to 1461,
            197 to 1884,
            208 to 1885,
            219 to 1887,
            230 to 1886,
            241 to 1883,
            252 to 1449,
            263 to 1882,
        )

        fun panelEventRemap(): Map<Int, Int> {
            val remap = panelRemapTable()
            val out = LinkedHashMap<Int, Int>()
            for ((slot, replayId) in panelReplay919) {
                val corrected = remap[slot] ?: continue
                if (corrected != replayId) out[replayId] = corrected
            }
            return out
        }

        fun panelEventDrop(): Set<Int> {
            val remap = panelRemapTable()
            return panelReplay919.entries
                .filter { it.key in panelDrop949 }
                .map { it.value }
                .filter { id -> panelReplay919.none { e -> e.value == id && remap.containsKey(e.key) } }
                .toSet()
        }

        fun eventInterfaceFor(id: Int): Int? {
            if (!panelRemapEnabled) return id
            if (id in panelEventDrop()) return null
            return panelEventRemap()[id] ?: id
        }

        val panelBarComponentEnabled: Boolean
            get() = System.getProperty("opennxt.experiment.panelBarComponent") == "true"

        private val abilityTriple: Map<Int, IntArray?> = mapOf(
            1460 to intArrayOf(5, 6, 7),
            1449 to intArrayOf(7, 8, 11),
            1452 to intArrayOf(7, 8, 11),
            1461 to intArrayOf(7, 8, 11),
            1882 to intArrayOf(7, 8, 11),
            1883 to intArrayOf(7, 8, 11),
            1884 to intArrayOf(7, 8, 11),
            1885 to intArrayOf(7, 8, 11),
            1886 to intArrayOf(7, 8, 11),
            1887 to intArrayOf(7, 8, 11),
            1219 to intArrayOf(7, 8, 11),
            1220 to intArrayOf(7, 8, 11),
            1221 to intArrayOf(7, 8, 11),
            1880 to intArrayOf(7, 8, 11),
            1458 to null,
            1881 to intArrayOf(5, 6, 7),
            1888 to intArrayOf(5, 6, 7),
        )

        private fun tripleOf(id: Int): IntArray? = abilityTriple[id]

        const val PANEL_BAR_PROVENANCE: String =
            "Panel bar component remap (1888:5 -> 1452:7) is off; -Dopennxt.experiment.panelBarComponent=true to enable."

        fun barComponentFor(from: Int, to: Int, component: Int): Int {
            if (!panelBarComponentEnabled || from == to) return component
            val src = tripleOf(from) ?: return component
            val dst = tripleOf(to) ?: return component
            val i = src.indexOf(component)
            return if (i < 0) component else dst[i]
        }
    }

    private var root: OpenedInterface? = null

    fun openedCount(): Int = openedIds.size

    fun isOpened(id: Int): Boolean {
        return root?.findChild(id) != null
    }

    fun openTop(id: Int) {
        if (interfaceMode == "none") {
            logger.info("interfaces=none: skipping root interface $id")
            return
        }
        logger.info("opening root interface ${com.opennxt.resources.Names949.iface(id)}")
        if (root != null) {
        }

        root = OpenedInterface(id, walkable = true)
        player.client.write(IfOpenTop(id))
    }

    fun adoptTop(id: Int) {
        logger.info("adopting root interface ${com.opennxt.resources.Names949.iface(id)} from the replay")
        root = OpenedInterface(id, walkable = true)
    }

    fun adoptSub(id: Int, parent: Int, component: Int, walkable: Boolean): Boolean {
        val root = root ?: run { logger.warn("adoptSub($id on $parent:$component) before any root - ignored"); return false }
        val host = root.findChild(parent) ?: run { logger.warn("adoptSub($id on $parent:$component): parent not in the tree - ignored"); return false }
        host.children[component] = OpenedInterface(id, walkable = walkable)
        openedIds.add(id)
        return true
    }

    fun hasSubAt(id: Int, component: Int): Boolean {
        val base = root?.findChild(id) ?: return false
        return base.children.containsKey(component)
    }

    fun close(id: Int, component: Int, native949: Boolean = false) {
        val base = root?.findChild(id) ?: return

        val slot = if (native949) component else mountFor(id, component)

        if (!base.children.containsKey(slot)) return

        val removed = base.children.remove(slot)
        logger.info("Closed interface $removed at $id:$slot")

        player.client.write(IfClosesub(InterfaceHash(id, slot)))
    }

    private val skipInterfaces: Set<Int> =
        (System.getProperty("opennxt.world.skipInterfaces") ?: "")
            .split(',').mapNotNull { it.trim().toIntOrNull() }.toSet()

    private val interfaceMode: String =
        (System.getProperty("opennxt.world.interfaces") ?: "all").lowercase()

    private val definedInterfaceIds: IntOpenHashSet? by lazy {
        try {
            val table = OpenNXT.filesystem.getReferenceTable(3)
                ?: throw IllegalStateException("no reference table for index 3")
            val ids = IntOpenHashSet(table.archives.keys)
            if (ids.isEmpty()) throw IllegalStateException("index 3 has no archives")
            logger.info("Interface guard: ${ids.size} interfaces defined in the cache")
            ids
        } catch (t: Throwable) {
            logger.warn("Interface guard disabled: could not read index 3 (${t.message})")
            null
        }
    }

    private val panelRemap = Int2ObjectOpenHashMap<Int>()

    private val openedIds = IntOpenHashSet()

    private val armedIds = IntOpenHashSet()

    private val dragArmedIds = IntOpenHashSet()

    private fun interfaceExists(id: Int): Boolean =
        definedInterfaceIds?.contains(id) ?: true

    fun open(
        id: Int,
        parent: Int = -1,
        component: Int,
        walkable: Boolean = false,
        native949: Boolean = false
    ) {
        if (interfaceMode == "hud" || interfaceMode == "none") {
            logger.info("interfaces=$interfaceMode: skipping interface $id (parent $parent component $component)")
            return
        }
        if (id in skipInterfaces) {
            logger.info("skipInterfaces: skipping interface $id (parent $parent component $component)")
            return
        }
        var effectiveId = id
        if (!native949 && panelRemapEnabled && parent == 1477) {
            if (component in panelDrop949) {
                logger.info("Panel remap: no equivalent for interface $id at 1477:$component, not opening")
                panelRemap[id] = DROPPED
                return
            }
            panelRemapTable()[component]?.let { corrected ->
                if (corrected != id) {
                    logger.info("Panel remap: interface $id -> $corrected at 1477:$component")
                    effectiveId = corrected
                    panelRemap[id] = corrected
                    val predicted = panelEventRemap()[id]
                    if (predicted != corrected) {
                        logger.warn("Panel remap mismatch at 1477:$component: event table maps $id -> " +
                            "$predicted, open used $corrected")
                    }
                }
            }
        }

        if (!interfaceExists(effectiveId)) {
            logger.warn("Interface guard: interface $effectiveId is not in the cache, skipping open on $parent:$component")
            return
        }

        val effectiveComponent = if (native949) component else mountFor(parent, component)
        if (effectiveComponent != component) {
            logger.info("Mount remap: interface $effectiveId moved from 1477:$component to 1477:$effectiveComponent")
        } else if (parent == 1477) {
            val dummyMount = when (component) {
                98 -> 103
                109 -> 114
                284 -> 300
                else -> -1
            }
            if (dummyMount != -1) {
                player.client.write(com.opennxt.net.game.serverprot.ifaces.IfOpenSub(1448, walkable, com.opennxt.model.InterfaceHash(1477, dummyMount)))
                logger.info("Padlock bypass: mounted dummy interface 1448 on 1477:$dummyMount for panel $component")
            }
        }

        logger.info(
            "opening interface ${com.opennxt.resources.Names949.iface(effectiveId)}" +
                (if (effectiveId != id) " (remapped from $id)" else "") +
                " on parent ${if (parent == -1) "<root>" else parent} component $effectiveComponent" +
                (if (effectiveComponent != component) " (remapped from $component)" else "")
        )

        val root = root
            ?: throw IllegalStateException("Cannot open an interface before the root interface for player ${player.name}")

        val parentId = if (parent == -1) root.id else parent

        val child = root.findChild(parentId)
            ?: throw IllegalStateException("Cannot open interface $id: parent $parent is not open for player ${player.name}")

        if (child.children[effectiveComponent] != null)
            logger.warn("Overriding an interface on ${child.id}:$effectiveComponent with interface $id for player ${player.name}")
            
        child.children[effectiveComponent] = OpenedInterface(effectiveId, walkable = walkable)
        openedIds.add(effectiveId)

        player.client.write(IfOpenSub(effectiveId, walkable, InterfaceHash(parentId, effectiveComponent)))

        if (mountArmEnabled && !(parentId == 1477 && effectiveComponent in MOUNT_ARM_SKIP_1477)) {
            events(parentId, effectiveComponent, MOUNT_SLOT_SELF, MOUNT_SLOT_SELF,
                MOUNT_WALKABLE_MASK)
        }
    }

    fun armAllOpenComponents(mask: Int = 2046) {
        val table = try {
            OpenNXT.filesystem.getReferenceTable(3)
        } catch (t: Throwable) {
            logger.warn("armAll: cannot read index 3 (${t.message}); nothing armed")
            return
        } ?: return

        var interfaces = 0
        var components = 0
        for (id in openedIds.toIntArray().sortedArray()) {
            val archive = try { table.loadArchive(id) } catch (t: Throwable) { null } ?: continue
            val comps = archive.files.keys
            if (comps.isEmpty()) continue
            interfaces++
            for (c in comps) {
                events(id = id, component = c, from = 65535, to = 65535, mask = mask)
                components++
            }
        }
        logger.info(
            "armAll: IF_SETEVENTS sent for $components component(s) across $interfaces interface(s), mask $mask"
        )
    }

    fun armPanelInteractivity(mode: String) {
        val table = try {
            OpenNXT.filesystem.getReferenceTable(3)
        } catch (t: Throwable) {
            logger.warn("armPanels: cannot read index 3 (${t.message}); nothing armed")
            return
        } ?: return

        val topUp = mode == "all"
        var interfaces = 0
        var components = 0
        val touched = ArrayList<Int>()

        var notReallyOpen = 0
        for (id in openedIds.toIntArray().sortedArray()) {
            if (!isOpened(id)) { notReallyOpen++; continue }

            val neverArmed = !armedIds.contains(id)
            val optionsOnly = armedIds.contains(id) && !dragArmedIds.contains(id)
            if (!neverArmed && !(topUp && optionsOnly)) continue

            val archive = try { table.loadArchive(id) } catch (t: Throwable) { null } ?: continue
            val comps = archive.files.keys
            if (comps.isEmpty()) continue

            val mask = if (neverArmed) PANEL_INTERACT_MASK or 2046 else PANEL_INTERACT_MASK

            interfaces++
            touched.add(id)
            for (c in comps) {
                events(id = id, component = c, from = 65535, to = 65535, mask = mask)
                components++
            }
        }

        logger.info(
            "armPanels[$mode]: armed $components component(s) across $interfaces panel(s) $touched; " +
                "not open: $notReallyOpen, already armed: ${openedIds.size - interfaces - notReallyOpen}"
        )
    }

    fun open(slot: InterfaceSlot, id: Int, offset: Int = 0, walkable: Boolean = false) {
        this.open(id, slot.parent, slot.component + offset, walkable)
    }

    fun close(slot: InterfaceSlot, offset: Int = 0) {
        this.close(slot.parent, slot.component + offset)
    }

    fun events(
        id: Int,
        component: Int,
        from: Int,
        to: Int,
        mask: Int,
        native949: Boolean = false
    ) {
        var effectiveId = id
        var effectiveComponent = component
        if (!native949 && panelRemapEnabled) {
            val seenValues = panelRemap[id]
            val corrected = if (seenValues != null) {
                if (seenValues == DROPPED) null else seenValues
            } else {
                eventInterfaceFor(id)
            }
            if (corrected == null) {
                logger.info("Panel remap: dropping IF_SETEVENTS for interface $id:$component")
                return
            }
            if (corrected != id) {
                effectiveId = corrected
                effectiveComponent = barComponentFor(id, corrected, component)
                logger.info("Panel remap: IF_SETEVENTS $id:$component -> $effectiveId:$effectiveComponent")
            }
        }

        if (!interfaceExists(effectiveId)) {
            logger.warn("Interface guard: skipped IF_SETEVENTS for undefined interface $effectiveId:$effectiveComponent")
            return
        }
        if (!isOpened(effectiveId)) {
            logger.warn("IF_SETEVENTS for interface $effectiveId:$effectiveComponent, which is not open")
        }
        armedIds.add(effectiveId)
        if (mask and PANEL_INTERACT_BITS != 0) dragArmedIds.add(effectiveId)
        if (effectiveId == 1477) armed1477Components.add(effectiveComponent)
        armedMasks[(effectiveId.toLong() shl 32) or effectiveComponent.toLong()] = mask
        player.client.write(IfSetevents(InterfaceHash(effectiveId, effectiveComponent), from, to, mask))
    }

    fun text(id: Int, component: Int, text: String) {
        player.client.write(IfSettext(InterfaceHash(id, component), text))
    }

    fun hide(id: Int, component: Int, hidden: Boolean) {
        player.client.write(IfSethide(InterfaceHash(id, component), hidden))
    }

    fun model(id: Int, component: Int, kind: Int, value: Int) {
        val hash = InterfaceHash(id, component)
        when (kind) {
            1 -> player.client.write(IfModelK1(hash, value))
            2 -> player.client.write(IfModelK2(hash, value))
            3 -> player.client.write(IfModelK3Self(hash))
            5 -> player.client.write(IfModelK5Self(hash))
            else -> throw IllegalArgumentException("no attribute-4 opcode for model kind $kind")
        }
    }

    val armed1477Components: MutableSet<Int> = HashSet()

    private val armedMasks = HashMap<Long, Int>()

    fun armedMaskFor(id: Int, component: Int): Int? =
        armedMasks[(id.toLong() shl 32) or component.toLong()]

    fun markModalsClosedByClient(): List<String> {
        val root = root ?: return emptyList()
        val dropped = mutableListOf<String>()

        fun iterateOver(iface: OpenedInterface) {
            val toClose = it.unimi.dsi.fastutil.ints.IntArrayList()
            iface.children.forEach { (id, child) ->
                if (!child.walkable) {
                    toClose.add(id)
                } else {
                    iterateOver(child)
                }
            }
            for (i in 0 until toClose.size) {
                val slot = toClose.getInt(i)
                val removed = iface.children.remove(slot)
                dropped.add("${removed?.id} at ${iface.id}:$slot")
            }
        }

        iterateOver(root)
        return dropped
    }

    fun closeModals(): Boolean {
        val root = root ?: return false
        var closedAny = false

        fun iterateOver(iface: OpenedInterface) {
            val toClose = it.unimi.dsi.fastutil.ints.IntArrayList()
            iface.children.forEach { (id, child) ->
                if (!child.walkable) {
                    toClose.add(id)
                } else {
                    iterateOver(child)
                }
            }
            for (i in 0 until toClose.size) {
                close(iface.id, toClose.getInt(i))
                closedAny = true
            }
        }

        iterateOver(root)
        return closedAny
    }

    private data class OpenedInterface(
        val id: Int,
        val children: Int2ObjectOpenHashMap<OpenedInterface> = Int2ObjectOpenHashMap(),
        val walkable: Boolean
    ) {
        fun findChild(id: Int): OpenedInterface? {
            if (this.id == id) return this
            for (child in children) {
                return child.value.findChild(id) ?: continue
            }
            return null
        }
    }

}

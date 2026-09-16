package com.opennxt.model.world

import com.opennxt.net.game.serverprot.generated.VorbisSound
import mu.KotlinLogging

object GateSwing {
    private val logger = KotlinLogging.logger { }

    val enabled: Boolean
        get() = System.getProperty("opennxt.experiment.gates.swing") != "off"

    val DIR = arrayOf(-1 to 0, 0 to 1, 1 to 0, 0 to -1)

    const val SOUND_OPEN = 3932
    const val SOUND_CLOSE = 3935

    const val SOUND_RATE = 256

    data class Leaf(val x: Int, val y: Int, val shape: Int, val rot: Int, val id: Int)
    data class Placement(val x: Int, val y: Int, val rot: Int)

    data class OpenGate(
        val plane: Int,
        val hinge: Leaf,
        val partner: Leaf,
        val hingeOpen: Leaf,
        val partnerOpen: Leaf
    ) {
        val tiles: List<Pair<Int, Int>>
            get() = listOf(hinge.x to hinge.y, partner.x to partner.y, hingeOpen.x to hingeOpen.y, partnerOpen.x to partnerOpen.y)
    }

    fun hingeOf(a: Leaf, b: Leaf): Pair<Leaf, Leaf>? {
        if (a.rot != b.rot || a.shape != b.shape) return null
        val d = DIR[(a.rot + 1) and 3]
        if (b.x == a.x + d.first && b.y == a.y + d.second) return a to b
        if (a.x == b.x + d.first && a.y == b.y + d.second) return b to a
        return null
    }

    fun openPlacements(hinge: Leaf): Pair<Placement, Placement> {
        val d = DIR[hinge.rot and 3]
        val r = (hinge.rot + 3) and 3
        return Placement(hinge.x + d.first, hinge.y + d.second, r) to
            Placement(hinge.x + 2 * d.first, hinge.y + 2 * d.second, r)
    }

    private val openGates = LinkedHashMap<Triple<Int, Int, Int>, OpenGate>()

    private fun key(plane: Int, x: Int, y: Int) = Triple(plane, x, y)

    fun gateAt(plane: Int, x: Int, y: Int): OpenGate? = synchronized(openGates) { openGates[key(plane, x, y)] }

    fun openLeafAt(plane: Int, x: Int, y: Int, locId: Int): Leaf? {
        val g = gateAt(plane, x, y) ?: return null
        return listOf(g.hingeOpen, g.partnerOpen).firstOrNull { it.x == x && it.y == y && it.id == locId }
    }

    fun openCount(): Int = synchronized(openGates) { openGates.values.distinct().size }

    fun isSwungAt(plane: Int, x: Int, y: Int): Boolean = gateAt(plane, x, y) != null || doorAt(plane, x, y) != null

    internal fun clear() {
        synchronized(openGates) { openGates.clear() }
        synchronized(openDoors) { openDoors.clear() }
    }

    fun open(player: WorldPlayer?, plane: Int, a: Leaf, b: Leaf, aOpenId: Int, bOpenId: Int): OpenGate? {
        if (!enabled) return null
        gateAt(plane, a.x, a.y)?.let { existing ->
            logger.info { "gate swing: gate at (${a.x},${a.y},plane $plane) is already open" }
            return existing
        }
        val (hinge, partner) = hingeOf(a, b) ?: run {
            logger.info {
                "gate swing: leaves ${a.id}@(${a.x},${a.y}) r${a.rot} and ${b.id}@(${b.x},${b.y}) r${b.rot} " +
                    "are not a recognised pair; not swung"
            }
            return null
        }
        val hingeOpenId = if (hinge === a) aOpenId else bOpenId
        val partnerOpenId = if (hinge === a) bOpenId else aOpenId
        val (p1, p2) = openPlacements(hinge)
        val removed1 = LocChanges.remove(plane, hinge.x, hinge.y, hinge.shape, hinge.rot, hinge.id, forcedSwingMode = DoorSwing.Mode.OFF)
        val removed2 = LocChanges.remove(plane, partner.x, partner.y, partner.shape, partner.rot, partner.id, forcedSwingMode = DoorSwing.Mode.OFF)
        val placed1 = LocChanges.place(plane, p1.x, p1.y, hinge.shape, p1.rot, hingeOpenId)
        val placed2 = LocChanges.place(plane, p2.x, p2.y, hinge.shape, p2.rot, partnerOpenId)
        if (removed1 == null || removed2 == null || placed1 == null || placed2 == null) {
            logger.warn { "gate swing: loc packets unavailable on this build; gate at (${hinge.x},${hinge.y}) not swung" }
            return null
        }
        val gate = OpenGate(
            plane, hinge, partner,
            Leaf(p1.x, p1.y, hinge.shape, p1.rot, hingeOpenId),
            Leaf(p2.x, p2.y, hinge.shape, p2.rot, partnerOpenId)
        )
        synchronized(openGates) { for ((x, y) in gate.tiles) openGates[key(plane, x, y)] = gate }
        sound(player, SOUND_OPEN)
        logger.info {
            "gate swing: opened hinge ${hinge.id}@(${hinge.x},${hinge.y}) r${hinge.rot} + ${partner.id}@(${partner.x},${partner.y}) -> " +
                "$hingeOpenId@(${p1.x},${p1.y}) r${p1.rot} + $partnerOpenId@(${p2.x},${p2.y}) r${p2.rot} (plane $plane)"
        }
        return gate
    }

    fun close(player: WorldPlayer?, plane: Int, x: Int, y: Int): OpenGate? {
        if (!enabled) return null
        val gate = gateAt(plane, x, y) ?: return null
        LocChanges.revert(plane, gate.hingeOpen.x, gate.hingeOpen.y, gate.hingeOpen.shape)
        LocChanges.revert(plane, gate.partnerOpen.x, gate.partnerOpen.y, gate.partnerOpen.shape)
        LocChanges.revert(plane, gate.hinge.x, gate.hinge.y, gate.hinge.shape)
        LocChanges.revert(plane, gate.partner.x, gate.partner.y, gate.partner.shape)
        synchronized(openGates) { for ((tx, ty) in gate.tiles) openGates.remove(key(plane, tx, ty)) }
        sound(player, SOUND_CLOSE)
        logger.info {
            "gate swing: closed ${gate.hingeOpen.id}@(${gate.hingeOpen.x},${gate.hingeOpen.y}) + " +
                "${gate.partnerOpen.id}@(${gate.partnerOpen.x},${gate.partnerOpen.y}) -> " +
                "${gate.hinge.id}@(${gate.hinge.x},${gate.hinge.y}) r${gate.hinge.rot} + ${gate.partner.id}@(${gate.partner.x},${gate.partner.y}) (plane $plane)"
        }
        return gate
    }

    const val DOOR_SOUND_OPEN = 3933
    const val DOOR_SOUND_CLOSE = 3934

    data class OpenDoor(val plane: Int, val shut: Leaf, val open: Leaf) {
        val tiles: List<Pair<Int, Int>> get() = listOf(shut.x to shut.y, open.x to open.y)
    }

    fun doorOpenPlacement(shut: Leaf): Placement {
        val d = DIR[shut.rot and 3]
        return Placement(shut.x + d.first, shut.y + d.second, (shut.rot + 1) and 3)
    }

    private val openDoors = LinkedHashMap<Triple<Int, Int, Int>, OpenDoor>()

    fun doorAt(plane: Int, x: Int, y: Int): OpenDoor? = synchronized(openDoors) { openDoors[key(plane, x, y)] }

    fun openDoorLeafAt(plane: Int, x: Int, y: Int, locId: Int): Leaf? {
        val d = doorAt(plane, x, y) ?: return null
        return if (d.open.x == x && d.open.y == y && d.open.id == locId) d.open else null
    }

    fun openDoorCount(): Int = synchronized(openDoors) { openDoors.values.distinct().size }

    fun openDoor(player: WorldPlayer?, plane: Int, shut: Leaf, openId: Int): OpenDoor? {
        if (!enabled) return null
        doorAt(plane, shut.x, shut.y)?.let { return it }
        val p = doorOpenPlacement(shut)
        val removed = LocChanges.remove(plane, shut.x, shut.y, shut.shape, shut.rot, shut.id, forcedSwingMode = DoorSwing.Mode.OFF)
        val placed = LocChanges.place(plane, p.x, p.y, shut.shape, p.rot, openId)
        if (removed == null || placed == null) {
            logger.warn { "door swing: loc packets unavailable on this build; door at (${shut.x},${shut.y}) not swung" }
            return null
        }
        val door = OpenDoor(plane, shut, Leaf(p.x, p.y, shut.shape, p.rot, openId))
        synchronized(openDoors) { for ((x, y) in door.tiles) openDoors[key(plane, x, y)] = door }
        sound(player, DOOR_SOUND_OPEN)
        logger.info { "door swing: opened ${shut.id}@(${shut.x},${shut.y}) r${shut.rot} -> $openId@(${p.x},${p.y}) r${p.rot} (plane $plane)" }
        return door
    }

    fun closeDoor(player: WorldPlayer?, plane: Int, x: Int, y: Int): OpenDoor? {
        if (!enabled) return null
        val door = doorAt(plane, x, y) ?: return null
        LocChanges.revert(plane, door.open.x, door.open.y, door.open.shape)
        LocChanges.revert(plane, door.shut.x, door.shut.y, door.shut.shape)
        synchronized(openDoors) { for ((tx, ty) in door.tiles) openDoors.remove(key(plane, tx, ty)) }
        sound(player, DOOR_SOUND_CLOSE)
        logger.info { "door swing: closed ${door.open.id}@(${door.open.x},${door.open.y}) -> ${door.shut.id}@(${door.shut.x},${door.shut.y}) r${door.shut.rot} (plane $plane)" }
        return door
    }

    private fun sound(player: WorldPlayer?, sound: Int) {
        if (player == null) return
        runCatching { player.client.write(VorbisSound(sound = sound, count = 1, delay = 0, volume = 255, extra = SOUND_RATE)) }
            .onFailure { logger.warn(it) { "gate swing: VORBIS_SOUND $sound not sent" } }
    }
}

package com.opennxt.model.world

object DoorSwing {
    private val DIR = arrayOf(-1 to 0, 0 to 1, 1 to 0, 0 to -1)

    enum class Mode(val id: String, val why: String) {
        OFF("off", "same tile and rotation"),
        LEFT("left", "one tile along DIR[(rot+1)&3], rotation unchanged"),
        RIGHT("right", "one tile along DIR[(rot+3)&3], rotation unchanged"),
        TURN("turn", "same tile, rotation (rot+1)&3"),
        TURN_BACK("turnback", "same tile, rotation (rot-1)&3"),
    }

    data class Placement(val x: Int, val y: Int, val rotation: Int, val mode: Mode)

    val mode: Mode
        get() {
            val want = System.getProperty("opennxt.experiment.doors.swing") ?: return Mode.OFF
            return Mode.values().firstOrNull { it.id.equals(want, ignoreCase = true) } ?: Mode.OFF
        }

    val PROVENANCE: String =
        "[door swing: -Dopennxt.experiment.doors.swing=off|left|right|turn|turnback]"

    fun placementFor(shape: Int, x: Int, y: Int, rotation: Int): Placement {
        val m = mode
        if (m == Mode.OFF || shape !in WALL_SHAPES) return Placement(x, y, rotation, Mode.OFF)
        return when (m) {
            Mode.LEFT -> DIR[(rotation + 1) and 3].let { Placement(x + it.first, y + it.second, rotation, m) }
            Mode.RIGHT -> DIR[(rotation + 3) and 3].let { Placement(x + it.first, y + it.second, rotation, m) }
            Mode.TURN -> Placement(x, y, (rotation + 1) and 3, m)
            Mode.TURN_BACK -> Placement(x, y, (rotation + 3) and 3, m)
            Mode.OFF -> Placement(x, y, rotation, Mode.OFF)
        }
    }

    val WALL_SHAPES = setOf(0, 1, 2, 3, 9)
}

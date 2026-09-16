package com.opennxt.net.game.handlers

import com.opennxt.model.entity.BasePlayer
import java.util.Collections
import java.util.WeakHashMap

object ClientEventState {
    data class State(
        var focused: Int = -1,
        var cameraAngle1: Int = -1,
        var cameraAngle2: Int = -1,
        var cameraPackets: Long = 0,

        var mouseX: Int = -1,
        var mouseY: Int = -1,
        var mouseNotLeftButton: Boolean = false,
        var mousePackets: Long = 0
    )

    private val states: MutableMap<BasePlayer, State> =
        Collections.synchronizedMap(WeakHashMap<BasePlayer, State>())

    fun of(player: BasePlayer): State = synchronized(states) { states.getOrPut(player) { State() } }
}

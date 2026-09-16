package com.opennxt.content.impl

import com.opennxt.model.entity.movement.RunEnergy
import com.opennxt.model.world.WorldPlayer
import com.opennxt.net.game.clientprot.IfButtonN
import com.opennxt.net.game.serverprot.generated.UpdateRunenergy
import com.opennxt.net.game.serverprot.variables.VarpSmall
import mu.KotlinLogging

object RunToggle {
    private val logger = KotlinLogging.logger { }

    const val ORB_INTERFACE = 326

    const val ORB_COMPONENT = 1

    const val TOGGLE_OP = 6

    fun install() {
        logProvenanceOnce()
    }

    @Volatile
    private var provenanceLogged = false

    private fun logProvenanceOnce() {
        if (provenanceLogged) return
        provenanceLogged = true
        logger.info { RunEnergy.PROVENANCE }
    }

    fun isToggleFrame(interfaceId: Int, component: Int, buttonOp: Int): Boolean =
        interfaceId == ORB_INTERFACE && component == ORB_COMPONENT && buttonOp == TOGGLE_OP

    fun handleButton(player: WorldPlayer, packet: IfButtonN): Boolean {
        if (!isToggleFrame(packet.interfaceId, packet.component, packet.buttonOp)) return false
        set(player, !player.entity.runEnergy.toggled, "orb click 326:1 op 6")
        return true
    }

    fun set(player: WorldPlayer, on: Boolean, why: String): Boolean {
        val energy = player.entity.runEnergy
        val before = energy.toggled
        energy.setToggled(on)
        sendVarp(player)
        logger.info {
            "run toggle: ${if (before) "run" else "walk"} -> ${if (on) "run" else "walk"} " +
                "($why); reserve ${energy.tenths} tenths = ${energy.displayed}%"
        }
        return on
    }

    fun sendVarp(player: WorldPlayer) {
        player.client.write(VarpSmall(RunEnergy.RUN_VARP, if (player.entity.runEnergy.toggled) 1 else 0))
    }

    fun sendLogin(player: WorldPlayer) {
        logProvenanceOnce()
        val energy = player.entity.runEnergy
        val value = energy.displayed
        player.client.write(UpdateRunenergy(energy = value))
        energy.markSent(value)
        sendVarp(player)
    }

    fun tick(player: WorldPlayer, ranThisTick: Boolean): Int? {
        val energy = player.entity.runEnergy
        energy.tick(ranThisTick)
        val send = energy.takeSend() ?: return null
        player.client.write(UpdateRunenergy(energy = send))
        return send
    }
}

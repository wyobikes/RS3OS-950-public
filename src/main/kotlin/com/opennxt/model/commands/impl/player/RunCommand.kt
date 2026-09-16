package com.opennxt.model.commands.impl.player

import com.opennxt.content.impl.RunToggle
import com.opennxt.model.commands.CommandSender
import com.opennxt.model.commands.SimpleCommand
import com.opennxt.model.entity.movement.RunEnergy
import com.opennxt.model.world.WorldPlayer

object RunCommand : SimpleCommand() {
    override fun execute(sender: CommandSender, alias: String, command: String) {
        if (sender !is WorldPlayer) {
            sender.error("This command can only be used by a player in the world.")
            return
        }
        val args = command.split(" ").drop(1).filter { it.isNotBlank() }
        val energy = sender.entity.runEnergy
        when (args.firstOrNull()?.lowercase()) {
            null, "toggle" -> RunToggle.set(sender, !energy.toggled, "::run")
            "on", "run", "true" -> RunToggle.set(sender, true, "::run on")
            "off", "walk", "false" -> RunToggle.set(sender, false, "::run off")
            "energy" -> {
                if (!sender.hasPermissions("spawn-item")) {
                    sender.error("You need the 'spawn-item' power to set your run energy directly.")
                    return
                }
                val pct = args.getOrNull(1)?.toIntOrNull()
                if (pct == null || pct !in 0..100) {
                    sender.error("Usage: ::run energy <0..100>")
                    return
                }
                energy.restore(pct * RunEnergy.TENTHS_PER_POINT, energy.toggled)
                energy.markSent(-1)
            }
            else -> {
                sender.error("Usage: ::run [on|off|toggle|energy <0..100>]")
                return
            }
        }
        sender.console(
            "run ${if (energy.toggled) "ON" else "OFF"}, energy ${energy.displayed}% " +
                "(${energy.tenths} tenths; drain ${energy.drainTenths}/run tick, " +
                "regen ${energy.regenTenths}/tick)"
        )
    }
}

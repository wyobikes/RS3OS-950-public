package com.opennxt.model.commands.impl.player

import com.opennxt.content.skills.SkillBones
import com.opennxt.content.skills.SkillInteractions
import com.opennxt.model.commands.CommandSender
import com.opennxt.model.commands.SimpleCommand

object SkillsCommand : SimpleCommand() {
    override fun execute(sender: CommandSender, alias: String, command: String) {
        val filter = command.trim().lowercase()
        val lines = SkillBones.describe().filter { filter.isEmpty() || it.lowercase().startsWith(filter) }
        if (lines.isEmpty()) { sender.error("No skill matches '$filter'. Usage: ::skills [name]"); return }
        lines.forEach { sender.console(it) }
        val name = (sender as? com.opennxt.model.entity.BasePlayer)?.name
        if (name != null) {
            val task = SkillInteractions.slayerTaskFor(name)
            sender.console(if (task != null && task.left > 0) "Slayer task: ${task.left} ${task.monster} (from ${task.master})" else "Slayer task: none")
        }
    }
}

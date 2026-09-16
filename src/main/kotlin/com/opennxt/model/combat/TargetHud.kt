package com.opennxt.model.combat

import com.opennxt.model.world.WorldNpc
import com.opennxt.model.world.WorldPlayer
import com.opennxt.net.game.GamePacket
import com.opennxt.net.game.serverprot.RunClientScript
import com.opennxt.net.game.serverprot.generated.SetTarget
import com.opennxt.net.game.serverprot.ifaces.IfClosesub
import com.opennxt.net.game.serverprot.ifaces.IfOpensubActiveNpc
import com.opennxt.net.game.serverprot.variables.ClientSetvarcSmall

object TargetHud {
    const val TARGET_TYPE_NPC = 1

    const val NO_TARGET = 0

    const val VARC_TARGET_HEALTH_PERCENT = 2059

    const val SCRIPT_TARGET_INFO = 82

    val enabled: Boolean
        get() = System.getProperty("opennxt.experiment.combat.targethud") != "false"

    fun healthPercent(current: Int, maximum: Int): Int {
        if (maximum <= 0) return 0
        return (100 * current.coerceIn(0, maximum) / maximum).coerceIn(0, 100)
    }

    fun targetField(infoIndex: Int): Int =
        if (setTargetTypeFirst()) ((infoIndex and 0xffff) shl 8) or TARGET_TYPE_NPC
        else (TARGET_TYPE_NPC shl 16) or (infoIndex and 0xffff)

    fun setTargetTypeFirst(): Boolean =
        System.getProperty("opennxt.compat.setTarget") != "949" &&
            com.opennxt.model.entity.rendering.UpdateBlockType.experimentalBuild()

    fun nameAndLevel(name: String, combatLevel: Int): RunClientScript = RunClientScript(
        script = SCRIPT_TARGET_INFO,
        args = arrayOf(name, combatLevel, -1, -1, -1, 1)
    )

    fun showFrames(npc: WorldNpc): List<GamePacket> {
        if (!enabled || npc.infoIndex < 0) return emptyList()
        val out = ArrayList<GamePacket>(4)
        out.add(SetTarget(targetField(npc.infoIndex)))
        val current = npc.currentLifepoints
        val maximum = npc.lifepoints?.value
        if (current != null && maximum != null) {
            out.add(ClientSetvarcSmall(VARC_TARGET_HEALTH_PERCENT, healthPercent(current, maximum)))
        }
        out.add(IfOpensubActiveNpc.targetInfo(npc.infoIndex))
        out.add(nameAndLevel(npc.name ?: "", npc.combat?.combatLevel ?: 0))
        return out
    }

    fun updateFrames(npc: WorldNpc): List<GamePacket> {
        if (!enabled) return emptyList()
        val current = npc.currentLifepoints ?: return emptyList()
        val maximum = npc.lifepoints?.value ?: return emptyList()
        return listOf(ClientSetvarcSmall(VARC_TARGET_HEALTH_PERCENT, healthPercent(current, maximum)))
    }

    fun hideFrames(): List<GamePacket> {
        if (!enabled) return emptyList()
        return listOf(
            SetTarget(NO_TARGET),
            ClientSetvarcSmall(VARC_TARGET_HEALTH_PERCENT, 0),
            IfClosesub(IfOpensubActiveNpc.TARGET_INFO_PARENT)
        )
    }

    @Volatile
    var containedWriteFailures: Int = 0
        private set

    @Volatile
    var firstWriteFailure: String? = null
        private set

    @Volatile
    var framesSent: Int = 0
        private set

    fun resetCounters() {
        framesSent = 0
        containedWriteFailures = 0
        firstWriteFailure = null
    }

    private fun write(player: WorldPlayer, frames: List<GamePacket>) {
        for (frame in frames) {
            try {
                player.client.write(frame)
                framesSent++
            } catch (t: Throwable) {
                containedWriteFailures++
                if (firstWriteFailure == null) firstWriteFailure = "${frame.javaClass.simpleName}: $t"
            }
        }
    }

    fun show(player: WorldPlayer, npc: WorldNpc) = write(player, showFrames(npc))

    fun update(player: WorldPlayer, npc: WorldNpc) = write(player, updateFrames(npc))

    fun hide(player: WorldPlayer) = write(player, hideFrames())
}

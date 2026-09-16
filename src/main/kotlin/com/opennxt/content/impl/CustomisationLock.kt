package com.opennxt.content.impl

import com.opennxt.model.world.WorldPlayer
import com.opennxt.net.game.clientprot.IfButtonN
import com.opennxt.net.game.serverprot.RunClientScript
import mu.KotlinLogging

object CustomisationLock {
    private val logger = KotlinLogging.logger { }

    const val TOPLEVEL = 1477
    const val LOCK_COMPONENT = 65
    const val LOCK_SLOT = 1
    const val VARP_HUD = 3814
    const val DISABLED_BIT = 0x2
    const val ANNOUNCE_SCRIPT = 8862
    val ANNOUNCE_ARGS: Array<Any> = arrayOf(5, 1)

    val enabled: Boolean get() = System.getProperty("opennxt.content.customisationLock") != "off"

    val ENABLE_MOUNTS: List<IntArray> = listOf(
        intArrayOf(1461, 169), intArrayOf(1884, 180), intArrayOf(1885, 191), intArrayOf(1887, 202), intArrayOf(1886, 213),
        intArrayOf(1460, 147), intArrayOf(1883, 257), intArrayOf(1449, 268), intArrayOf(1882, 279), intArrayOf(1452, 158),
        intArrayOf(1219, 224), intArrayOf(1220, 235), intArrayOf(1221, 246),
    )

    val ENABLE_EVENTS: List<IntArray> = listOf(
        intArrayOf(1461, 1, 0, 264, 97358), intArrayOf(1884, 1, 0, 264, 97358), intArrayOf(1885, 1, 0, 264, 97358),
        intArrayOf(1887, 1, 0, 264, 97358), intArrayOf(1886, 1, 0, 264, 97358),
        intArrayOf(1461, 7, 7, 16, 2), intArrayOf(1461, 7, 7, 10, 10319874),
        intArrayOf(1460, 5, 7, 16, 2), intArrayOf(1452, 7, 7, 16, 2),
        intArrayOf(1219, 7, 7, 16, 2), intArrayOf(1219, 7, 7, 10, 10319874),
        intArrayOf(1220, 7, 7, 16, 2), intArrayOf(1221, 7, 7, 16, 2),
        intArrayOf(1883, 7, 7, 16, 2), intArrayOf(1883, 7, 7, 10, 10319874),
        intArrayOf(1449, 7, 7, 16, 2), intArrayOf(1882, 7, 7, 16, 2),
        intArrayOf(1884, 7, 7, 16, 2), intArrayOf(1885, 7, 7, 16, 2), intArrayOf(1887, 7, 7, 16, 2), intArrayOf(1886, 7, 7, 16, 2),
        intArrayOf(1460, 1, 0, 264, 97286), intArrayOf(1452, 1, 0, 264, 97286), intArrayOf(1219, 1, 0, 264, 97286),
        intArrayOf(1220, 1, 0, 264, 97286), intArrayOf(1221, 1, 0, 264, 97286), intArrayOf(1883, 1, 0, 264, 97286),
        intArrayOf(1449, 1, 0, 264, 97286), intArrayOf(1882, 1, 0, 264, 97286),
    )

    fun isDisabled(varpValue: Int): Boolean = varpValue and DISABLED_BIT != 0
    fun toggled(varpValue: Int): Int = varpValue xor DISABLED_BIT

    sealed class Outcome {
        data class Toggled(val value: Int, val nowEnabled: Boolean, val remounted: Int, val armed: Int) : Outcome()
        object Refused : Outcome()
    }

    fun handleButton(player: WorldPlayer, packet: IfButtonN): Boolean {
        if (!enabled) return false
        if (packet.interfaceId != TOPLEVEL || packet.component != LOCK_COMPONENT || packet.arg2 != LOCK_SLOT) return false
        if (!player.allowOncePerTick(RATE_KEY)) {
            throttled++
            logger.info { "customisationLock: ${player.name} toggled twice in one tick; ignored" }
            return true
        }
        toggle(player, com.opennxt.model.entity.rendering.UpdateBlockType.experimentalBuild())
        return true
    }

    const val RATE_KEY = "customisationLock"

    @Volatile
    var throttled: Int = 0
        private set

    fun toggle(player: WorldPlayer, remount: Boolean): Outcome {
        if (!player.interfaces.isOpened(TOPLEVEL)) {
            logger.info { "customisationLock: ${player.name} clicked the padlock with 1477 not open; ignored" }
            return Outcome.Refused
        }
        val value = toggled(player.varpValue(VARP_HUD))
        val nowEnabled = !isDisabled(value)
        player.setVarpOverride(VARP_HUD, value)
        var mounted = 0
        var armed = 0
        if (nowEnabled && remount) {
            for (m in ENABLE_MOUNTS) {
                player.interfaces.open(id = m[0], parent = TOPLEVEL, component = m[1], walkable = true, native949 = true)
                mounted++
            }
            for (e in ENABLE_EVENTS) {
                if (!player.interfaces.isOpened(e[0])) continue
                player.client.write(
                    com.opennxt.net.game.serverprot.ifaces.IfSetevents(com.opennxt.model.InterfaceHash(e[0], e[1]), e[2], e[3], e[4])
                )
                armed++
            }
            player.client.write(RunClientScript(script = ANNOUNCE_SCRIPT, args = ANNOUNCE_ARGS))
        }
        logger.info {
            "customisationLock: ${player.name} ${if (nowEnabled) "enabled" else "disabled"} interface customisation, varp 3814 = $value" +
                (if (nowEnabled && remount) "; re-mounted $mounted, armed $armed" else "")
        }
        return Outcome.Toggled(value, nowEnabled, mounted, armed)
    }
}

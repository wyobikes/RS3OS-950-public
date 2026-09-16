package com.opennxt.content.impl

import com.opennxt.Constants
import com.opennxt.model.world.WorldPlayer
import com.opennxt.net.game.clientprot.IfButtonN
import com.opennxt.net.game.serverprot.MessageGame
import mu.KotlinLogging
import java.nio.file.Files
import java.nio.file.Path

object ActionBarLock {
    private val logger = KotlinLogging.logger { }

    const val BAR_IFACE = 1430
    const val LOCK_BUTTON = 270
    const val VARP_ACTION_BAR = 682
    const val LOCKED_BIT = 0x10
    const val MSG_LOCKED = "Action bars are now locked."
    const val MSG_UNLOCKED = "Action bars are now unlocked."

    val UNLOCKED_FILE: Path = Constants.DATA_PATH.resolve("seed").resolve("actionbar_unlocked_events_950.tsv")
    val LOCKED_FILE: Path = Constants.DATA_PATH.resolve("seed").resolve("actionbar_locked_events_950.tsv")

    fun load(path: Path): List<IntArray> {
        if (!Files.isRegularFile(path)) {
            logger.warn { "actionBarLock: $path is MISSING - the padlock will toggle the varp but arm nothing" }
            return emptyList()
        }
        return Files.readAllLines(path).mapNotNull { raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) return@mapNotNull null
            val f = line.split('\t', ' ').filter { it.isNotEmpty() }.map { it.toInt() }
            if (f.size == 5) intArrayOf(f[0], f[1], f[2], f[3], f[4]) else null
        }
    }

    val unlockedEvents: List<IntArray> by lazy { load(UNLOCKED_FILE) }
    val lockedEvents: List<IntArray> by lazy { load(LOCKED_FILE) }

    fun isLocked(player: WorldPlayer): Boolean = player.varpValue(VARP_ACTION_BAR) and LOCKED_BIT != 0

    const val RATE_KEY = "actionBarLock"

    @Volatile
    var throttled: Int = 0
        private set

    fun toggled(current: Int): Int = current xor LOCKED_BIT

    fun handleButton(player: WorldPlayer, packet: IfButtonN): Boolean {
        if (packet.interfaceId != BAR_IFACE || packet.component != LOCK_BUTTON) return false
        if (!player.interfaces.isOpened(BAR_IFACE)) {
            logger.info { "actionBarLock: ${player.name} clicked 1430:270 with 1430 not open - ignored" }
            return true
        }
        if (!player.allowOncePerTick(RATE_KEY)) {
            throttled++
            logger.info { "actionBarLock: ${player.name} clicked 1430:270 again in the same tick - dropped (one toggle per tick)" }
            return true
        }
        val value = toggled(player.varpValue(VARP_ACTION_BAR))
        val locked = value and LOCKED_BIT != 0
        player.setVarpOverride(VARP_ACTION_BAR, value)
        player.client.write(MessageGame(0, if (locked) MSG_LOCKED else MSG_UNLOCKED))
        val rows = if (locked) lockedEvents else unlockedEvents
        var armed = 0
        for (e in rows) {
            if (!player.interfaces.isOpened(e[0])) continue
            if (AbilityBar.isSlotRow(e[0], e[1])) continue
            player.interfaces.events(id = e[0], component = e[1], from = e[2], to = e[3], mask = e[4], native949 = true)
            armed++
        }
        armed += AbilityBar.armSlots(player, locked = locked)
        logger.info { "actionBarLock: ${player.name} ${if (locked) "LOCKED" else "UNLOCKED"} the action bars - varp 682 = $value, $armed of ${rows.size} event rows armed (the rest name panels this player has not open)" }
        return true
    }
}

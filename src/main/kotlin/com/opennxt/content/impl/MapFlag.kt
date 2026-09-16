package com.opennxt.content.impl

import com.opennxt.model.world.WorldPlayer
import com.opennxt.net.game.serverprot.SetMapFlag
import mu.KotlinLogging

object MapFlag {
    private val logger = KotlinLogging.logger { }

    private const val UNK1 = 255
    private const val UNK4 = 0
    private const val UNK5 = 255
    private const val UNK6 = 255
    private const val NO_TARGET = -1

    const val CLEAR_LOCAL = 255

    const val SCENE = 256

    fun base(baseTileX: Int, baseTileY: Int): Pair<Int, Int> =
        (baseTileX / 8 - 16) * 8 to (baseTileY / 8 - 16) * 8

    fun packet(localX: Int, localY: Int) = SetMapFlag(
        target = NO_TARGET,
        unk1 = UNK1,
        localY = localY,
        localX = localX,
        unk4 = UNK4,
        unk5 = UNK5,
        unk6 = UNK6
    )

    fun set(player: WorldPlayer, x: Int, y: Int): Boolean {
        val bt = player.viewport.baseTile
        val (bx, by) = base(bt.x, bt.y)
        val lx = x - bx
        val ly = y - by
        if (lx !in 0 until SCENE || ly !in 0 until SCENE) {
            logger.debug {
                "map flag not sent for ($x,$y): local ($lx,$ly) is outside the scene at ($bx,$by)"
            }
            return false
        }
        player.client.write(packet(lx, ly))
        return true
    }

    fun clear(player: WorldPlayer) {
        player.client.write(packet(CLEAR_LOCAL, CLEAR_LOCAL))
    }
}

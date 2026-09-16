package com.opennxt.net.game.handlers

import com.opennxt.model.world.WorldPlayer
import com.opennxt.net.game.clientprot.MoveMinimapClick
import com.opennxt.net.game.pipeline.GamePacketHandler

object MoveMinimapClickHandler : GamePacketHandler<WorldPlayer, MoveMinimapClick> {
    const val TAG = "MOVE_MINIMAPCLICK"

    override fun handle(context: WorldPlayer, packet: MoveMinimapClick) {
        val queued = walk(context.entity, context.contentPlayer, packet.x, packet.y, packet.flags)
        if (queued > 0) com.opennxt.content.impl.MapFlag.set(context, packet.x, packet.y)
        else com.opennxt.content.impl.MapFlag.clear(context)
    }

    fun walk(
        entity: com.opennxt.model.entity.PlayerEntity,
        player: com.opennxt.content.ContentPlayer,
        x: Int,
        y: Int,
        flags: Int
    ): Int {
        if (com.opennxt.content.ActionLock.refuse(player, "$TAG to ($x,$y)")) return 0
        return MoveGameClickHandler.walk(entity, x, y, TAG, "flags=0x${flags.toString(16)} ")
    }
}

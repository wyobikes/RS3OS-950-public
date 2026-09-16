package com.opennxt.net.game.handlers

import com.opennxt.model.entity.BasePlayer
import com.opennxt.model.lobby.LobbyPlayer
import com.opennxt.model.worldlist.WorldList
import com.opennxt.net.game.clientprot.WorldlistFetch
import com.opennxt.net.game.pipeline.GamePacketHandler
import mu.KotlinLogging

object WorldlistFetchHandler : GamePacketHandler<BasePlayer, WorldlistFetch> {
    private val logger = KotlinLogging.logger { }

    const val WORLD_REPLY_PROPERTY = "opennxt.experiment.worldlist.world"

    val worldReplyEnabled: Boolean =
        System.getProperty(WORLD_REPLY_PROPERTY)?.toBoolean() ?: true

    override fun handle(context: BasePlayer, packet: WorldlistFetch) {
        if (context is LobbyPlayer) {
            context.worldList.handleRequest(packet.checksum, context.client)
            return
        }

        if (!worldReplyEnabled) {
            logger.warn {
                "WORLDLIST_FETCH from ${context.name} ignored (-D$WORLD_REPLY_PROPERTY is off)"
            }
            return
        }

        val list = WorldList(WorldList.demoEntries())
        list.handleRequest(packet.checksum, context.client)
        logger.info {
            "WORLDLIST_FETCH from ${context.name}: sent ${list.entries.size} world(s) " +
                "(client checksum ${packet.checksum}, server ${list.hashCode()})"
        }
    }
}

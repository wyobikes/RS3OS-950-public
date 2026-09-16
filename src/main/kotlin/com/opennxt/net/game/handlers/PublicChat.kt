package com.opennxt.net.game.handlers

import com.opennxt.OpenNXT
import com.opennxt.model.entity.rendering.PlayerUpdates
import com.opennxt.model.world.WorldPlayer
import com.opennxt.net.game.clientprot.MessagePublic
import com.opennxt.net.game.serverprot.MessagePublicOut
import com.opennxt.util.HuffmanCodec
import mu.KotlinLogging

object PublicChat {
    private val logger = KotlinLogging.logger { }

    val enabled: Boolean
        get() = System.getProperty("opennxt.experiment.chat") != "false"

    var linesRelayed = 0
        private set
    var copiesSent = 0
        private set

    fun resetCounters() {
        linesRelayed = 0
        copiesSent = 0
    }

    fun canSee(viewer: WorldPlayer, speakerIndex: Int): Boolean {
        if (speakerIndex < 1 || speakerIndex >= viewer.viewport.localPlayers.size) return false
        return viewer.viewport.localPlayers[speakerIndex] != null
    }

    fun relay(speaker: WorldPlayer, packet: MessagePublic): Int {
        if (!enabled) return 0

        val index = speaker.entity.index
        if (index < 1) {
            logger.warn { "Dropping public chat from ${speaker.name}: entity index is $index" }
            return 0
        }

        if (HuffmanCodec.instance() == null) {
            logger.warn { "Dropping public chat from ${speaker.name}: no huffman table to re-encode it with" }
            return 0
        }

        PlayerUpdates.say(speaker.entity, packet.text)

        var sent = 0
        OpenNXT.world.forEachPlayer { viewer ->
            if (!canSee(viewer, index)) return@forEachPlayer
            viewer.write(
                MessagePublicOut(
                    index = index,
                    colour = packet.colour,
                    effect = packet.effect,
                    rights = 0,
                    text = packet.text
                )
            )
            sent++
        }

        linesRelayed++
        copiesSent += sent
        logger.info {
            "public chat from ${speaker.name} (index $index) -> $sent viewer(s): " +
                "colour=${packet.colour}${packet.colourName()?.let { "/$it" } ?: ""} " +
                "effect=${packet.effect}${packet.effectName()?.let { "/$it" } ?: ""} \"${packet.text}\""
        }
        return sent
    }
}

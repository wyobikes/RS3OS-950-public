package com.opennxt.net.game.handlers

import com.opennxt.model.entity.BasePlayer
import com.opennxt.net.game.clientprot.UndecodedClientPacket
import com.opennxt.net.game.clientprot.UndecodedStructure
import com.opennxt.net.game.pipeline.GamePacketHandler
import mu.KotlinLogging
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

object UndecodedClientPacketHandler : GamePacketHandler<BasePlayer, UndecodedClientPacket> {
    private val logger = KotlinLogging.logger { }

    val FULL_SAMPLES: Int = System.getProperty("opennxt.prot.undecodedSamples")?.toIntOrNull() ?: 3
    val SAMPLE_EVERY: Int = (System.getProperty("opennxt.prot.undecodedEvery")?.toIntOrNull() ?: 100)
        .coerceAtLeast(1)

    private val seen = ConcurrentHashMap<Int, AtomicInteger>()

    fun count(opcode: Int): Int = seen[opcode]?.get() ?: 0

    private const val COMPONENT_CLICK_OPCODE = 127

    private const val PANEL_CLOSE_OPCODE = 89

    private const val CLIENT_CLOSED_INTERFACES_OPCODE = 67

    override fun handle(context: BasePlayer, packet: UndecodedClientPacket) {
        val n = seen.computeIfAbsent(packet.opcode) { AtomicInteger() }.incrementAndGet()

        if (packet.opcode == PANEL_CLOSE_OPCODE && packet.payload.size == 4 &&
            context is com.opennxt.model.world.WorldPlayer
        ) {
            val panelId = (packet.u8(0) shl 24) or (packet.u8(1) shl 16) or (packet.u8(2) shl 8) or packet.u8(3)
            if (com.opennxt.content.impl.PanelCloseWiring.handlePanelClose(context, panelId)) return
        }

        if (packet.opcode == CLIENT_CLOSED_INTERFACES_OPCODE &&
            context is com.opennxt.model.world.WorldPlayer
        ) {
            com.opennxt.content.impl.GlobalCloseWiring.handleClientClosedInterfaces(context)
        }

        if (packet.opcode == COMPONENT_CLICK_OPCODE && context is com.opennxt.model.world.WorldPlayer) {
            val interfaceId = packet.u16(0)
            val component = packet.u16(2)
            if (interfaceId >= 0 && component >= 0 &&
                com.opennxt.content.impl.DialogueWiring
                    .handleUndecodedComponentClick(context, interfaceId, component)
            ) return
        }

        if (n <= FULL_SAMPLES || n % SAMPLE_EVERY == 0) {
            val structure = UndecodedStructure.describe(packet)
            logger.info {
                "undecoded client opcode ${packet.opcode} (#$n, ${packet.payload.size} bytes): ${packet.hex()}" +
                    (if (structure == null) "" else "  |  $structure")
            }
        }
    }
}

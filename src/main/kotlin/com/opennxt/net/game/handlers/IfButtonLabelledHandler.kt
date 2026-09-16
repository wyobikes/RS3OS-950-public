package com.opennxt.net.game.handlers

import com.opennxt.model.entity.BasePlayer
import com.opennxt.net.game.clientprot.IfButtonLabelled
import com.opennxt.net.game.pipeline.GamePacketHandler
import mu.KotlinLogging
import java.util.concurrent.atomic.AtomicBoolean

object IfButtonLabelledHandler : GamePacketHandler<BasePlayer, IfButtonLabelled> {
    private val logger = KotlinLogging.logger { }

    private val handoffTypeCheck:
        it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap<
            kotlin.reflect.KClass<out com.opennxt.net.game.GamePacket>,
            GamePacketHandler<in BasePlayer, out com.opennxt.net.game.GamePacket>> =
        it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap<
            kotlin.reflect.KClass<out com.opennxt.net.game.GamePacket>,
            GamePacketHandler<in BasePlayer, out com.opennxt.net.game.GamePacket>>()
            .also { it[IfButtonLabelled::class] = IfButtonLabelledHandler }

    private val bannerPrinted = AtomicBoolean(false)

    override fun handle(context: BasePlayer, packet: IfButtonLabelled) {
        if (bannerPrinted.compareAndSet(false, true)) {
            logger.info { "first IF_BUTTON_LABELLED frame received" }
        }

        logger.info {
            "IF_BUTTON_LABELLED slot=${packet.slotSigned} " +
                "(raw=${packet.slot} 0x${packet.slot.toString(16).padStart(4, '0')}) " +
                "interface=${packet.interfaceId} component=${packet.component} op=${packet.op} " +
                "label='${packet.label}' defFlag=${packet.defFlag} " +
                "(0x${packet.defFlag.toString(16).padStart(2, '0')}) " +
                "hash=0x${packet.hash.toString(16).padStart(8, '0')}"
        }

        if (context is com.opennxt.model.world.WorldPlayer &&
            com.opennxt.content.impl.DialogueWiring.handleButtonLabelled(context, packet)
        ) return

        if (packet.slotSigned != -1) {
            logger.warn {
                "IF_BUTTON_LABELLED with unexpected slot ${packet.slotSigned} on " +
                    "${packet.interfaceId}:${packet.component} label='${packet.label}'"
            }
        }
    }
}

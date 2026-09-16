package com.opennxt.net.game.handlers

import com.opennxt.model.entity.BasePlayer
import com.opennxt.net.game.clientprot.VarcTransmit
import com.opennxt.net.game.pipeline.GamePacketHandler
import com.opennxt.net.game.serverprot.variables.StoreServerpermVarcsAck
import mu.KotlinLogging

object VarcTransmitHandler : GamePacketHandler<BasePlayer, VarcTransmit> {
    private val logger = KotlinLogging.logger { }

    private val enabled: Boolean
        get() = System.getProperty("opennxt.experiment.ui.varcStore") != "false"

    private val store = java.util.Collections.synchronizedMap(
        java.util.WeakHashMap<BasePlayer, MutableMap<Int, Any>>()
    )

    fun stateOf(player: BasePlayer): Map<Int, Any> =
        store[player]?.toMap() ?: emptyMap()

    override fun handle(context: BasePlayer, packet: VarcTransmit) {
        if (!enabled) return

        val held = store.getOrPut(context) { LinkedHashMap() }
        val before = held.size

        val changed = LinkedHashMap<Int, Pair<Any?, Any>>()
        for ((id, value) in packet.values) {
            val old = held[id]
            if (old != value) changed[id] = old to value
        }

        held.putAll(packet.values)

        context.client.write(StoreServerpermVarcsAck)

        if (changed.isNotEmpty()) {
            logger.info {
                val revised = changed.count { it.value.first != null }
                val sample = changed.entries.take(12).joinToString {
                    "${it.key}:${it.value.first ?: "-"}->${it.value.second}"
                }
                "varcStore ${context.name}: ${changed.size} varc(s) changed " +
                    "(${held.size - before} new, $revised revised, ${packet.values.size} in packet, " +
                    "allSent=${packet.allSent}), holding ${held.size} " +
                    "[$sample${if (changed.size > 12) ", ..." else ""}]"
            }
        }
    }
}

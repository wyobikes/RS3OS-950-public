package com.opennxt.net.game.handlers

import com.opennxt.model.entity.BasePlayer
import com.opennxt.net.game.GamePacket
import com.opennxt.net.game.pipeline.GamePacketHandler
import mu.KotlinLogging
import java.util.concurrent.ConcurrentHashMap

object DecodedPacketLogHandler : GamePacketHandler<BasePlayer, GamePacket> {
    private val logger = KotlinLogging.logger { }

    val FULL: Int = System.getProperty("opennxt.prot.decodedFull")?.toIntOrNull() ?: 25
    val EVERY: Int = (System.getProperty("opennxt.prot.decodedEvery")?.toIntOrNull() ?: 100).coerceAtLeast(1)

    private val seen = ConcurrentHashMap<String, Int>()

    fun count(clazz: Class<*>): Int = seen[clazz.simpleName] ?: 0

    override fun handle(context: BasePlayer, packet: GamePacket) {
        val name = packet::class.java.simpleName
        val n = seen.merge(name, 1, Int::plus) ?: 1
        if (n <= FULL || n % EVERY == 0) {
            logger.info { "[decoded] $packet (occurrence $n, from ${context.javaClass.simpleName})" }
        }
    }
}

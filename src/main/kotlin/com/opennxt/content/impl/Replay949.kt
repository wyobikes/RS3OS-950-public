package com.opennxt.content.impl

import com.opennxt.model.world.WorldPlayer
import com.opennxt.net.game.pipeline.OpcodeWithBuffer
import com.opennxt.net.proxy.UnidentifiedPacket
import io.netty.buffer.Unpooled
import mu.KotlinLogging
import java.io.File

object Replay949 {
    private val logger = KotlinLogging.logger { }

    data class Rec(val seq: Int, val op: Int, val name: String, val bytes: ByteArray)

    val path: String? = System.getProperty("opennxt.experiment.replay949")
    val enabled: Boolean get() = path != null
    val mode: String = (System.getProperty("opennxt.experiment.replay949.mode") ?: "after").lowercase()
    val only: Boolean get() = mode == "only"

    private val UI_PACKET_NAMES = listOf(
        "IF_OPENTOP", "IF_OPENSUB", "IF_CLOSESUB", "IF_SETEVENTS", "IF_SETHIDE", "IF_SETTEXT",
        "IF_SETGRAPHIC", "IF_SETCOLOUR", "IF_SETANGLE", "IF_SETSCROLLPOS", "IF_MOVESUB",
        "RUNCLIENTSCRIPT", "SETDRAWORDER"
    )

    val uiOnly: Boolean get() = mode == "ui"

    private val uiOps: Set<Int> by lazy {
        val resolved = LinkedHashMap<String, Int>()
        val missing = ArrayList<String>()
        val table = com.opennxt.OpenNXT.protocol.serverProtNames.values
        for (n in UI_PACKET_NAMES) {
            if (table.containsKey(n)) resolved[n] = table.getInt(n) else missing.add(n)
        }
        if (missing.isNotEmpty()) {
            logger.warn { "replay949[ui]: not replaying ${missing.size} packet(s) missing from serverProtNames.toml: " +
                missing.joinToString() }
        }
        logger.warn { "replay949[ui]: interface layer = ${resolved.size} opcode(s): " +
            resolved.entries.joinToString { "${it.key}=${it.value}" } }
        resolved.values.toSet()
    }
    val delayTicks: Int = System.getProperty("opennxt.experiment.replay949.delayTicks")?.toIntOrNull() ?: (if (mode == "only") 1 else 6)
    val perTick: Int = System.getProperty("opennxt.experiment.replay949.perTick")?.toIntOrNull() ?: 200
    private val fromWorld = (System.getProperty("opennxt.experiment.replay949.from") ?: "world") != "all"
    private val worldSkip = setOf(64, 45, 90, 180, 227, 126, 109, 28, 41, 14, 12, 202, 186, 157, 211, 62, 1, 47, 57, 2, 58, 102, 136, 172, 83, 132, 163, 123)
    private val builtinSkip: Set<Int> get() = if (only) worldSkip else worldSkip + setOf(20, 8, 43, 10, 4, 77, 21)
    private val extraSkip: Set<Int> = System.getProperty("opennxt.experiment.replay949.skip")?.split(',')?.mapNotNull { it.trim().toIntOrNull() }?.toSet() ?: emptySet()
    private val onlyOps: Set<Int>? = System.getProperty("opennxt.experiment.replay949.only")?.split(',')?.mapNotNull { it.trim().toIntOrNull() }?.toSet()

    private val records: List<Rec> by lazy { load() }

    private fun load(): List<Rec> {
        val p = path ?: return emptyList()
        val f = File(p)
        if (!f.isFile) { logger.error { "replay949: $p is not a file" }; return emptyList() }
        val out = ArrayList<Rec>()
        val opRe = Regex("\"op\"\\s*:\\s*(\\d+)")
        val seqRe = Regex("\"seq\"\\s*:\\s*(\\d+)")
        val dirRe = Regex("\"dir\"\\s*:\\s*\"([^\"]*)\"")
        val nameRe = Regex("\"name\"\\s*:\\s*\"([^\"]*)\"")
        val hexRe = Regex("\"hex\"\\s*:\\s*\"([0-9a-fA-F]*)\"")
        var nonS2c = 0
        f.forEachLine { line ->
            if (!line.startsWith("{")) return@forEachLine
            val dir = dirRe.find(line)?.groupValues?.get(1)
            if (dir != null && dir != "s2c") { if (dir != "wire") nonS2c++; return@forEachLine }
            val op = opRe.find(line)?.groupValues?.get(1)?.toIntOrNull() ?: return@forEachLine
            val seq = seqRe.find(line)?.groupValues?.get(1)?.toIntOrNull() ?: out.size
            val name = nameRe.find(line)?.groupValues?.get(1) ?: "?"
            val hex = hexRe.find(line)?.groupValues?.get(1) ?: ""
            val bytes = ByteArray(hex.length / 2) { i -> hex.substring(2 * i, 2 * i + 2).toInt(16).toByte() }
            out.add(Rec(seq, op, name, bytes))
        }
        var start = 0
        if (fromWorld) {
            val idx = out.indexOfFirst { it.op == 64 }
            if (idx >= 0) start = idx + 1
        }
        val kept = out.drop(start).filter { r ->
            (onlyOps?.contains(r.op) ?: true) &&
                if (uiOnly) r.op in uiOps else (r.op !in builtinSkip && r.op !in extraSkip)
        }
        val hist = kept.groupingBy { "${it.op} ${it.name}" }.eachCount().entries.sortedByDescending { it.value }
        logger.warn {
            "replay949[$mode]: loaded ${out.size} server->client packet(s) from $p " +
                "(ignored $nonS2c client->server), starting at $start; " +
                "replaying ${kept.size}, skipped ${out.size - start - kept.size}. Top: " +
                hist.take(12).joinToString { "${it.key} x${it.value}" }
        }
        return kept
    }

    fun step(player: WorldPlayer, cursor: Int): Int {
        val recs = records
        var i = cursor
        var n = 0
        while (i < recs.size && n < perTick) {
            val r = recs[i]
            val refused = suppressionReason(r, player)
            if (refused != null) {
                if (suppressWarned.add(r.name + i)) logger.warn { "replay949: skipping suppressed open: $refused" }
                i++
                continue
            }
            adoptIfOpen(player, r)
            player.client.write(UnidentifiedPacket(OpcodeWithBuffer(r.op, Unpooled.wrappedBuffer(fitToWire(r)))))
            i++; n++
        }
        if (n > 0) logger.info { "replay949: sent $n packet(s) [${cursor}..${i - 1}] of ${recs.size}" }
        return recs.size - i
    }

    fun size(): Int = records.size

    private fun suppressionReason(r: Rec, player: WorldPlayer): String? {
        val reg = com.opennxt.net.game.PacketRegistry.getRegistration(com.opennxt.net.Side.SERVER, r.op) ?: return null
        if (reg.name != "IF_OPENSUB") return null
        return try {
            val p = reg.codec.decode(com.opennxt.net.buf.GamePacketReader(Unpooled.wrappedBuffer(r.bytes)))
                as com.opennxt.net.game.serverprot.ifaces.IfOpenSub
            if (p.parent.parent != 1477) return null

            com.opennxt.content.impl.PanelToggles.panelForBarMount(p.id, p.parent.component)?.let { panel ->
                if (!com.opennxt.content.impl.PanelToggles.isBarEnabled(player, panel)) {
                    return "${com.opennxt.content.impl.PanelToggles.describe(panel)} (interface ${p.id} at 1477:" +
                        "${p.parent.component}) is disabled for ${player.name} " +
                        "(varp ${com.opennxt.content.impl.PanelToggles.varpFor(panel)})"
                }
            }

            com.opennxt.model.entity.player.InterfaceManager.loginSuppressionReason(p.id, p.parent.component)
        } catch (e: Exception) {
            null
        }
    }

    private fun adoptIfOpen(player: WorldPlayer, r: Rec) {
        val reg = com.opennxt.net.game.PacketRegistry.getRegistration(com.opennxt.net.Side.SERVER, r.op) ?: return
        when (reg.name) {
            "IF_OPENTOP" -> {
                val p = reg.codec.decode(com.opennxt.net.buf.GamePacketReader(Unpooled.wrappedBuffer(r.bytes))) as com.opennxt.net.game.serverprot.ifaces.IfOpenTop
                player.interfaces.adoptTop(p.id)
            }
            "IF_OPENSUB" -> {
                val p = reg.codec.decode(com.opennxt.net.buf.GamePacketReader(Unpooled.wrappedBuffer(r.bytes))) as com.opennxt.net.game.serverprot.ifaces.IfOpenSub
                if (player.interfaces.adoptSub(p.id, p.parent.parent, p.parent.component, p.flag))
                    logger.info {
                        "replay949: adopted open ${com.opennxt.resources.Names949.iface(p.id)} at " +
                            com.opennxt.resources.Names949.component(p.parent.parent, p.parent.component) +
                            " (walkable=${p.flag})"
                    }
            }
        }
    }

    private val fitWarned = HashSet<Int>()
    private val suppressWarned = HashSet<String>()

    private fun fitToWire(r: Rec): ByteArray {
        val size = com.opennxt.OpenNXT.protocol.serverProtSizes.values.getOrDefault(r.op, Int.MIN_VALUE)
        if (size < 0 || size == r.bytes.size) return r.bytes
        if (fitWarned.add(r.op)) logger.warn { "replay949: opcode ${r.op} ${r.name} is ${r.bytes.size} byte(s), expected $size; ${if (r.bytes.size > size) "truncating" else "zero-padding"}" }
        return r.bytes.copyOf(size)
    }
}

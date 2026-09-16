package com.opennxt.content.impl

import com.opennxt.Constants
import com.opennxt.model.world.WorldPlayer
import com.opennxt.net.game.serverprot.RunClientScript
import com.opennxt.net.game.serverprot.variables.ClientSetvarcSmall
import com.opennxt.net.game.serverprot.variables.ClientSetvarcstrSmall
import mu.KotlinLogging
import java.nio.file.Files

object ActionBarArm {
    private val logger = KotlinLogging.logger { }

    private data class Events(val iface: Int, val comp: Int, val from: Int, val to: Int, val mask: Int)

    private class Block {
        val events = ArrayList<Events>()
        val varps = ArrayList<Pair<Int, Int>>()
        val varcs = ArrayList<Pair<Int, Int>>()
        val varcstrs = ArrayList<Pair<Int, String>>()
        val scripts = ArrayList<Int>()
        val size: Int get() = events.size + varps.size + varcs.size + varcstrs.size + scripts.size
    }

    private val blocks: Map<String, Block> by lazy { load() }

    @Volatile
    private var skipped: Int = -1

    val loadSkipped: Int
        get() { blocks; return skipped }

    private fun load(): Map<String, Block> {
        val path = Constants.DATA_PATH.resolve("config").resolve("action-bar-arm.tsv")
        val out = LinkedHashMap<String, Block>()
        if (!Files.isRegularFile(path)) {
            logger.warn("actionBarArm: $path is missing. Additional Action Bars will open but " +
                "their events will not be armed.")
            skipped = 0
            return out
        }
        var bad = 0
        Files.newBufferedReader(path).use { r ->
            r.lineSequence().forEach { line ->
                if (line.isBlank() || line.startsWith("#")) return@forEach
                val f = line.split('\t')
                if (f.size < 3) { bad++; return@forEach }
                val block = out.getOrPut(f[0]) { Block() }
                when (f[1]) {
                    "events" -> if (f.size >= 7) {
                        val v = (2..6).map { f[it].toIntOrNull() }
                        if (v.any { it == null }) bad++
                        else block.events += Events(v[0]!!, v[1]!!, v[2]!!, v[3]!!, v[4]!!)
                    } else bad++
                    "varp" -> {
                        val id = f[2].toIntOrNull(); val value = f.getOrNull(3)?.toIntOrNull()
                        if (id == null || value == null) bad++ else block.varps += id to value
                    }
                    "varc" -> {
                        val id = f[2].toIntOrNull(); val value = f.getOrNull(3)?.toIntOrNull()
                        if (id == null || value == null) bad++ else block.varcs += id to value
                    }
                    "varcstr" -> {
                        val id = f[2].toIntOrNull()
                        if (id == null) bad++ else block.varcstrs += id to (f.getOrNull(3) ?: "")
                    }
                    "script" -> {
                        val id = f[2].toIntOrNull()
                        if (id == null) bad++ else block.scripts += id
                    }
                    else -> bad++
                }
            }
        }
        skipped = bad
        logger.info {
            "actionBarArm: loaded ${out.entries.joinToString { "${it.key}=${it.value.size}" }}" +
                (if (bad > 0) "  ($bad unparseable row(s) SKIPPED - the table is generated, so a " +
                    "bad row means the emitter and this reader disagree)" else "")
        }
        return out
    }

    fun loadedSizes(): Map<String, Int> = blocks.mapValues { it.value.size }

    fun loadedEvents(scope: String): List<Pair<Int, Int>> =
        blocks[scope]?.events?.map { it.iface to it.comp } ?: emptyList()

    private fun send(player: WorldPlayer, b: Block) {
        for (e in b.events) {
            player.interfaces.events(id = e.iface, component = e.comp, from = e.from, to = e.to, mask = e.mask)
        }
        for ((id, value) in b.varps) {
            val v = if (id == PanelToggles.VARP_BAR_LAYOUT && PanelToggles.layoutVarpEnabled) player.varpValue(id) else value
            player.setVarpOverride(id, v, store = false)
        }
        for ((id, value) in b.varcs) player.client.write(ClientSetvarcSmall(id, value))
        for ((id, value) in b.varcstrs) player.client.write(ClientSetvarcstrSmall(id, value))
        for (s in b.scripts) player.client.write(RunClientScript(script = s))
    }

    fun arm(player: WorldPlayer, enabledPanels: Collection<Int>) {
        if (blocks.isEmpty()) return
        blocks["common"]?.let { send(player, it) }
        var armed = 0
        for (panel in enabledPanels.sorted()) {
            val b = blocks["bar$panel"] ?: continue
            send(player, b)
            armed++
        }
        logger.info {
            "actionBarArm: armed the main bar block plus $armed of ${enabledPanels.size} enabled " +
                "bar(s) for ${player.name}; every open bar is re-armed on each toggle."
        }
    }
}

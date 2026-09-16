package com.opennxt.impl.stat

import com.opennxt.OpenNXT
import com.opennxt.api.stat.Stat
import com.opennxt.model.entity.BasePlayer
import com.opennxt.model.entity.rendering.UpdateBlockType
import com.opennxt.model.entity.rendering.blocks.PlayerSpotanimBlock950
import com.opennxt.model.world.WorldPlayer
import com.opennxt.net.game.serverprot.generated.MidiJingle
import com.opennxt.resources.FilesystemResources
import com.opennxt.resources.config.enums.EnumDefinition
import mu.KotlinLogging

object LevelUpFanfare {
    private val logger = KotlinLogging.logger {}

    const val ENUM_LINES = 1477

    const val ENUM_JINGLES = 1479

    const val VOLUME_WIRE_BYTE = 0x20

    const val VOLUME_VALUE = (VOLUME_WIRE_BYTE - 0x80) and 0xff

    val jingleEnabled: Boolean
        get() = System.getProperty("opennxt.stats.levelUpJingle") != "off"

    val graphicEnabled: Boolean
        get() = System.getProperty("opennxt.stats.levelUpGraphic") != "off"

    private val LINE = Regex("""advanced an? (.+) level!""", RegexOption.IGNORE_CASE)

    fun buildJingleTable(lines: Map<Int, Any>, jingles: Map<Int, Any>): Map<Stat, Int> {
        val byName = Stat.values().associateBy { it.name.lowercase() }
        val out = LinkedHashMap<Stat, Int>()
        for ((slot, line) in lines) {
            val name = LINE.find(line.toString())?.groupValues?.get(1)?.trim()?.lowercase() ?: continue
            val stat = byName[name] ?: continue
            val id = (jingles[slot] as? Number)?.toInt() ?: continue
            out[stat] = id
        }
        return out
    }

    @Volatile
    private var table: Map<Stat, Int>? = null

    fun jingleTable(): Map<Stat, Int> {
        table?.let { return it }
        val built = runCatching {
            val res = FilesystemResources.instance
            val lines = res.get<EnumDefinition>(ENUM_LINES)?.values
            val ids = res.get<EnumDefinition>(ENUM_JINGLES)?.values
            if (lines == null || ids == null) emptyMap() else buildJingleTable(lines, ids)
        }.getOrElse { emptyMap() }
        if (built.size != Stat.values().size) {
            logger.warn { "level-up jingles: enums $ENUM_LINES/$ENUM_JINGLES name ${built.size} of ${Stat.values().size} skills; the rest level up silently" }
        }
        table = built
        return built
    }

    fun resetTable() {
        table = null
    }

    @Volatile
    var tickSource: () -> Long? = { runCatching { OpenNXT.world.currentTick }.getOrNull() }

    @Volatile
    private var jingles = 0

    @Volatile
    private var graphics = 0

    fun jingleCount(): Int = jingles

    @Volatile
    var lastJingle: Pair<String, Int>? = null
        private set

    fun graphicCount(): Int = graphics

    fun play(player: BasePlayer, stat: Stat, lastTick: Long?): Long? {
        val now = tickSource()
        if (now != null && now == lastTick) return lastTick
        if (jingleEnabled) {
            val id = jingleTable()[stat]
            if (id != null) {
                jingles++
                lastJingle = player.name to id
                runCatching { player.client.write(MidiJingle(volume = VOLUME_VALUE, id = id)) }
                    .onFailure { logger.warn(it) { "could not send the level-up jingle to ${player.name}" } }
            }
        }
        if (graphicEnabled && player is WorldPlayer && UpdateBlockType.experimentalBuild()) {
            graphics++
            PlayerSpotanimBlock950.queue(player.entity, PlayerSpotanimBlock950.LEVEL_UP)
        }
        return now
    }
}

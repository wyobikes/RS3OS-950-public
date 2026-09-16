package com.opennxt.content.ability

import com.google.gson.JsonParser
import com.opennxt.Constants
import com.opennxt.content.ability.AbilityDefinitions.Definition
import com.opennxt.model.entity.rendering.PlayerUpdates
import com.opennxt.model.entity.rendering.UpdateBlockType
import com.opennxt.model.entity.rendering.blocks.PlayerSpotanimBlock950
import com.opennxt.model.world.WorldPlayer
import com.opennxt.resources.sqlite.RsDatabase
import mu.KotlinLogging
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

object AbilityGraphics {
    private val logger = KotlinLogging.logger { }

    const val FLAG = "opennxt.combat.abilityGraphics"
    const val ADAPTIVE_STRIKE = 14679
    const val ADAPTIVE_STRIKE_1H_ENUM = 445
    const val OFFHAND_SLOT = 5
    const val PARAM_WEAPON_TYPE = 686

    enum class Tier(val rank: Int) { NONE(0), LOW(1), MID(2), HIGH(3) ;
        companion object { fun of(s: String?): Tier = values().firstOrNull { it.name.equals(s?.trim(), ignoreCase = true) } ?: NONE }
    }

    val admitted: Tier?
        get() = when (System.getProperty(FLAG)?.trim()?.lowercase()) {
            "off", "false" -> null
            "mid" -> Tier.MID
            "low", "all" -> Tier.LOW
            else -> Tier.HIGH
        }

    data class Row(val structId: Int, val name: String, val animation: Int?, val animationTier: Tier, val graphic: Int?, val graphicTier: Tier)

    @Volatile private var rows: Map<Int, Row>? = null
    @Volatile private var oneHanded: Pair<Int?, Map<Int, Int>>? = null

    var animationsPlayed = 0; private set
    var graphicsPlayed = 0; private set

    fun seedPath(): Path =
        (System.getProperty("opennxt.seed.dir")?.let { Paths.get(it) } ?: Constants.DATA_PATH.resolve("seed")).resolve("ability_graphics_950.tsv")

    fun rows(): Map<Int, Row> = rows ?: synchronized(this) { rows ?: load().also { rows = it } }

    private fun load(): Map<Int, Row> {
        val path = seedPath()
        if (!Files.isRegularFile(path)) { logger.warn { "abilities: $path absent - no seed animations or graphics" }; return emptyMap() }
        val lines = Files.readAllLines(path, StandardCharsets.UTF_8).filter { it.isNotBlank() && !it.startsWith("#") }
        if (lines.isEmpty()) return emptyMap()
        val header = lines.first().split('\t')
        fun idx(n: String) = header.indexOf(n)
        val out = LinkedHashMap<Int, Row>()
        for (l in lines.drop(1)) {
            val f = l.split('\t')
            fun col(n: String) = f.getOrNull(idx(n))?.trim().orEmpty()
            val sid = col("struct_id").toIntOrNull() ?: continue
            if (out.containsKey(sid)) continue
            val tier = col("tier")
            val at = Tier.of(tier.substringAfter("anim=", "").substringBefore(' '))
            val gt = Tier.of(tier.substringAfter("graphic=", "").substringBefore(' '))
            out[sid] = Row(sid, col("name"), col("proposed_animation").toIntOrNull(), at, col("proposed_graphic").toIntOrNull(), gt)
        }
        logger.info { "abilities: ${out.size} animation/graphic rows from $path" }
        return out
    }

    private fun oneHandedEnum(): Pair<Int?, Map<Int, Int>> = oneHanded ?: synchronized(this) {
        oneHanded ?: run {
            var default: Int? = null
            val m = HashMap<Int, Int>()
            runCatching {
                default = RsDatabase.queryAll("SELECT intValue FROM enums WHERE id = $ADAPTIVE_STRIKE_1H_ENUM") { it.getInt("intValue") }.firstOrNull()
                RsDatabase.queryAll("SELECT value FROM enums_attr WHERE field = 'intArrayValue1' AND id = $ADAPTIVE_STRIKE_1H_ENUM") { it.getString("value") }.firstOrNull()?.let { raw ->
                    for (pair in JsonParser().parse(raw).asJsonArray) { val a = pair.asJsonArray; m[a[0].asInt] = a[1].asInt }
                }
            }.onFailure { logger.warn { "abilities: enum $ADAPTIVE_STRIKE_1H_ENUM not read: ${it.message}" } }
            (default to (m as Map<Int, Int>)).also { oneHanded = it }
        }
    }

    fun oneHandedLoadout(player: WorldPlayer): Boolean =
        !AbilityActivation.isTwoHanded(player.wornWeapon) && player.worn[OFFHAND_SLOT] == null

    fun animationFor(player: WorldPlayer, def: Definition, floor: Tier? = null): Int? {
        val level = listOfNotNull(admitted, floor).minByOrNull { it.rank }
        val weaponType = player.wornWeapon?.params?.get(PARAM_WEAPON_TYPE)
        if (level != null && def.structId == ADAPTIVE_STRIKE && oneHandedLoadout(player)) {
            val (default, byType) = oneHandedEnum()
            (weaponType?.let { byType[it] } ?: default)?.let { return it }
        }
        def.animationFor(weaponType)?.let { return it }
        if (level == null) return null
        val row = rows()[def.structId] ?: return null
        return row.animation?.takeIf { row.animationTier != Tier.NONE && row.animationTier.rank >= level.rank }
    }

    fun graphicFor(def: Definition, floor: Tier? = null): Int? {
        val level = listOfNotNull(admitted, floor).minByOrNull { it.rank } ?: return null
        val row = rows()[def.structId] ?: return null
        return row.graphic?.takeIf { row.graphicTier != Tier.NONE && row.graphicTier.rank >= level.rank }
    }

    fun play(player: WorldPlayer, def: Definition, floor: Tier? = null): Pair<Int?, Int?> {
        val animation = runCatching { animationFor(player, def, floor) }.getOrNull()
        val graphic = runCatching { graphicFor(def, floor) }.getOrNull()
        if (animation != null) runCatching { PlayerUpdates.animate(player.entity, animation); animationsPlayed++ }
        val sentGraphic = if (graphic != null && UpdateBlockType.experimentalBuild()) {
            runCatching { PlayerSpotanimBlock950.queue(player.entity, PlayerSpotanimBlock950.single(graphic)); graphicsPlayed++ }.isSuccess
        } else false
        return animation to (if (sentGraphic) graphic else null)
    }
}

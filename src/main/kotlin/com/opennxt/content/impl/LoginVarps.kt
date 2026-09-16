package com.opennxt.content.impl

import com.opennxt.Constants
import com.opennxt.model.lobby.TODORefactorThisClass
import com.opennxt.model.world.WorldPlayer
import com.opennxt.net.game.serverprot.variables.VarpLarge
import com.opennxt.net.game.serverprot.variables.VarpSmall
import mu.KotlinLogging
import java.nio.file.Files

object LoginVarps {
    private val logger = KotlinLogging.logger { }

    val enabled: Boolean = System.getProperty("opennxt.experiment.ui.loginVarps") != "false"

    const val WHAT_A_LOGIN_SETS = 3669

    private val path = Constants.DATA_PATH.resolve("config").resolve("login-varps.tsv")

    val table: List<Triple<Int, Int, Int>> by lazy {
        if (!Files.exists(path)) return@lazy emptyList()
        val out = ArrayList<Triple<Int, Int, Int>>()
        var bad = 0
        Files.readAllLines(path).forEach { raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) return@forEach
            val p = line.split('\t', ' ').filter { it.isNotBlank() }
            if (p.size < 2) { bad++; return@forEach }
            val id = p[0].toIntOrNull()
            val v = p[1].toLongOrNull()
            if (id == null || v == null) { bad++; return@forEach }
            val op = p.getOrNull(2)?.toIntOrNull() ?: if (v > 127 || v < -128) 78 else 95
            out.add(Triple(id, v.toInt(), op))
        }
        if (bad > 0) logger.warn("login-varps.tsv: $bad unparseable line(s) skipped")
        out
    }

    fun send(player: WorldPlayer): Int {
        if (!enabled) {
            logger.info("ui.loginVarps=false: the login varp table is not being sent")
            return 0
        }
        val t = table
        if (t.isEmpty()) {
            logger.info(
                "ui.loginVarps: no table at $path; see data/config/login-varps.example.tsv for the format"
            )
            return 0
        }
        var large = 0
        var small = 0
        var skipped = 0
        val replaced = ArrayList<Int>()
        t.forEach { (id, tableValue, op) ->
            val stored = player.varpOverride(id)
            val v = stored ?: tableValue
            if (stored != null) replaced += id
            if (!TODORefactorThisClass.varpIsDefined(id)) { skipped++; return@forEach }
            if (op == 95 && v in -128..127) { player.client.write(VarpSmall(id, v)); small++ }
            else { player.client.write(VarpLarge(id, v)); large++ }
        }
        if (replaced.isNotEmpty()) {
            logger.info(
                "ui.loginVarps: ${player.name} - ${replaced.size} row(s) use saved values: $replaced"
            )
        }
        if (skipped > 0) {
            logger.warn(
                "ui.loginVarps: skipped $skipped of ${t.size} rows with varp ids not defined in this cache"
            )
        }
        logger.info(
            "ui.loginVarps: sent ${small + large} of ${t.size} login default varps " +
                "($large large, $small small)"
        )
        return t.size
    }
}

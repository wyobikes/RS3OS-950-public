package com.opennxt.content.impl

import com.opennxt.model.world.WorldPlayer
import com.opennxt.net.game.serverprot.variables.ClientSetvarcLarge
import com.opennxt.net.game.serverprot.generated.ClientSetvarcLarge64
import com.opennxt.net.game.serverprot.variables.ClientSetvarcSmall
import com.opennxt.net.game.serverprot.variables.ClientSetvarcstrLarge
import com.opennxt.net.game.serverprot.variables.ClientSetvarcstrSmall
import mu.KotlinLogging

object VarcRestore {
    private val logger = KotlinLogging.logger { }

    private const val STR_SMALL_MAX = 250

    const val SEED_949 = "varc-defaults.tsv"

    const val SEED_950 = "varc-defaults-950.tsv"

    fun seedFileName(build950: Boolean = com.opennxt.model.entity.rendering.UpdateBlockType.experimentalBuild()): String =
        when (System.getProperty("opennxt.experiment.ui.varcSeedTable")) {
            "949" -> SEED_949
            "950" -> SEED_950
            else -> if (build950) SEED_950 else SEED_949
        }

    val LEVEL_VARCS: IntArray = (1469..1493).toList().toIntArray() + intArrayOf(3715, 5125, 6783, 7292)

    val SKILL_KEY_STAT: IntArray =
        intArrayOf(0, 2, 4, 6, 1, 3, 5, 16, 15, 17, 12, 20, 14, 13, 10, 7, 11, 8, 9, 18, 19, 22, 21, 23, 24, 25, 26, 27, 28)

    const val COMBAT_LEVEL_VARC = 1000

    val COMPUTED_VARCS: Set<Int> = LEVEL_VARCS.toSet() + COMBAT_LEVEL_VARC

    private fun baseLevel(player: WorldPlayer, statId: Int): Int =
        player.stats.getLevel(com.opennxt.api.stat.Stat.values().first { it.id == statId }, false)

    fun levelValues(player: WorldPlayer): List<Pair<Int, Int>> =
        LEVEL_VARCS.mapIndexed { i, varc -> varc to baseLevel(player, SKILL_KEY_STAT[i]) }

    fun combatLevel(player: WorldPlayer): Int {
        fun l(id: Int) = baseLevel(player, id)
        return com.opennxt.model.world.PlayerCombatStats.combatLevel(
            attack = l(0), strength = l(2), defence = l(1), constitution = l(3), prayer = l(5),
            summoning = l(23), ranged = l(4), magic = l(6), necromancy = l(28)
        )
    }

    fun sendInt(player: WorldPlayer, id: Int, value: Int) {
        if (value in -128..127) player.client.write(ClientSetvarcSmall(id, value))
        else player.client.write(ClientSetvarcLarge(id, value))
    }

    fun sendLevels(player: WorldPlayer) {
        for ((varc, level) in levelValues(player)) sendInt(player, varc, level)
    }

    private fun seedFromDefaults(player: WorldPlayer, varcs: Boolean) {
        if (System.getProperty("opennxt.experiment.ui.varcSeed") == "false") {
            logger.info { "ui.varcSeed=false: the default varc/varcbit/varcstr table is not sent" }
            return
        }
        val path = com.opennxt.Constants.DATA_PATH.resolve("config").resolve(seedFileName())
        if (!java.nio.file.Files.exists(path)) {
            logger.warn {
                "ui.varcRestore: no seed table at $path for ${player.name}; using client defaults"
            }
            return
        }
        var varc = 0; var bit = 0; var str = 0; var bad = 0; var computed = 0
        java.nio.file.Files.readAllLines(path).forEach { line ->
            if (line.startsWith("#") || line.isBlank()) return@forEach
            val f = line.split('	')
            if (f.size < 3) { bad++; return@forEach }
            val id = f[1].toIntOrNull() ?: run { bad++; return@forEach }
            if (f[0] == "varc" && id in COMPUTED_VARCS) { computed++; return@forEach }
            when (f[0]) {
                "varc" -> if (!varcs) Unit else f[2].toIntOrNull()?.let {
                    if (it in -128..127) player.client.write(ClientSetvarcSmall(id, it))
                    else player.client.write(ClientSetvarcLarge(id, it))
                    varc++
                } ?: bad++
                "varcbit" -> f[2].toIntOrNull()?.let {
                    player.client.write(com.opennxt.net.game.serverprot.variables.ClientSetvarcbitSmall(it, id)); bit++
                } ?: bad++
                "varcstr" -> {
                    player.client.write(ClientSetvarcstrSmall(id, line.substringAfter('	').substringAfter('	')))
                    str++
                }
                else -> bad++
            }
        }
        logger.info {
            (if (varcs) "ui.varcRestore: first login for ${player.name}, seeded "
             else "ui.varcRestore: seeded defaults for ${player.name}: ") +
                "$varc varc(s), $bit varcbit(s) and $str varcstr(s) from ${path.fileName}" +
                (if (computed > 0) " ($computed level row(s) skipped)" else "") +
                (if (bad > 0) " ($bad unparseable row(s) skipped)" else "")
        }
    }

    fun send(player: WorldPlayer) {
        if (System.getProperty("opennxt.experiment.ui.varcRestore") == "false") {
            logger.info { "ui.varcRestore=false: stored interface settings are not replayed" }
            return
        }
        val stored = player.save.varcs
        val replayOff = System.getProperty("opennxt.experiment.ui.varcReplay") == "false"
        if (replayOff && stored.isNotEmpty()) {
            logger.warn { "ui.varcReplay=false: not replaying ${stored.size} stored varc(s) for ${player.name}; seeding the defaults instead" }
        }
        if (stored.isEmpty() || replayOff) {
            seedFromDefaults(player, varcs = true)
            return
        }
        var ints = 0; var longs = 0; var strings = 0
        for ((id, v) in stored) {
            when (v) {
                is Int -> {
                    if (v in -128..127) player.client.write(ClientSetvarcSmall(id, v))
                    else player.client.write(ClientSetvarcLarge(id, v))
                    ints++
                }
                is Long -> {
                    player.client.write(ClientSetvarcLarge64((v ushr 32).toInt(), v.toInt(), id))
                    longs++
                }
                is String -> {
                    if (v.length <= STR_SMALL_MAX) player.client.write(ClientSetvarcstrSmall(id, v))
                    else player.client.write(ClientSetvarcstrLarge(id, v))
                    strings++
                }
                else -> logger.warn {
                    "ui.varcRestore: varc $id has unsupported type ${v.javaClass.simpleName}; not sent"
                }
            }
        }

        logger.info {
            "ui.varcRestore: replayed ${stored.size} stored interface setting(s) for ${player.name} " +
                "($ints int, $longs long, $strings string)"
        }
    }
}

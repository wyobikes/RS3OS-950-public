package com.opennxt.content.impl

import com.opennxt.Constants
import com.opennxt.model.vars.SqliteVarBits
import com.opennxt.model.world.TileLocation
import com.opennxt.model.world.WorldPlayer
import com.opennxt.net.game.serverprot.generated.SynthSound
import com.opennxt.resources.sqlite.RsDatabase
import mu.KotlinLogging
import java.nio.file.Files

object Lodestones {
    private val logger = KotlinLogging.logger { }

    val enabled: Boolean get() = System.getProperty("opennxt.experiment.lodestones") != "false"

    val DELAY_TICKS: Int
        get() = System.getProperty("opennxt.lodestone.ticks")?.toIntOrNull()?.takeIf { it in 0..100 } ?: 7

    const val SOUND = 14278
    const val SOUND_VOLUME = 127
    const val SOUND_EXTRA = 256

    val ACTIONS: Set<String> = setOf("Activate", "Power-up", "Charge", "Unlock", "Build")

    data class Lodestone(
        val parentLocId: Int,
        val varbit: Int,
        val activateLocId: Int,
        val activatedLocId: Int,
        val action: String,
        val name: String
    )

    val lodestones: Map<Int, Lodestone> by lazy { load() }

    val usable: List<Lodestone> by lazy {
        lodestones.values.filter { SqliteVarBits.definition(it.varbit)?.isWellFormed == true }
    }

    val namedLodestoneLocs: Map<Int, Pair<String, String?>> by lazy {
        if (!RsDatabase.available) emptyMap()
        else RsDatabase.queryAll(
            "SELECT id, name, actions_0 FROM locs WHERE lower(name) LIKE '% lodestone'"
        ) { rs -> rs.getInt("id") to (rs.getString("name") to rs.getString("actions_0")) }.toMap()
    }

    data class Slot(
        val slot: Int,
        val coord: Int,
        val x: Int,
        val y: Int,
        val plane: Int,
        val name: String,
        val varbit: Int,
        val locId: Int
    ) {
        val destination: TileLocation get() = TileLocation(x, y, plane)
    }

    val slotSeedPath: java.nio.file.Path = Constants.DATA_PATH.resolve("seed").resolve("lodestone_slots.tsv")

    val slots: Map<Int, Slot> by lazy { loadSlots() }

    private fun loadSlots(): Map<Int, Slot> {
        if (!Files.exists(slotSeedPath)) {
            logger.warn {
                "lodestones: $slotSeedPath not found; lodestone teleports are disabled"
            }
            return emptyMap()
        }
        val out = LinkedHashMap<Int, Slot>()
        runCatching {
            Files.readAllLines(slotSeedPath).forEach { line ->
                if (line.startsWith("#") || line.startsWith("slot\t") || line.isBlank()) return@forEach
                val f = line.split('\t')
                if (f.size < 8) return@forEach
                val s = Slot(
                    slot = f[0].toInt(), coord = f[1].toInt(), x = f[2].toInt(), y = f[3].toInt(),
                    plane = f[4].toInt(), name = f[5], varbit = f[6].toInt(), locId = f[7].toInt()
                )
                out[s.slot] = s
            }
        }.onFailure {
            logger.error(it) { "lodestones: could not read $slotSeedPath; lodestone teleports are disabled" }
            return emptyMap()
        }
        return out
    }

    fun slotFor(slot: Int): Slot? = slots[slot]

    val TELEPORT_TICKS: Int
        get() = System.getProperty("opennxt.lodestone.teleport.ticks")?.toIntOrNull()
            ?.takeIf { it in 0..200 } ?: 20

    val requireActivated: Boolean
        get() = System.getProperty("opennxt.lodestone.teleport.requireactive") == "true"

    @Volatile
    var teleportsAccepted: Int = 0
        private set

    @Volatile
    var teleportsRefusedUnknownSlot: Int = 0
        private set

    @Volatile
    var teleportsRefusedInactive: Int = 0
        private set

    internal fun resetTeleportCounters() {
        teleportsAccepted = 0; teleportsRefusedUnknownSlot = 0; teleportsRefusedInactive = 0
    }

    fun handleConfirmClick(player: WorldPlayer, slot: Int): Boolean {
        if (!enabled) return false
        val dest = slots[slot]
        if (dest == null) {
            teleportsRefusedUnknownSlot++
            logger.warn {
                "lodestone teleport: ${player.name} chose unknown slot $slot (${slots.size} slots known)"
            }
            return true
        }
        val lode = if (dest.locId >= 0) lodestones[dest.locId] else null
        val active = lode != null && isActivated(player, lode)
        if (requireActivated && lode != null && !active) {
            teleportsRefusedInactive++
            logger.info {
                "lodestone teleport: ${player.name} chose ${dest.name} (slot $slot), not activated"
            }
            return true
        }
        val armed = Teleports.schedule(
            player, dest.destination, TELEPORT_TICKS,
            "lodestone ${dest.name} (1612:11 slot $slot)"
        )
        if (armed) teleportsAccepted++
        logger.info {
            "lodestone teleport: ${player.name} chose ${dest.name} (slot $slot) -> " +
                "(${dest.x},${dest.y},plane ${dest.plane}), " +
                "${if (armed) "in $TELEPORT_TICKS tick(s)" else "ignored, teleport already pending"}" +
                ", ${if (lode == null) "no lodestone loc" else if (active) "activated" else "not activated"}"
        }
        return true
    }

    private fun load(): Map<Int, Lodestone> {
        if (!RsDatabase.available) return emptyMap()
        val named = namedLodestoneLocs
        val rows = RsDatabase.queryAll(
            "SELECT id, value FROM locs_attr WHERE field = 'morphs_1'"
        ) { rs -> rs.getInt("id") to rs.getString("value") }
        val out = LinkedHashMap<Int, Lodestone>()
        for ((parent, morph) in rows) {
            if (morph == null) continue
            val varbit = jsonInt(morph, "varbit") ?: continue
            val opt = jsonFirstOption(morph) ?: continue
            val dflt = jsonInt(morph, "default") ?: continue
            val activate = named[opt] ?: continue
            named[dflt] ?: continue
            val name = activate.first
            val action = activate.second ?: continue
            if (action !in ACTIONS) {
                logger.warn {
                    "lodestones: loc $parent -> $opt ('$name') has unexpected action '$action'"
                }
            }
            out[parent] = Lodestone(parent, varbit, opt, dflt, action, name)
        }
        return out
    }

    private fun jsonInt(blob: String, key: String): Int? =
        Regex("\"$key\"\\s*:\\s*(-?\\d+)").find(blob)?.groupValues?.get(1)?.toIntOrNull()

    private fun jsonFirstOption(blob: String): Int? =
        Regex("\"options\"\\s*:\\s*\\[\\s*(-?\\d+)").find(blob)?.groupValues?.get(1)?.toIntOrNull()

    fun isLodestone(locId: Int): Boolean = enabled && lodestones.containsKey(locId)

    fun resolvedLocFor(player: WorldPlayer, locId: Int): Int? {
        val lode = lodestones[locId] ?: return null
        return if (isActivated(player, lode)) lode.activatedLocId else lode.activateLocId
    }

    fun isActivated(player: WorldPlayer, lode: Lodestone): Boolean {
        val def = SqliteVarBits.definition(lode.varbit) ?: return false
        if (!def.isWellFormed) return false
        return def.read(player.varpValue(def.varId and 0xFFFF)) != 0
    }

    fun lodestoneStateOf(varp: (Int) -> Int): Pair<List<Lodestone>, List<Lodestone>> {
        val on = ArrayList<Lodestone>()
        val off = ArrayList<Lodestone>()
        for (lode in usable) {
            val def = SqliteVarBits.definition(lode.varbit) ?: continue
            if (def.read(varp(def.varId and 0xFFFF)) != 0) on.add(lode) else off.add(lode)
        }
        return on to off
    }

    private data class Pending(val lode: Lodestone, var ticksLeft: Int)

    private val pending = java.util.Collections.synchronizedMap(
        java.util.WeakHashMap<WorldPlayer, Pending>()
    )

    @Volatile
    var accepted: Int = 0
        private set

    @Volatile
    var activated: Int = 0
        private set

    @Volatile
    var refusedAlreadyActive: Int = 0
        private set

    internal fun resetCounters() {
        accepted = 0; activated = 0; refusedAlreadyActive = 0; pending.clear()
    }

    fun pendingTicks(player: WorldPlayer): Int? = pending[player]?.ticksLeft

    fun handleLocClick(player: WorldPlayer, locId: Int, option: Int, x: Int, y: Int, plane: Int): Boolean {
        if (!enabled) return false
        val lode = lodestones[locId] ?: return false

        if (isActivated(player, lode)) {
            refusedAlreadyActive++
            logger.info {
                "lodestone: ${player.name} clicked ${lode.name} (loc $locId, option $option), already activated"
            }
            return false
        }

        val already = pending[player]
        if (already != null && already.lode.parentLocId != locId) {
            logger.info {
                "lodestone: ${player.name} clicked ${lode.name} while ${already.lode.name} is still activating"
            }
            return false
        }

        pending[player] = Pending(lode, DELAY_TICKS)
        accepted++
        logger.info {
            "lodestone: ${player.name} '${lode.action}' on ${lode.name} (loc $locId), activates in ${DELAY_TICKS} tick(s)"
        }
        return true
    }

    @Volatile
    var ticksWithPending: Int = 0
        private set

    fun pendingCount(): Int = synchronized(pending) { pending.size }

    fun tick(): Int {
        runCatching { Teleports.tick() }.onFailure {
            logger.error(it) { "lodestones: teleport queue failed" }
        }
        if (!enabled) return 0
        var done = 0
        if (pendingCount() > 0) {
            ticksWithPending++
            if (ticksWithPending <= 3 || ticksWithPending % 25 == 0) {
                logger.info { "lodestone: ${pendingCount()} pending activation(s), accepted=$accepted activated=$activated" }
            }
        }
        val ready = ArrayList<Pair<WorldPlayer, Pending>>()
        synchronized(pending) {
            val it = pending.entries.iterator()
            while (it.hasNext()) {
                val (player, p) = it.next()
                if (!player.client.channel.isActive) { it.remove(); continue }
                if (--p.ticksLeft > 0) continue
                ready.add(player to p)
                it.remove()
            }
        }
        for ((player, p) in ready) {
            if (activate(player, p.lode)) done++
        }
        return done
    }

    fun activate(player: WorldPlayer, lode: Lodestone): Boolean {
        val def = SqliteVarBits.definition(lode.varbit)
        if (def == null || !def.isWellFormed || !def.fits(1)) {
            logger.warn {
                "lodestone: ${lode.name} not activated, varbit ${lode.varbit} " +
                    "${if (def == null) "is undefined" else "is unusable ($def)"}"
            }
            return false
        }
        val varp = def.varId and 0xFFFF
        val before = player.varpValue(varp)
        val after = def.write(before, 1)
        player.setVarpOverride(varp, after)
        if (player.client.channel.isActive) {
            player.client.write(SynthSound(SOUND, 1, 0, SOUND_VOLUME, SOUND_EXTRA))
        }
        activated++
        logger.info {
            "lodestone: ${player.name} activated ${lode.name}, varp $varp $before -> $after"
        }
        return true
    }

    fun describe(): String =
        "lodestones: ${lodestones.size} lodestones (${usable.size} usable), ${slots.size} teleport slot(s), " +
            "activation ${if (requireActivated) "required" else "not required"} for teleport"
}

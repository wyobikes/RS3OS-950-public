package com.opennxt.content.ability

import com.opennxt.content.ability.AbilityDefinitions.Effect
import com.opennxt.content.ability.AbilityDefinitions.Style
import com.opennxt.model.world.CombatDefender
import com.opennxt.model.world.WorldNpc
import com.opennxt.model.world.WorldNpcs
import com.opennxt.model.world.WorldPlayer
import mu.KotlinLogging
import java.util.IdentityHashMap

object StatusEffects {
    private val logger = KotlinLogging.logger { }

    const val FLAG = "opennxt.combat.effects"
    val enabled: Boolean get() = System.getProperty(FLAG) != "off"

    const val STUN = "stun"
    const val BIND = "bind"
    const val DEBILITATE = "debilitate"
    const val PULVERISED = "pulverised"
    const val FREEDOM = "freedom"
    const val ANTICIPATION = "anticipation"

    const val AOE_MAX_SECONDARIES = 9

    const val DEBILITATE_MULTIPLIER = 0.5

    data class BuffRow(
        val damage: Double = 1.0,
        val damageStyle: Style? = null,
        val damageTaken: Double = 1.0,
        val stunImmune: Boolean = false,
        val adrenalineGain: Double = 1.0,
        val note: String
    )

    val BUFF_TABLE: Map<String, BuffRow> = mapOf(
        "berserk" to BuffRow(damage = 1.75, damageStyle = Style.MELEE, damageTaken = 1.25, note = "Berserk: melee x1.75, taken x1.25"),
        "sunshine" to BuffRow(damage = 1.5, damageStyle = Style.MAGIC, note = "Sunshine: magic x1.5"),
        "deaths_swiftness" to BuffRow(damage = 1.5, damageStyle = Style.RANGED, note = "Death's Swiftness: ranged x1.5"),
        "anticipation" to BuffRow(damageTaken = 0.9, stunImmune = true, note = "Anticipation: taken x0.9, stun immune"),
        "freedom" to BuffRow(stunImmune = true, note = "Freedom: stun immune, clears stun"),
        "reflect" to BuffRow(damageTaken = 0.5, note = "Reflect: taken x0.5"),
        "barricade" to BuffRow(damageTaken = 0.0, note = "Barricade: immune"),
        "immortality" to BuffRow(damageTaken = 0.75, note = "Immortality: taken x0.75"),
        "devotion" to BuffRow(note = "Devotion: tracked only"),
        "natural_instinct" to BuffRow(adrenalineGain = 2.0, note = "Natural Instinct: adrenaline gain x2"),
        "rejuvenate" to BuffRow(note = "Rejuvenate: heals 2.5% of max per tick"),
        "guthixs_blessing" to BuffRow(note = "Guthix's Blessing: heals 8% every 3 ticks"),
        "chaos_roar" to BuffRow(note = "Chaos Roar: tracked only"),
        "flow" to BuffRow(note = "Sonic Wave: tracked only"),
        "tsunami" to BuffRow(note = "Tsunami: tracked only"),
        "phantom_guardian" to BuffRow(damageTaken = com.opennxt.content.combat.Conjures.phantomDamageTaken, note = "Phantom Guardian: taken x0.95 while the conjure is active"),
    )

    class NpcEffect(val name: String, var endTick: Long, val magnitude: Double, val source: String)
    class Buff(val name: String, val startTick: Long, var endTick: Long, val source: String, val periodic: Effect.PeriodicHeal?, val sourceStruct: Int) {
        val row: BuffRow? get() = BUFF_TABLE[name]
        val iconStruct: Int get() = ICON_STRUCTS[name] ?: sourceStruct
    }

    const val SCRIPT_BUFF_ICON = 10623
    const val STRUCT_STUNNED = 14883
    const val STRUCT_BOUND = 14884

    val ICON_STRUCTS: Map<String, Int> = mapOf(
        STUN to STRUCT_STUNNED,
        BIND to STRUCT_BOUND,
    )

    var iconsShown = 0; private set
    var iconsCleared = 0; private set

    private val npcEffects = IdentityHashMap<WorldNpc, LinkedHashMap<String, NpcEffect>>()
    private val buffs = IdentityHashMap<WorldPlayer, LinkedHashMap<String, Buff>>()

    var npcEffectsApplied = 0; private set
    var npcEffectsExtended = 0; private set
    var npcEffectsExpired = 0; private set
    var npcEffectsCleared = 0; private set
    var buffsApplied = 0; private set
    var buffsExpired = 0; private set
    var buffsCleared = 0; private set
    var periodicHeals = 0; private set
    var stunsSkippedSwings = 0; private set
    var bindsSkippedSteps = 0; private set
    var wireCalls = 0; private set

    private val now: Long get() = AbilityActivation.clock

    fun stunImmune(npc: WorldNpc): Boolean =
        runCatching { com.opennxt.model.combat.SeedData.refCombat(npc.gameId)?.immuneToStun == true }.getOrDefault(false)

    var immuneRefusals = 0; private set

    fun stun(npc: WorldNpc, ticks: Int, source: String): Long? {
        if (!enabled || ticks <= 0 || !npc.alive) return null
        if (stunImmune(npc)) { immuneRefusals++; logger.info { "status: tick $now npc ${npc.name ?: "npc"} ${npc.gameId} is stun immune; $source's stun not applied" }; return null }
        return applyNpc(npc, STUN, ticks, 0.0, source)
    }

    fun bind(npc: WorldNpc, ticks: Int, source: String): Long? {
        if (!enabled || ticks <= 0 || !npc.alive) return null
        if (stunImmune(npc)) { immuneRefusals++; logger.info { "status: tick $now npc ${npc.name ?: "npc"} ${npc.gameId} is stun immune; $source's bind not applied" }; return null }
        npc.movement.reset()
        return applyNpc(npc, BIND, ticks, 0.0, source)
    }

    fun debuff(npc: WorldNpc, name: String, ticks: Int, source: String, magnitude: Double = 0.0): Long? {
        if (!enabled || ticks <= 0 || !npc.alive) return null
        return applyNpc(npc, name.lowercase(), ticks, magnitude, source)
    }

    private fun applyNpc(npc: WorldNpc, name: String, ticks: Int, magnitude: Double, source: String): Long {
        val end = now + ticks + 1
        val map = npcEffects.getOrPut(npc) { LinkedHashMap() }
        val existing = map[name]
        if (existing != null && existing.endTick > now) {
            if (end > existing.endTick) {
                existing.endTick = end
                npcEffectsExtended++
                logger.info { "status: tick $now npc ${npc.name ?: "npc"} ${npc.gameId} [slot ${npc.infoIndex}] $name extended to $end by $source" }
            } else {
                npcEffectsExtended++
                logger.info { "status: tick $now npc ${npc.name ?: "npc"} ${npc.gameId} [slot ${npc.infoIndex}] $name already runs to ${existing.endTick}, unchanged by $source" }
            }
            return existing.endTick
        }
        map[name] = NpcEffect(name, end, magnitude, source)
        npcEffectsApplied++
        logger.info { "status: tick $now npc ${npc.name ?: "npc"} ${npc.gameId} [slot ${npc.infoIndex}] $name applied by $source, ends $end" }
        return end
    }

    private fun npcActive(npc: WorldNpc, name: String): NpcEffect? =
        if (!enabled) null else npcEffects[npc]?.get(name)?.takeIf { it.endTick > now }

    fun isStunned(npc: WorldNpc): Boolean = npcActive(npc, STUN) != null
    fun isBound(npc: WorldNpc): Boolean = npcActive(npc, BIND) != null
    fun hasNpcEffect(npc: WorldNpc, name: String): Boolean = npcActive(npc, name.lowercase()) != null
    fun npcEffectEnd(npc: WorldNpc, name: String): Long? = npcActive(npc, name.lowercase())?.endTick

    fun npcStunRemaining(npc: WorldNpc): Int? {
        val e = npcActive(npc, STUN) ?: return null
        stunsSkippedSwings++
        return (e.endTick - now).toInt()
    }

    fun blocksStep(npc: WorldNpc): Boolean {
        if (!isBound(npc)) return false
        bindsSkippedSteps++
        return true
    }

    fun damageDealtMultiplier(npc: WorldNpc): Double = if (npcActive(npc, DEBILITATE) != null) DEBILITATE_MULTIPLIER else 1.0

    fun activeNpcEffects(npc: WorldNpc): List<NpcEffect> = if (!enabled) emptyList() else npcEffects[npc]?.values?.filter { it.endTick > now }.orEmpty()

    fun clearNpc(npc: WorldNpc, why: String): Int {
        val map = npcEffects.remove(npc) ?: return 0
        val live = map.values.count { it.endTick > now }
        if (live > 0) {
            npcEffectsCleared += live
            logger.info { "status: tick $now npc ${npc.name ?: "npc"} ${npc.gameId} [slot ${npc.infoIndex}] cleared ${map.values.filter { it.endTick > now }.map { "${it.name}->${it.endTick}" }} - $why" }
        }
        return live
    }

    fun buff(player: WorldPlayer, name: String, ticks: Int, source: String, periodic: Effect.PeriodicHeal? = null, sourceStruct: Int = -1): Long? {
        if (!enabled || ticks <= 0) return null
        val key = name.lowercase()
        val end = now + ticks
        val map = buffs.getOrPut(player) { LinkedHashMap() }
        val existing = map[key]
        if (existing != null && existing.endTick > now) {
            if (end > existing.endTick) existing.endTick = end
            logger.info { "status: tick $now player ${player.name} buff $key extended to ${existing.endTick} by $source" }
            wire(player, existing, true)
            return existing.endTick
        }
        val b = Buff(key, now, end, source, periodic, sourceStruct)
        map[key] = b
        buffsApplied++
        logger.info { "status: tick $now player ${player.name} buff $key applied by $source, ends $end${b.row?.let { " (${it.note})" } ?: ""}" }
        wire(player, b, true)
        return end
    }

    fun freedom(player: WorldPlayer, ticks: Int, source: String, sourceStruct: Int = -1): Long? {
        if (!enabled) return null
        val cleared = runCatching { com.opennxt.content.impl.Thieving.clearStun(player.contentPlayer, "Freedom ($source)") }.getOrDefault(false)
        logger.info { "status: tick $now player ${player.name} Freedom: pickpocket stun cleared=$cleared" }
        return buff(player, FREEDOM, ticks, source, null, sourceStruct)
    }

    private fun active(player: WorldPlayer): Collection<Buff> = if (!enabled) emptyList() else buffs[player]?.values?.filter { it.endTick > now }.orEmpty()

    fun hasBuff(player: WorldPlayer, name: String): Boolean = active(player).any { it.name == name.lowercase() }
    fun buffEnd(player: WorldPlayer, name: String): Long? = active(player).firstOrNull { it.name == name.lowercase() }?.endTick
    fun activeBuffs(player: WorldPlayer): List<Buff> = active(player).toList()

    fun damageMultiplier(player: WorldPlayer, style: Style): Double {
        var m = 1.0
        for (b in active(player)) {
            val r = b.row ?: continue
            if (r.damage != 1.0 && (r.damageStyle == null || r.damageStyle == style)) m *= r.damage
        }
        return m
    }

    fun damageTakenMultiplier(player: WorldPlayer): Double {
        var m = 1.0
        for (b in active(player)) b.row?.let { if (it.damageTaken != 1.0) m *= it.damageTaken }
        return m
    }

    fun stunImmune(player: WorldPlayer): Boolean = active(player).any { it.row?.stunImmune == true }

    fun adrenalineGainMultiplier(player: WorldPlayer): Double {
        var m = 1.0
        for (b in active(player)) b.row?.let { if (it.adrenalineGain != 1.0) m *= it.adrenalineGain }
        return m
    }

    fun scaleOutgoing(player: WorldPlayer, style: Style, amount: Int): Int {
        if (amount <= 0) return amount
        val m = damageMultiplier(player, style) * com.opennxt.content.impl.PrayerBook.damageMultiplier(player, style)
        return if (m == 1.0) amount else Math.floor(amount * m).toInt().coerceAtLeast(0)
    }

    fun scaleNpcHit(npc: WorldNpc, defender: CombatDefender, amount: Int): Int {
        if (amount <= 0) return amount
        val prayer = (defender as? WorldPlayer)?.let { com.opennxt.content.impl.PrayerBook.incomingMultiplier(it, npc) } ?: 1.0
        if (!enabled) return if (prayer == 1.0) amount else Math.floor(amount * prayer).toInt().coerceAtLeast(0)
        var m = damageDealtMultiplier(npc) * prayer
        (defender as? WorldPlayer)?.let { m *= damageTakenMultiplier(it) }
        return if (m == 1.0) amount else Math.floor(amount * m).toInt().coerceAtLeast(0)
    }

    fun clearPlayer(player: WorldPlayer, why: String): Int {
        val map = buffs.remove(player) ?: return 0
        val live = map.values.filter { it.endTick > now }
        if (live.isNotEmpty()) {
            buffsCleared += live.size
            logger.info { "status: tick $now player ${player.name} cleared ${live.map { "${it.name}->${it.endTick}" }} - $why" }
            for (b in live) wire(player, b, false)
        }
        return live.size
    }

    fun wire(player: WorldPlayer, buff: Buff, applied: Boolean) {
        wireCalls++
        sendIcon(player, buff.iconStruct, applied)
    }

    fun playerStunIcon(player: WorldPlayer, on: Boolean) {
        if (!enabled) return
        sendIcon(player, STRUCT_STUNNED, on)
    }

    private fun sendIcon(player: WorldPlayer, struct: Int, on: Boolean) {
        if (struct < 0) return
        if (on) iconsShown++ else iconsCleared++
        AbilityActivation.sendPacket(player, com.opennxt.net.game.serverprot.RunClientScript(SCRIPT_BUFF_ICON, arrayOf<Any>(struct, if (on) 1 else 0)))
    }

    fun tick(npcs: WorldNpcs, players: List<WorldPlayer>) {
        if (!enabled) {
            if (npcEffects.isNotEmpty() || buffs.isNotEmpty()) {
                logger.warn { "status: disabled (-D$FLAG=off), dropped ${npcEffects.values.sumOf { it.size }} npc status(es) and ${buffs.values.sumOf { it.size }} buff(s)" }
                npcEffects.clear(); buffs.clear()
            }
            return
        }
        val t = now
        val npcIt = npcEffects.entries.iterator()
        while (npcIt.hasNext()) {
            val (npc, map) = npcIt.next()
            if (!npc.alive || npc.despawned) {
                val live = map.values.count { it.endTick > t }
                if (live > 0) {
                    npcEffectsCleared += live
                    logger.info { "status: tick $t npc ${npc.gameId} [slot ${npc.infoIndex}] cleared ${map.keys}, npc dead or despawned" }
                }
                npcIt.remove(); continue
            }
            val eIt = map.values.iterator()
            while (eIt.hasNext()) {
                val e = eIt.next()
                if (e.endTick <= t) {
                    eIt.remove(); npcEffectsExpired++
                    logger.info { "status: tick $t npc ${npc.name ?: "npc"} ${npc.gameId} [slot ${npc.infoIndex}] ${e.name} expired (end ${e.endTick})" }
                }
            }
            if (map.isEmpty()) npcIt.remove()
        }
        val pIt = buffs.entries.iterator()
        while (pIt.hasNext()) {
            val (player, map) = pIt.next()
            val bIt = map.values.iterator()
            while (bIt.hasNext()) {
                val b = bIt.next()
                if (b.endTick <= t) {
                    bIt.remove(); buffsExpired++
                    logger.info { "status: tick $t player ${player.name} buff ${b.name} expired (end ${b.endTick})" }
                    wire(player, b, false)
                    continue
                }
                val ph = b.periodic ?: continue
                val every = ph.everyTicks.coerceAtLeast(1)
                if ((t - b.startTick) % every == 0L) {
                    val amount = Math.floor(player.maxLifepoints * ph.percent / 100.0).toInt()
                    if (amount > 0) {
                        val healed = CombatVitals.heal(player, amount, "buff ${b.name} (${ph.raw})")
                        if (healed > 0) periodicHeals++
                    }
                }
            }
            if (map.isEmpty()) pIt.remove()
        }
    }

    fun trackedNpcs(): Int = npcEffects.size
    fun trackedPlayers(): Int = buffs.size
}

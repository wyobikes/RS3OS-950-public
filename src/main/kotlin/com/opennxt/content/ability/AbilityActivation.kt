package com.opennxt.content.ability

import com.opennxt.api.stat.Stat
import com.opennxt.content.ActionLock
import com.opennxt.content.ability.AbilityDefinitions.Definition
import com.opennxt.content.ability.AbilityDefinitions.Effect
import com.opennxt.content.ability.AbilityDefinitions.HitSpec
import com.opennxt.content.ability.AbilityDefinitions.HitSource
import com.opennxt.content.ability.AbilityDefinitions.Style
import com.opennxt.content.impl.AbilityBar
import com.opennxt.model.combat.CombatFormulas
import com.opennxt.model.combat.CombatStyle
import com.opennxt.model.combat.CombatXp
import com.opennxt.model.combat.PlayerCombat
import com.opennxt.model.combat.TargetHud
import com.opennxt.model.entity.rendering.PlayerUpdates
import com.opennxt.model.entity.rendering.npc.blocks.NpcHitsBlock
import com.opennxt.model.world.DeathResult
import com.opennxt.model.world.EquippedWeapon
import com.opennxt.model.world.GroundItems
import com.opennxt.model.world.PlayerCombatStats
import com.opennxt.model.world.TileLocation
import com.opennxt.model.world.WorldNpc
import com.opennxt.model.world.WorldNpcs
import com.opennxt.model.world.WorldPlayer
import com.opennxt.net.game.GamePacket
import com.opennxt.net.game.clientprot.IfButtonN
import com.opennxt.net.game.serverprot.MessageGame
import com.opennxt.net.game.serverprot.RunClientScript
import com.opennxt.net.game.serverprot.variables.ClientSetvarcLarge
import com.opennxt.net.game.serverprot.variables.VarpLarge
import mu.KotlinLogging
import java.util.Collections
import java.util.EnumMap
import java.util.IdentityHashMap
import java.util.Random

object AbilityActivation {
    private val logger = KotlinLogging.logger { }

    const val FLAG = "opennxt.experiment.abilities"
    val enabled: Boolean get() = System.getProperty(FLAG) != "false"

    const val VARP_COOLDOWN_START = 908
    const val VARP_COOLDOWN_END = 909
    const val VARP_ADRENALINE = 679
    const val VARP_ADRENALINE_PREVIOUS = 9417
    const val VARP_GCD_START = 4501
    const val VARP_GCD_END = 4502
    const val VARP_ACTIVATED = 686
    const val VARC_ACTIVATED = 4098
    const val SCRIPT_COOLDOWN_OVERLAY = 6570
    const val SCRIPT_GCD_OVERLAY = 6066
    const val MESSAGE_TYPE_NOT_READY = 109
    const val MESSAGE_NOT_READY = "Ability not ready yet."

    const val BUTTON_OP_CLICK = 1
    const val OFFHAND_SLOT = 5
    const val TWO_HANDED_EQUIP_ID = 5
    const val PARAM_WEAPON_TYPE = 686
    const val PARAM_ITEM_TIER = 4596
    const val NECROMANCY_WEAPON_X10_PER_TIER = 96
    const val MAX_PENDING_HITS = 96
    const val SPLAT_AMOUNT_MAX = 0x7fff
    const val MAX_SPLATS_PER_BLOCK = 255
    const val ABILITY_SEED = 0x41424C54L
    const val RETAIN_SWEEP_TICKS = 100L
    const val RECENT_HITS_KEPT = 256

    const val ACCURACY_FLAG = "opennxt.combat.abilityAccuracy"
    const val DASH_FLAG = "opennxt.combat.dash"
    val accuracyMode: String get() = when (val v = System.getProperty(ACCURACY_FLAG)?.trim()?.lowercase()) { "miss", "off" -> v; else -> "multiplier" }
    val dashEnabled: Boolean get() = System.getProperty(DASH_FLAG) != "off"
    const val NECROMANCY_AFFINITY = 60
    const val NECROSIS_MAX = 12
    const val RESIDUAL_SOULS_MAX = 3
    const val STORM_SHARDS_MAX = 10
    const val COST_TENTHS_PER_NECROSIS = 100
    const val BOUNCE_EVERY_TICKS = 2
    const val MESSAGE_NO_SOULS = "This ability requires residual souls."

    enum class Reason {
        DISABLED, BAR_NOT_OPEN, ADDITIONAL_BAR, EMPTY_SLOT, UNRESOLVED, DEAD, LOCKED, NOT_MODELLED, LEVEL, STYLE,
        REQUIREMENT, COMBAT_OFF, NO_TARGET, TARGET_DEAD, WRONG_PLANE, OUT_OF_RANGE, NOT_COMPUTABLE, GCD, COOLDOWN,
        ADRENALINE, QUEUE_FULL, NO_SOULS, NO_SHARDS
    }

    sealed class Outcome {
        data class Activated(
            val definition: Definition,
            val target: WorldNpc?,
            val tick: Long,
            val adrenalineBefore: Int,
            val adrenalineAfter: Int,
            val gcdEnd: Long,
            val cooldownEnd: Long?,
            val hitTicks: List<Long>,
            val hitSource: HitSource,
            val animation: Int?,
            val activationId: Long = 0,
            val channelEnd: Long? = null,
            val selfEffectsApplied: List<String> = emptyList()
        ) : Outcome()

        data class Refused(val reason: Reason, val detail: String) : Outcome()
    }

    data class AppliedHit(
        val tick: Long, val player: String, val structId: Int, val target: WorldNpc,
        val requested: Int, val applied: Int, val percent: Double, val fallback: Boolean, val killed: Boolean,
        val kind: AbilityDefinitions.HitKind,
        val primary: Boolean = true,
        val chance: Double? = null,
        val activationId: Long = 0
    )

    var clock: Long = 0
        private set

    var random: Random = Random(PlayerCombat.bootSeed xor ABILITY_SEED)

    var observer: ((WorldPlayer, GamePacket) -> Unit)? = null

    private val states = IdentityHashMap<WorldPlayer, AbilityState>()
    private data class Retained(val gcdEnd: Long, val cooldowns: Map<Int, Long>)
    private val retained = HashMap<String, Retained>()
    private var listenedTo: WorldNpcs? = null
    private var sequence = 0L
    private var activationSequence = 0L

    var onAbilityCast: ((WorldPlayer, Definition) -> Unit)? = null
    var onAbilityHitLanded: ((WorldPlayer, PendingHit, WorldNpc, Int) -> Unit)? = null
    var abilityMaxHitX10Override: ((WorldPlayer, Style, Int?) -> Int?)? = null
    var preTick: ((List<WorldPlayer>) -> Unit)? = null
    var seamFailures = 0
        private set

    var channelsStarted = 0; private set
    var channelsCancelled = 0; private set
    var channelsCompleted = 0; private set
    var secondariesQueued = 0; private set
    var secondariesDropped = 0; private set
    var bouncesQueued = 0; private set
    var targetEffectsApplied = 0; private set
    var selfEffectsApplied = 0; private set
    var dashes = 0; private set
    var healsFromHits = 0; private set
    var accuracyScaled = 0; private set
    var accuracyMisses = 0; private set
    var executeMultiplied = 0; private set
    private val loggedChanceGaps = HashSet<String>()

    var activations = 0
        private set
    private val refusalCounts = EnumMap<Reason, Int>(Reason::class.java)
    var hitsApplied = 0
        private set
    var hitsCancelled = 0
        private set
    var kills = 0
        private set
    var fallbackActivations = 0
        private set
    var autoAdrenalineAwards = 0
        private set
    var autoSwingsOnGcd = 0
        private set
    var bookClicks = 0
        private set
    var containedHitFailures = 0
        private set
    val recentHits = ArrayDeque<AppliedHit>()

    fun refusals(reason: Reason): Int = refusalCounts[reason] ?: 0
    fun stateOrNull(player: WorldPlayer): AbilityState? = states[player]
    fun trackedPlayers(): Int = states.size
    fun retainedAccounts(): Int = retained.size

    fun stateFor(player: WorldPlayer): AbilityState = states.getOrPut(player) {
        AbilityState(player.name).also { s ->
            retained.remove(player.name.lowercase())?.let { r ->
                s.gcdEnd = r.gcdEnd
                s.cooldownEnd.putAll(r.cooldowns)
            }
        }
    }

    fun handleButton(player: WorldPlayer, packet: IfButtonN): Boolean {
        if (!enabled || packet.buttonOp != BUTTON_OP_CLICK) return false
        val iface = packet.interfaceId
        val component = packet.component
        AbilityBar.extraBarSlotOf(iface, component)?.let { slot ->
            refuse(player, null, null, "additional bar $iface:$component (slot $slot)", Reason.ADDITIONAL_BAR,
                "additional action bars are not supported")
            return true
        }
        val slot = AbilityBar.slotOf(iface, component)
        if (slot == null) {
            if (iface in AbilityBar.BOOK_TYPES) {
                bookClicks++
                logger.info {
                    "abilities: ${player.name} clicked book $iface:$component[${packet.arg2}], not an activation"
                }
            }
            return false
        }
        if (player.interfaces.isOpened(iface)) {
            val value = player.varpValue(AbilityBar.slotVarp(slot))
            if (!AbilityBar.isEmpty(value) &&
                com.opennxt.content.impl.PrayerBook.activateFromBar(player, AbilityBar.unpackType(value), AbilityBar.unpackIndex(value))
            ) return true
        }
        activateSlot(player, iface, slot, queueable = true)
        return true
    }

    fun activateSlot(player: WorldPlayer, iface: Int, slot: Int, queueable: Boolean = false, fromQueue: Int? = null): Outcome {
        val via = if (fromQueue != null) "queued bar $iface slot $slot" else "bar $iface slot $slot"
        if (!enabled) return refuse(player, null, null, via, Reason.DISABLED, "-D$FLAG=false")
        if (!player.interfaces.isOpened(iface)) return refuse(player, null, null, via, Reason.BAR_NOT_OPEN, "interface $iface is not open for this player")
        val varp = AbilityBar.slotVarp(slot)
        val value = player.varpValue(varp)
        if (AbilityBar.isEmpty(value)) return refuse(player, null, null, via, Reason.EMPTY_SLOT, "varp $varp = $value holds no ability")
        val type = AbilityBar.unpackType(value)
        val index = AbilityBar.unpackIndex(value)
        val ability = AbilityBar.abilityFor(type, index)
            ?: return refuse(player, null, null, via, Reason.UNRESOLVED, "varp $varp = $value (book $type, index $index) is not a known ability")
        val definition = AbilityDefinitions.forStruct(ability.structId)
            ?: return refuse(player, null, null, via, Reason.UNRESOLVED, "struct ${ability.structId} ${ability.name} is not a book entry of the seed")
        if (fromQueue != null && fromQueue != definition.structId) {
            return refuse(player, null, definition, via, Reason.UNRESOLVED, "the slot was re-bound from struct $fromQueue to ${definition.structId} while queued")
        }
        return activate(player, definition, via, if (queueable) iface to slot else null)
    }

    fun activate(player: WorldPlayer, def: Definition, via: String = "direct", queueSlot: Pair<Int, Int>? = null): Outcome {
        if (!enabled) return refuse(player, null, def, via, Reason.DISABLED, "-D$FLAG=false")
        val st = stateFor(player)
        val now = clock
        fun no(reason: Reason, detail: String, notReady: Boolean = false) = refuse(player, st, def, via, reason, detail, notReady)
        val effectsOn = StatusEffects.enabled
        if (MovementAbilities.handles(def)) return MovementAbilities.activate(player, st, def, via, queueSlot) { r, d, nr -> refuse(player, st, def, via, r, d, nr) }

        if (player.currentLifepoints <= 0) return no(Reason.DEAD, "lifepoints ${player.currentLifepoints}")
        val freesStun = effectsOn && def.selfEffects.any { it is Effect.Buff && it.name == StatusEffects.FREEDOM }
        ActionLock.reasonFor(player.contentPlayer, ignoreStuns = freesStun)?.let { return no(Reason.LOCKED, it) }
        val selfOnly = effectsOn && def.selfOnly
        if (!def.modelled && !selfOnly) return no(Reason.NOT_MODELLED, def.notModelledWhy ?: "no hits")

        val levelStat = levelStat(def.style)
        val required = def.level
        if (required != null) {
            val have = player.stats.getLevel(levelStat)
            if (have < required) return no(Reason.LEVEL, "${levelStat.name} $have < $required")
        }
        val weapon = player.wornWeapon
        val weaponStyle = weaponStyle(weapon)
        val bookOnly = def.style.isCombatBook || !effectsOn
        val hitStyle: Style = if (bookOnly) def.style else (weaponStyle ?: return no(Reason.STYLE, "a ${def.style.book} ability with ${weapon?.definition?.name ?: "no weapon"} (a weapon of no style)"))
        if (bookOnly && weaponStyle != def.style) {
            return no(Reason.STYLE, "a ${def.style.book} ability with ${weapon?.definition?.name ?: "no weapon"} (${weaponStyle?.book ?: "a weapon of no style"})")
        }
        unmetRequirement(player, def)?.let { return no(Reason.REQUIREMENT, it) }

        val needsTarget = !(selfOnly && "target" !in def.requires)
        var npc: WorldNpc? = null
        var reach = 0
        val dash = if (effectsOn && dashEnabled) def.selfEffects.filterIsInstance<Effect.Dash>().firstOrNull() else null
        if (needsTarget) {
            if (!PlayerCombat.enabled) return no(Reason.COMBAT_OFF, "combat is disabled (-Dopennxt.experiment.combat)")
            val target = PlayerCombat.targetOf(player) ?: return no(Reason.NO_TARGET, "not engaged on any npc")
            if (!target.alive) return no(Reason.TARGET_DEAD, "${target.name ?: "npc"} ${target.gameId} is dead")
            val loc = player.entity.location
            if (target.location.plane != loc.plane) return no(Reason.WRONG_PLANE, "target on plane ${target.location.plane}, player on ${loc.plane}")
            val distance = PlayerCombat.footprintDistance(target, loc.x, loc.y)
            reach = PlayerCombat.playerAttackRange(player)
            val allowed = if (dash != null) maxOf(reach, dash.range) else reach
            if (distance > allowed) return no(Reason.OUT_OF_RANGE, "distance $distance > reach $allowed")
            npc = target
        }
        val storm = if (effectsOn) def.selfEffects.filterIsInstance<Effect.StormShard>().firstOrNull() else null
        val needsDamage = def.hits.isNotEmpty() || storm != null
        var damage: Double? = null
        if (needsDamage) {
            val (d, damageWhy) = abilityDamageOrWhy(player, hitStyle)
            damage = d ?: return no(Reason.NOT_COMPUTABLE, damageWhy ?: "no ability damage")
        }

        val soulsRule = if (effectsOn) def.selfEffects.filterIsInstance<Effect.HitsPerSoul>().firstOrNull() else null
        val shardsRule = if (effectsOn) def.selfEffects.filterIsInstance<Effect.ConsumeShards>().firstOrNull() else null
        val costRule = if (effectsOn) def.selfEffects.filterIsInstance<Effect.CostPerNecrosis>().firstOrNull() else null
        var soulsUsed = 0
        var fixedDamage: Int? = null
        val schedule: List<HitSpec> = when {
            soulsRule != null -> {
                if (st.residualSouls <= 0) return refuse(player, st, def, via, Reason.NO_SOULS, "no residual souls (${def.name} fires one hit per soul)", message = MESSAGE_NO_SOULS)
                soulsUsed = minOf(st.residualSouls, soulsRule.max, def.hits.size)
                def.hits.take(soulsUsed)
            }
            shardsRule != null -> {
                if (st.stormShards <= 0) return no(Reason.NO_SHARDS, "no storm shards stored (${def.name} deals the stored total)")
                fixedDamage = st.stormShardDamage
                listOf(HitSpec(0, 100.0, 100.0))
            }
            else -> def.hits
        }

        fun queuedOr(reason: Reason, detail: String): Outcome.Refused {
            val q = queueSlot?.let { (qi, qs) -> AbilityQueue.offer(player, st, def, qi, qs, now) }
            return if (q != null) no(reason, "$detail; $q") else no(reason, detail, notReady = true)
        }
        if (st.onGcd(now)) return queuedOr(Reason.GCD, "global cooldown until tick ${st.gcdEnd} (now $now)")
        val cooldownLeft = st.cooldownRemaining(def.structId, now)
        if (cooldownLeft > 0) return queuedOr(Reason.COOLDOWN, "${def.name} cools down for $cooldownLeft more tick(s)")
        val necrosisConsumed = if (costRule != null) minOf(st.necrosis, costRule.consumes) else 0
        val cost = (def.adrenalineCost - necrosisConsumed * COST_TENTHS_PER_NECROSIS).coerceAtLeast(0)
        if (st.adrenaline.tenths < cost) return no(Reason.ADRENALINE, "needs $cost tenths, has ${st.adrenaline.tenths}")
        if (st.pending.size + schedule.size > MAX_PENDING_HITS) {
            return no(Reason.QUEUE_FULL, "${st.pending.size} hits queued + ${schedule.size} > $MAX_PENDING_HITS")
        }

        val activationId = ++activationSequence
        AbilityQueue.onCommit(player, st, def)
        st.channel?.let { ch -> if (now < ch.endTick) cancelChannel(player, st, ch, "a second activation (${def.name})") else st.channel = null }
        val before = st.adrenaline.tenths
        if (cost > 0) {
            if (!st.adrenaline.trySpend(cost)) return no(Reason.ADRENALINE, "needs $cost tenths, has ${st.adrenaline.tenths}")
        } else {
            st.adrenaline.add(scaledGain(player, def.adrenalineGain))
        }
        if (necrosisConsumed > 0) st.necrosis -= necrosisConsumed
        if (soulsUsed > 0) st.residualSouls -= soulsUsed
        if (shardsRule != null) { st.stormShards = 0; st.stormShardDamage = 0 }
        val after = st.adrenaline.tenths
        val gcd = AbilityDefinitions.gcdTicks
        st.gcdStart = now
        st.gcdEnd = now + gcd
        val channelTicks = if (effectsOn) def.channelTicks else 0
        PlayerCombat.holdSwing(player, PlayerCombat.ticks() + 1 + maxOf(gcd, channelTicks))
        val cooldownEnd = if (def.cooldownTicks > 0) (now + def.cooldownTicks).also { st.cooldownEnd[def.structId] = it } else null
        CombatVitals.onCombatAction(player)

        val applied = ArrayList<String>()
        var dashTo: TileLocation? = null
        if (effectsOn) {
            val periodic = def.selfEffects.filterIsInstance<Effect.PeriodicHeal>().firstOrNull()
            for (e in def.selfEffects) {
                when (e) {
                    is Effect.Buff -> {
                        val end = if (e.name == StatusEffects.FREEDOM) StatusEffects.freedom(player, e.ticks, def.name, def.structId)
                            else StatusEffects.buff(player, e.name, e.ticks, def.name, periodic, def.structId)
                        if (end != null) applied += "${e.raw}->$end"
                    }
                    is Effect.Necrosis -> { st.necrosis = (st.necrosis + e.stacks).coerceAtMost(NECROSIS_MAX); applied += "${e.raw}=${st.necrosis}" }
                    is Effect.ResidualSoul -> { st.residualSouls = (st.residualSouls + e.souls).coerceAtMost(RESIDUAL_SOULS_MAX); applied += "${e.raw}=${st.residualSouls}" }
                    is Effect.StormShard -> if (damage != null && st.stormShards < minOf(e.max, STORM_SHARDS_MAX)) {
                        val pct = e.minPct + random.nextDouble() * (e.maxPct - e.minPct)
                        val stored = Math.floor(damage * pct / 100.0).toInt()
                        st.stormShards++; st.stormShardDamage += stored
                        applied += "${e.raw}=${st.stormShards} (+$stored, stored ${st.stormShardDamage})"
                    } else applied += "${e.raw} at the cap ${st.stormShards}"
                    is Effect.Dash -> if (dash != null && npc != null) {
                        val loc = player.entity.location
                        val tile = PlayerCombat.nearestAttackTile(npc, loc.x, loc.y, 1)
                        if (tile != null && (tile[0] != loc.x || tile[1] != loc.y)) {
                            val to = TileLocation(tile[0], tile[1], loc.plane)
                            player.entity.movement.teleport(to)
                            dashTo = to
                            dashes++
                            applied += "${e.raw}->(${to.x},${to.y})"
                        } else applied += "${e.raw}: already adjacent or no tile"
                    }
                    is Effect.CostPerNecrosis -> if (necrosisConsumed > 0) applied += "${e.raw}: consumed $necrosisConsumed, cost $cost"
                    is Effect.HitsPerSoul -> applied += "${e.raw}: $soulsUsed hit(s)"
                    is Effect.ConsumeShards -> applied += "${e.raw}: ${fixedDamage ?: 0} stored"
                    is Effect.PeriodicHeal -> {}
                    else -> {}
                }
            }
            selfEffectsApplied += applied.size
        }

        val animation = AbilityGraphics.animationFor(player, def)
        val fallback = def.hitSource == HitSource.FALLBACK
        val hitTicks = ArrayList<Long>(schedule.size)
        val targetEffects = if (effectsOn) def.targetEffects else emptyList()
        val decay = targetEffects.filterIsInstance<Effect.Decay>().firstOrNull()
        var overTimeIndex = 0
        if (npc != null) {
            for ((i, h) in schedule.withIndex()) {
                val due = now + h.delayTicks
                val splatDelay = if (i == 0 && h.delayTicks == 0 && animation != null) runCatching { PlayerCombat.npcSplatDelay(animation) }.getOrDefault(0) else 0
                val scale = if (decay != null && h.kind != AbilityDefinitions.HitKind.HIT) (1.0 - decay.percent / 100.0 * (overTimeIndex++)).coerceAtLeast(0.0) else 1.0
                st.enqueue(PendingHit(++sequence, def.structId, def.name, due, h.minPct, h.maxPct, npc, hitStyle, splatDelay, now, fallback, h.kind,
                    damageAtCommit = damage, reachAtCommit = reach, activationId = activationId, primary = true,
                    onLand = if (i == 0) targetEffects else emptyList(), dashTo = dashTo, fixedDamage = fixedDamage, bandScale = scale))
                hitTicks += due
            }
        }
        var channelEnd: Long? = null
        if (channelTicks > 0 && npc != null) {
            val loc = player.entity.location
            st.channel = Channel(activationId, def.structId, def.name, now, now + channelTicks, dashTo?.x ?: loc.x, dashTo?.y ?: loc.y, loc.plane, npc)
            channelEnd = now + channelTicks
            channelsStarted++
        }
        st.activations++
        activations++
        if (fallback) fallbackActivations++
        onAbilityCast?.let { hook -> try { hook(player, def) } catch (t: Throwable) { seamFailures++; logger.error(t) { "abilities: onAbilityCast hook failed for ${player.name}" } } }

        val graphic = AbilityGraphics.play(player, def).second
        if (cooldownEnd != null) {
            varp(player, VARP_COOLDOWN_START, now.toInt())
            varp(player, VARP_COOLDOWN_END, cooldownEnd.toInt())
        }
        varp(player, VARP_ADRENALINE_PREVIOUS, before)
        varp(player, VARP_ADRENALINE, after)
        varp(player, VARP_GCD_START, now.toInt())
        varp(player, VARP_GCD_END, (now + gcd).toInt())
        varp(player, VARP_ACTIVATED, def.structId)
        varp(player, VARP_ACTIVATED, -1)
        if (cooldownEnd != null) send(player, RunClientScript(SCRIPT_COOLDOWN_OVERLAY, arrayOf<Any>(def.structId, now.toInt(), cooldownEnd.toInt(), 1, 1)))
        send(player, ClientSetvarcLarge(VARC_ACTIVATED, def.structId))
        send(player, RunClientScript(SCRIPT_COOLDOWN_OVERLAY, arrayOf<Any>(AbilityDefinitions.GCD_STRUCT, now.toInt(), (now + gcd).toInt(), 1, 1)))
        send(player, RunClientScript(SCRIPT_GCD_OVERLAY, arrayOf<Any>(AbilityDefinitions.GCD_STRUCT)))

        logger.info {
            "abilities: ${player.name} activated ${def.name} (struct ${def.structId}, ${def.style.book} ${def.category?.seed ?: "?"}) via $via " +
                (npc?.let { "on ${it.name ?: "npc"} ${it.gameId} [slot ${it.infoIndex}] " } ?: "(self) ") +
                "at tick $now [#$activationId]: adrenaline $before -> $after, GCD to ${now + gcd}, " +
                "cooldown to ${cooldownEnd ?: "none"}, hits at $hitTicks (${def.hitSource})" +
                (if (fallback) " fallback ${def.hits.first().minPct}-${def.hits.first().maxPct}%" else "") +
                (channelEnd?.let { ", channel to $it" } ?: "") +
                ", anim ${animation ?: "none"}, gfx ${graphic ?: "none"}" + (damage?.let { ", damage ${"%.1f".format(it)}" } ?: "") +
                (if (applied.isEmpty()) "" else "; self effects $applied") +
                (if (targetEffects.isEmpty()) "" else "; target effects ${targetEffects.map { it.raw }}") +
                (if (def.unappliedEffects.isEmpty()) "" else "; unsupported effects ${def.unappliedEffects}")
        }
        return Outcome.Activated(def, npc, now, before, after, now + gcd, cooldownEnd, hitTicks, def.hitSource, animation,
            activationId, channelEnd, applied)
    }

    private fun scaledGain(player: WorldPlayer, gain: Int): Int {
        if (gain <= 0) return gain
        val m = StatusEffects.adrenalineGainMultiplier(player)
        return if (m == 1.0) gain else Math.floor(gain * m).toInt()
    }

    private fun cancelChannel(player: WorldPlayer, st: AbilityState, ch: Channel, why: String) {
        val n = st.pending.count { it.activationId == ch.activationId }
        st.pending.removeAll { it.activationId == ch.activationId }
        hitsCancelled += n
        channelsCancelled++
        st.channel = null
        logger.info { "abilities: ${player.name}'s ${ch.name} channel [#${ch.activationId}] cancelled at tick $clock ($why); dropped $n hit(s)" }
    }

    private fun refuse(
        player: WorldPlayer, st: AbilityState?, def: Definition?, via: String, reason: Reason, detail: String, notReady: Boolean = false,
        message: String? = null
    ): Outcome.Refused {
        st?.let { it.refusals++ }
        refusalCounts[reason] = (refusalCounts[reason] ?: 0) + 1
        if (notReady) send(player, MessageGame(MESSAGE_TYPE_NOT_READY, MESSAGE_NOT_READY))
        else if (message != null) send(player, MessageGame(MESSAGE_TYPE_NOT_READY, message))
        logger.info {
            "abilities: ${player.name} rejected ${def?.let { "${it.name} (struct ${it.structId})" } ?: "a slot"} via $via - $reason: $detail"
        }
        return Outcome.Refused(reason, detail)
    }

    fun levelStat(style: Style): Stat = when (style) {
        Style.MELEE -> Stat.ATTACK
        Style.RANGED -> Stat.RANGED
        Style.MAGIC -> Stat.MAGIC
        Style.NECROMANCY -> Stat.NECROMANCY
        Style.DEFENCE -> Stat.DEFENCE
        Style.CONSTITUTION -> Stat.CONSTITUTION
    }

    fun weaponStyle(weapon: EquippedWeapon?): Style? {
        if (weapon == null) return Style.MELEE
        if (PlayerCombat.isNecromancyWeapon(weapon)) return Style.NECROMANCY
        return when (weapon.style) {
            CombatStyle.MELEE -> Style.MELEE
            CombatStyle.RANGED -> Style.RANGED
            CombatStyle.MAGIC -> Style.MAGIC
            null -> null
        }
    }

    fun isTwoHanded(weapon: EquippedWeapon?): Boolean = weapon?.definition?.equipId == TWO_HANDED_EQUIP_ID

    fun unmetRequirement(player: WorldPlayer, def: Definition): String? {
        if (def.requires.isEmpty()) return null
        val main = player.wornWeapon
        val twoHanded = isTwoHanded(main)
        val offId = player.worn[OFFHAND_SLOT]?.id
        val off = offId?.let { PlayerCombatStats.lookupWeapon(it) }
        for (tag in def.requires) {
            when (tag) {
                "2h" -> if (!twoHanded) return "needs a two-handed weapon; wielding ${main?.definition?.name ?: "nothing"}"
                "mainhand" -> if (main == null || twoHanded) return "needs a one-handed main-hand weapon"
                "dual" -> if (main == null || twoHanded || off == null || weaponStyle(off) != weaponStyle(main)) return "needs a main-hand and an off-hand weapon of the same style"
                "shield" -> if (off == null || off.style != null || off.requirementSkill == Stat.NECROMANCY.id) return "needs a shield in the off-hand slot"
            }
        }
        return null
    }

    fun abilityDamage(player: WorldPlayer, style: Style): Double? = abilityDamageOrWhy(player, style).first

    private fun abilityDamageOrWhy(player: WorldPlayer, style: Style): Pair<Double?, String?> {
        val stats = player.stats
        val weapon = player.wornWeapon
        return when (style) {
            Style.NECROMANCY -> {
                val w = weapon ?: return null to "no necromancy weapon"
                val tier = w.params[PARAM_ITEM_TIER] ?: return null to "weapon ${w.definition.id} carries no tier param $PARAM_ITEM_TIER"
                val x10 = PlayerCombatStats.ABILITY_DAMAGE_PER_LEVEL_X10 * stats.getLevel(Stat.NECROMANCY).coerceAtLeast(1) +
                    NECROMANCY_WEAPON_X10_PER_TIER * tier
                x10 / 10.0 to null
            }
            Style.MELEE, Style.RANGED, Style.MAGIC -> {
                val derived = PlayerCombatStats.derive(
                    stats.getLevel(Stat.ATTACK), stats.getLevel(Stat.STRENGTH), stats.getLevel(Stat.MAGIC),
                    stats.getLevel(Stat.RANGED), stats.getLevel(Stat.DEFENCE), weapon,
                    loadout = PlayerCombatStats.loadoutOf(player)
                )
                val seam = abilityMaxHitX10Override
                val seamed = if (style == Style.MELEE || seam == null) derived.effectiveMaxHitX10
                    else seam(player, style, derived.effectiveMaxHitX10)
                val x10 = seamed ?: return null to derived.gaps.joinToString("; ").ifEmpty { "no max hit" }
                x10 / 10.0 to null
            }
            Style.DEFENCE, Style.CONSTITUTION ->
                if (!StatusEffects.enabled) null to "${style.book} abilities deal no damage here"
                else weaponStyle(weapon)?.let { abilityDamageOrWhy(player, it) } ?: (null to "${style.book} ability with a weapon of no style")
        }
    }

    private fun combatStyleOf(style: Style): CombatStyle? = when (style) {
        Style.MELEE -> CombatStyle.MELEE
        Style.RANGED -> CombatStyle.RANGED
        Style.MAGIC -> CombatStyle.MAGIC
        else -> null
    }

    private val damageListener = WorldNpcs.NpcDamageListener { npc, before, after, attacker ->
        if (attacker != null) states.keys.firstOrNull { it.name == attacker }?.let { CombatVitals.onDamageDealt(it) }
        if (before > 0 && after <= 0) {
            cancelHitsOn(npc, "target died" + (attacker?.let { " (killing blow by $it)" } ?: ""))
            StatusEffects.clearNpc(npc, "died")
        }
    }

    private val swingHook: (WorldPlayer, WorldNpc) -> Unit = { player, npc -> onAutoSwing(player, npc) }

    fun install() {
        PlayerCombat.onPlayerSwing = swingHook
    }

    private fun onAutoSwing(player: WorldPlayer, npc: WorldNpc) {
        if (!enabled) return
        val st = stateFor(player)
        val now = clock
        val gcd = AbilityDefinitions.gcdTicks
        st.lastCombatTick = now
        if (st.onGcd(now)) {
            autoSwingsOnGcd++
            logger.debug { "abilities: ${player.name} auto-attack swing at tick $now during GCD (to ${st.gcdEnd})" }
            return
        }
        val before = st.adrenaline.tenths
        val gained = st.adrenaline.add(scaledGain(player, AbilityDefinitions.AUTO_ADRENALINE_GAIN_TENTHS))
        if (st.gcdEnd < now + gcd) {
            st.gcdStart = now
            st.gcdEnd = now + gcd
        }
        autoAdrenalineAwards++
        if (gained > 0) {
            varp(player, VARP_ADRENALINE_PREVIOUS, before)
            varp(player, VARP_ADRENALINE, st.adrenaline.tenths)
        }
        varp(player, VARP_GCD_START, st.gcdStart.toInt())
        varp(player, VARP_GCD_END, st.gcdEnd.toInt())
        send(player, RunClientScript(SCRIPT_COOLDOWN_OVERLAY, arrayOf<Any>(AbilityDefinitions.GCD_STRUCT, st.gcdStart.toInt(), st.gcdEnd.toInt(), 1, 1)))
        send(player, RunClientScript(SCRIPT_GCD_OVERLAY, arrayOf<Any>(AbilityDefinitions.GCD_STRUCT)))
        logger.debug {
            "abilities: ${player.name} auto-attack swing on ${npc.name ?: "npc"} ${npc.gameId} at tick $now: " +
                "adrenaline $before -> ${st.adrenaline.tenths}, GCD to ${st.gcdEnd}"
        }
    }

    fun attach(npcs: WorldNpcs) {
        if (listenedTo === npcs) return
        listenedTo?.removeDamageListener(damageListener)
        npcs.addDamageListener(damageListener)
        listenedTo = npcs
    }

    fun tick(npcs: WorldNpcs, groundItems: GroundItems?, players: List<WorldPlayer>) {
        try {
            tickPhase(npcs, groundItems, players)
        } finally {
            clock++
        }
    }

    private fun tickPhase(npcs: WorldNpcs, groundItems: GroundItems?, players: List<WorldPlayer>) {
        if (!enabled) {
            if (states.isNotEmpty()) {
                val dropped = states.values.sumOf { it.pending.size }
                hitsCancelled += dropped
                states.clear()
                logger.warn { "abilities: disabled (-D$FLAG=false); cleared all state and $dropped queued hit(s)" }
            }
            return
        }
        attach(npcs)
        runCatching { AbilityQueue.tick(states.entries.map { it.key to it.value }, clock) }.onFailure { logger.error(it) { "abilities: queue tick failed" } }
        preTick?.let { hook -> runCatching { hook(players) }.onFailure { logger.error(it) { "abilities: preTick hook failed" } } }

        if (states.isNotEmpty()) {
            val live = Collections.newSetFromMap(IdentityHashMap<WorldPlayer, Boolean>())
            live.addAll(players)
            for (gone in states.keys.filter { it !in live }) cull(gone, "no longer in the world")
        }

        for ((player, st) in states.entries.map { it.key to it.value }) {
            st.channel?.let { ch ->
                if (clock >= ch.endTick) {
                    st.channel = null
                    channelsCompleted++
                    logger.info { "abilities: ${player.name}'s ${ch.name} channel [#${ch.activationId}] completed at tick $clock" }
                } else {
                    val m = player.entity.movement
                    val loc = player.entity.location
                    val why = when {
                        !ch.target.alive -> "the target died"
                        m.currentSpeed == com.opennxt.model.entity.movement.MovementSpeed.WALK || m.currentSpeed == com.opennxt.model.entity.movement.MovementSpeed.RUN ->
                            "the player stepped (${ch.startX},${ch.startY}) -> (${loc.x},${loc.y})"
                        ActionLock.reasonFor(player.contentPlayer) != null -> "the player is locked: ${ActionLock.reasonFor(player.contentPlayer)}"
                        else -> null
                    }
                    if (why != null) cancelChannel(player, st, ch, why)
                }
            }
            while (true) {
                val hit = st.pending.firstOrNull() ?: break
                if (hit.dueTick > clock) break
                st.pending.removeFirst()
                try {
                    applyHit(player, hit, npcs, groundItems, players)
                } catch (t: Throwable) {
                    containedHitFailures++
                    logger.error(t) { "abilities: ${player.name}'s ${hit.name} hit failed" }
                }
            }
        }

        try {
            StatusEffects.tick(npcs, players)
        } catch (t: Throwable) {
            containedHitFailures++
            logger.error(t) { "abilities: status effects tick failed" }
        }
        try {
            CombatVitals.tick(players) { p, id, v -> varp(p, id, v) }
        } catch (t: Throwable) {
            containedHitFailures++
            logger.error(t) { "abilities: vitals tick failed" }
        }

        if (clock % RETAIN_SWEEP_TICKS == 0L && retained.isNotEmpty()) {
            retained.values.removeIf { r -> r.gcdEnd <= clock && r.cooldowns.values.all { it <= clock } }
        }
    }

    private fun applyHit(player: WorldPlayer, hit: PendingHit, npcs: WorldNpcs, groundItems: GroundItems?, players: List<WorldPlayer>) {
        val npc = hit.target
        if (!npc.alive) return cancel(player, hit, "target is dead")
        val movement = player.entity.movement
        val pendingTeleport = movement.teleportLocation
        val dashPending = hit.dashTo != null && pendingTeleport != null && pendingTeleport.x == hit.dashTo.x && pendingTeleport.y == hit.dashTo.y && pendingTeleport.plane == hit.dashTo.plane
        if (hit.kind == AbilityDefinitions.HitKind.HIT) {
            if (hit.primary) {
                val current = PlayerCombat.targetOf(player)
                if (current !== npc) return cancel(player, hit, "the player's target changed (now ${current?.gameId ?: "none"})")
            }
            ActionLock.reasonFor(player.contentPlayer)?.let { return cancel(player, hit, "the player is locked: $it") }
            if (pendingTeleport != null && !dashPending) return cancel(player, hit, "a teleport to (${pendingTeleport.x}, ${pendingTeleport.y}, ${pendingTeleport.plane}) is pending")
            val worn = weaponStyle(player.wornWeapon)
            if (worn != hit.style) return cancel(player, hit, "the worn weapon now fights in ${worn?.book ?: "no style"}, the hit is ${hit.style.book}")
            AbilityDefinitions.forStruct(hit.structId)?.let { d -> unmetRequirement(player, d)?.let { return cancel(player, hit, "requirement no longer met: $it") } }
        }
        val loc = if (dashPending) hit.dashTo!! else player.entity.location
        if (npc.location.plane != loc.plane) return cancel(player, hit, "target on plane ${npc.location.plane}, player on ${loc.plane}")
        if (hit.primary) {
            val distance = PlayerCombat.footprintDistance(npc, loc.x, loc.y)
            val reach = minOf(PlayerCombat.playerAttackRange(player), hit.reachAtCommit ?: Int.MAX_VALUE)
            if (distance > reach) return cancel(player, hit, "target out of reach ($distance > $reach)")
        }
        val (damage, why) = hit.damageAtCommit?.let { it to null } ?: abilityDamageOrWhy(player, hit.style)
        if (damage == null) return cancel(player, hit, "ability damage not computable: $why")
        val before = npc.currentLifepoints ?: return cancel(player, hit, "target has no lifepoints")

        val percent = if (hit.maxPct <= hit.minPct) hit.minPct else hit.minPct + random.nextDouble() * (hit.maxPct - hit.minPct)
        var requested = hit.fixedDamage ?: Math.floor(damage * percent * hit.bandScale / 100.0).toInt().coerceIn(0, SPLAT_AMOUNT_MAX)
        val effectsOn = StatusEffects.enabled
        if (effectsOn) {
            hit.onLand.filterIsInstance<Effect.Execute>().firstOrNull()?.let { ex ->
                val max = npc.lifepoints?.value ?: 0
                if (max > 0 && before < max * ex.belowFraction) { requested = Math.floor(requested * ex.multiplier).toInt(); executeMultiplied++ }
            }
            requested = StatusEffects.scaleOutgoing(player, hit.style, requested)
        }
        var chance: Double? = null
        var missed = false
        val mode = accuracyMode
        if (mode != "off") {
            val (c, gap) = hitChanceFor(player, npc, hit.style)
            if (c == null) {
                if (gap != null && loggedChanceGaps.add(gap)) logger.warn { "abilities: no hit chance for ${player.name}'s ${hit.name} on npc ${npc.gameId}, damage unscaled: $gap" }
            } else {
                chance = c
                if (mode == "miss") {
                    if (random.nextDouble() >= c) { missed = true; accuracyMisses++; requested = 0 }
                } else if (c < 1.0) {
                    requested = Math.floor(requested * c).toInt(); accuracyScaled++
                }
            }
        }
        requested = requested.coerceIn(0, SPLAT_AMOUNT_MAX)
        val death: DeathResult? = if (missed) null else
            npcs.applyDamage(npc, requested, random, groundItems, player.name, combatStyleOf(hit.style), necromancy = hit.style == Style.NECROMANCY)
        val applied = if (missed) 0 else before - (npc.currentLifepoints ?: 0)
        hitsApplied++
        if (applied > 0) {
            splat(npc, applied, hit.splatDelay)
            runCatching { TargetHud.update(player, npc) }
        }
        recentHits.addLast(AppliedHit(clock, player.name, hit.structId, npc, requested, applied, percent, hit.fallback, death != null, hit.kind, hit.primary, chance, hit.activationId))
        while (recentHits.size > RECENT_HITS_KEPT) recentHits.removeFirst()
        onAbilityHitLanded?.let { hook -> try { hook(player, hit, npc, applied) } catch (t: Throwable) { seamFailures++; logger.error(t) { "abilities: onAbilityHitLanded hook failed for ${player.name}" } } }

        if (effectsOn && hit.onLand.isNotEmpty()) applyTargetEffects(player, hit, npc, applied, npcs, death != null)

        if (death != null) {
            kills++
            cancelHitsOn(npc, "target died")
            awardKill(death, npc, players)
            if (PlayerCombat.targetOf(player) === npc) PlayerCombat.disengage(player)
            logger.info { "abilities: ${player.name}'s ${hit.name} killed ${npc.name ?: "npc"} ${npc.gameId} at tick $clock ($applied damage)" }
        }
    }

    fun hitChanceFor(player: WorldPlayer, npc: WorldNpc, style: Style): Pair<Double?, String?> {
        val stats = player.stats
        val derived = PlayerCombatStats.derive(
            stats.getLevel(Stat.ATTACK), stats.getLevel(Stat.STRENGTH), stats.getLevel(Stat.MAGIC),
            stats.getLevel(Stat.RANGED), stats.getLevel(Stat.DEFENCE), player.wornWeapon
        )
        val accuracy = derived.effectiveAccuracy ?: return null to "no derivable accuracy: ${derived.gaps.joinToString("; ").ifEmpty { "no accuracy term" }}"
        val combat = npc.combat ?: return null to "no combat definition for npc ${npc.gameId}"
        val armour = combat.armour ?: return null to "npc ${npc.gameId} carries no armour param"
        val affinity = when (style) {
            Style.NECROMANCY -> NECROMANCY_AFFINITY
            else -> combatStyleOf(style)?.let { combat.affinityFor(it) } ?: return null to "npc ${npc.gameId} has no usable ${style.book} affinity"
        }
        return CombatFormulas.hitChance(accuracy, armour, affinity) to null
    }

    private fun applyTargetEffects(player: WorldPlayer, hit: PendingHit, npc: WorldNpc, applied: Int, npcs: WorldNpcs, killed: Boolean) {
        val st = stateFor(player)
        val def = AbilityDefinitions.forStruct(hit.structId)
        val source = "${player.name}'s ${hit.name}"
        for (e in hit.onLand) {
            when (e) {
                is Effect.Stun -> if (!killed && StatusEffects.stun(npc, e.ticks, source) != null) targetEffectsApplied++
                is Effect.Bind -> if (!killed && StatusEffects.bind(npc, e.ticks, source) != null) targetEffectsApplied++
                is Effect.Debuff -> if (!killed && StatusEffects.debuff(npc, e.name, e.ticks, source) != null) targetEffectsApplied++
                is Effect.Heal -> {
                    val amount = Math.floor(applied * e.percent / 100.0).toInt()
                    if (amount > 0 && CombatVitals.heal(player, amount, "$source heal:${e.percent}% of $applied") > 0) { healsFromHits++; targetEffectsApplied++ }
                }
                is Effect.Aoe -> if (def != null) {
                    val splash = hit.onLand.filterIsInstance<Effect.Splash>().firstOrNull()
                    val ricochet = hit.onLand.any { it is Effect.RicochetMissing }
                    when {
                        splash != null -> queueSecondaries(player, st, hit, npc, npcs, e.radius, splash.max.coerceAtMost(StatusEffects.AOE_MAX_SECONDARIES),
                            listOf(HitSpec(0, splash.minPct, splash.maxPct)), fromActivation = false)
                        ricochet -> {
                            val extra = def.hits.drop(1)
                            val secondaries = secondaryTargets(npcs, npc, e.radius, extra.size.coerceAtMost(StatusEffects.AOE_MAX_SECONDARIES))
                            for ((i, s) in secondaries.withIndex()) queueOne(player, st, hit, s, listOf(extra[i]), fromActivation = true)
                            var drop = secondaries.size
                            st.pending.removeAll { p -> drop > 0 && p.activationId == hit.activationId && p.target === npc && p.kind == AbilityDefinitions.HitKind.HIT && (drop-- > 0) }
                        }
                        else -> queueSecondaries(player, st, hit, npc, npcs, e.radius, StatusEffects.AOE_MAX_SECONDARIES, def.hits, fromActivation = true)
                    }
                }
                is Effect.Bounces -> {
                    val other = secondaryTargets(npcs, npc, e.radius, 1).firstOrNull()
                    for (k in 1..e.count) {
                        val target = if (other != null && k % 2 == 1) other else npc
                        if (st.pending.size >= MAX_PENDING_HITS) { secondariesDropped++; break }
                        st.enqueue(PendingHit(++sequence, hit.structId, hit.name, clock + k * BOUNCE_EVERY_TICKS, hit.minPct, hit.maxPct, target, hit.style, 0, hit.activatedAt, hit.fallback,
                            AbilityDefinitions.HitKind.HIT, damageAtCommit = hit.damageAtCommit, reachAtCommit = hit.reachAtCommit, activationId = hit.activationId, primary = target === npc))
                        bouncesQueued++
                    }
                    logger.info { "abilities: $source bounces ${e.count}x every $BOUNCE_EVERY_TICKS ticks between ${other?.let { "npc ${it.gameId} [slot ${it.infoIndex}]" } ?: "the primary only"} and the primary" }
                }
                else -> {}
            }
        }
    }

    fun secondaryTargets(npcs: WorldNpcs, primary: WorldNpc, radius: Int, max: Int): List<WorldNpc> {
        if (max <= 0 || radius < 0) return emptyList()
        val px = primary.location.x; val py = primary.location.y; val plane = primary.location.plane
        return npcs.all().asSequence()
            .filter { it !== primary && it.alive && !it.despawned && it.lifepoints != null && it.infoIndex >= 0 && it.location.plane == plane }
            .map { it to maxOf(Math.abs(it.location.x - px), Math.abs(it.location.y - py)) }
            .filter { it.second <= radius }
            .sortedWith(compareBy({ it.second }, { it.first.infoIndex }))
            .take(max)
            .map { it.first }
            .toList()
    }

    private fun queueSecondaries(player: WorldPlayer, st: AbilityState, hit: PendingHit, primary: WorldNpc, npcs: WorldNpcs, radius: Int, max: Int, schedule: List<HitSpec>, fromActivation: Boolean) {
        val secondaries = secondaryTargets(npcs, primary, radius, max)
        for (s in secondaries) queueOne(player, st, hit, s, schedule, fromActivation)
        logger.info { "abilities: ${player.name}'s ${hit.name} area radius $radius around npc ${primary.gameId}: ${secondaries.size} secondary target(s) ${secondaries.map { "${it.gameId}[${it.infoIndex}]" }}" }
    }

    private fun queueOne(player: WorldPlayer, st: AbilityState, hit: PendingHit, target: WorldNpc, schedule: List<HitSpec>, fromActivation: Boolean) {
        for (h in schedule) {
            if (st.pending.size >= MAX_PENDING_HITS) { secondariesDropped++; logger.warn { "abilities: ${player.name}'s ${hit.name} secondary hit on npc ${target.gameId} dropped, queue full ($MAX_PENDING_HITS)" }; return }
            val due = maxOf(clock, if (fromActivation) hit.activatedAt + h.delayTicks else clock + h.delayTicks)
            st.enqueue(PendingHit(++sequence, hit.structId, hit.name, due, h.minPct, h.maxPct, target, hit.style, 0, hit.activatedAt, hit.fallback, h.kind,
                damageAtCommit = hit.damageAtCommit, reachAtCommit = null, activationId = hit.activationId, primary = false, bandScale = hit.bandScale))
            secondariesQueued++
        }
    }

    private fun cancel(player: WorldPlayer, hit: PendingHit, why: String) {
        hitsCancelled++
        logger.info { "abilities: ${player.name}'s ${hit.name} hit due at ${hit.dueTick} cancelled: $why" }
    }

    fun hasQueuedHitOn(npc: WorldNpc): Boolean {
        for (st in states.values) {
            st.channel?.let { ch -> if (ch.target === npc && clock < ch.endTick) return true }
            if (st.pending.any { it.target === npc }) return true
        }
        return false
    }

    fun cancelHitsOn(npc: WorldNpc, why: String): Int {
        var total = 0
        for (st in states.values) {
            st.channel?.let { ch -> if (ch.target === npc && clock < ch.endTick) { st.channel = null; channelsCancelled++; logger.info { "abilities: ${st.name}'s ${ch.name} channel [#${ch.activationId}] cancelled at tick $clock: $why" } } }
            val n = st.pending.count { it.target === npc }
            if (n == 0) continue
            st.pending.removeAll { it.target === npc }
            total += n
            logger.info { "abilities: ${st.name}'s $n queued hit(s) on ${npc.name ?: "npc"} ${npc.gameId} cancelled: $why" }
        }
        hitsCancelled += total
        return total
    }

    private fun awardKill(death: DeathResult, npc: WorldNpc, players: List<WorldPlayer>) {
        for (name in death.damageLedger.keys) {
            val p = players.firstOrNull { it.name == name } ?: continue
            try {
                for ((stat, xp) in CombatXp.killAward(death, name, npc.gameId)) p.stats.addExperience(stat, xp)
            } catch (t: Throwable) {
                logger.error(t) { "abilities: kill xp award to $name failed" }
            }
        }
    }

    private fun splat(npc: WorldNpc, amount: Int, delay: Int) {
        val type = PlayerCombat.splatType ?: return
        val updates = npc.pendingUpdates
        val existing = if (updates.offered) null else updates.hits
        val hits = existing?.hits.orEmpty()
        if (hits.size >= MAX_SPLATS_PER_BLOCK) return
        runCatching {
            updates.hit(NpcHitsBlock(hits + NpcHitsBlock.Hit(type, amount.coerceAtMost(SPLAT_AMOUNT_MAX), delay), existing?.bars.orEmpty()))
        }.onFailure { logger.warn(it) { "abilities: splat of $amount on npc ${npc.gameId} not queued" } }
    }

    fun cull(player: WorldPlayer, why: String): Int {
        val st = states.remove(player) ?: return 0
        val dropped = st.pending.size
        st.pending.clear()
        hitsCancelled += dropped
        st.channel?.let { if (clock < it.endTick) channelsCancelled++ }
        st.channel = null
        StatusEffects.clearPlayer(player, why)
        val live = st.cooldownEnd.filterValues { it > clock }
        if (live.isNotEmpty() || st.gcdEnd > clock) retained[player.name.lowercase()] = Retained(st.gcdEnd, HashMap(live))
        logger.info { "abilities: ${player.name} left ($why); dropped $dropped hit(s), kept ${live.size} cooldown(s)" }
        return dropped
    }

    private fun send(player: WorldPlayer, packet: GamePacket) {
        observer?.invoke(player, packet)
        runCatching { player.client.write(packet) }.onFailure { logger.warn { "abilities: could not write $packet to ${player.name}: ${it.message}" } }
    }

    internal fun sendPacket(player: WorldPlayer, packet: GamePacket) = send(player, packet)

    internal fun dashWire(player: WorldPlayer, def: Definition, now: Long, cooldownEnd: Long) {
        activations++
        if (def.cooldownTicks > 0) send(player, RunClientScript(SCRIPT_COOLDOWN_OVERLAY, arrayOf<Any>(def.structId, now.toInt(), cooldownEnd.toInt(), 1, 1)))
        send(player, ClientSetvarcLarge(VARC_ACTIVATED, def.structId))
    }

    internal fun varpFor(player: WorldPlayer, id: Int, value: Int) = varp(player, id, value)

    private fun varp(player: WorldPlayer, id: Int, value: Int) {
        observer?.invoke(player, VarpLarge(id, value))
        runCatching { player.setVarpOverride(id, value, store = false) }.onFailure { logger.warn { "abilities: varp $id = $value not sent to ${player.name}: ${it.message}" } }
    }
}

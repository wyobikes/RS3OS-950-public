package com.opennxt.content.ability

import com.opennxt.model.world.TileLocation
import com.opennxt.model.world.WorldNpc

class Adrenaline(initialTenths: Int = 0) {
    companion object {
        const val MAX_TENTHS = 1000
        const val MIN_TENTHS = 0
    }

    var tenths: Int = initialTenths.coerceIn(MIN_TENTHS, MAX_TENTHS)
        private set

    fun add(delta: Int): Int {
        if (delta <= 0) return 0
        val before = tenths
        tenths = (tenths + delta).coerceAtMost(MAX_TENTHS)
        return tenths - before
    }

    fun trySpend(cost: Int): Boolean {
        if (cost < 0 || tenths < cost) return false
        tenths -= cost
        return true
    }

    fun drain(amount: Int): Int {
        if (amount <= 0) return 0
        val before = tenths
        tenths = (tenths - amount).coerceAtLeast(MIN_TENTHS)
        return before - tenths
    }
}

data class PendingHit(
    val sequence: Long,
    val structId: Int,
    val name: String,
    val dueTick: Long,
    val minPct: Double,
    val maxPct: Double,
    val target: WorldNpc,
    val style: AbilityDefinitions.Style,
    val splatDelay: Int,
    val activatedAt: Long,
    val fallback: Boolean,
    val kind: AbilityDefinitions.HitKind,
    val damageAtCommit: Double? = null,
    val reachAtCommit: Int? = null,
    val activationId: Long = 0,
    val primary: Boolean = true,
    val onLand: List<AbilityDefinitions.Effect> = emptyList(),
    val dashTo: TileLocation? = null,
    val fixedDamage: Int? = null,
    val bandScale: Double = 1.0
)

data class Channel(
    val activationId: Long,
    val structId: Int,
    val name: String,
    val startTick: Long,
    val endTick: Long,
    val startX: Int,
    val startY: Int,
    val startPlane: Int,
    val target: WorldNpc
)

class AbilityState(val name: String) {
    val adrenaline = Adrenaline()
    var gcdStart: Long = Long.MIN_VALUE
    var gcdEnd: Long = Long.MIN_VALUE
    val cooldownEnd = HashMap<Int, Long>()

    val pending = ArrayDeque<PendingHit>()

    var activations = 0
    var refusals = 0

    var channel: Channel? = null

    var lastCombatTick: Long = Long.MIN_VALUE

    var lastDamageTick: Long = Long.MIN_VALUE

    var slowRegenTicks: Int = 0

    var necrosis: Int = 0
    var residualSouls: Int = 0
    var stormShards: Int = 0
    var stormShardDamage: Int = 0

    var queued: AbilityQueue.Queued? = null

    fun onGcd(now: Long): Boolean = now < gcdEnd
    fun cooldownRemaining(structId: Int, now: Long): Long {
        val end = cooldownEnd[structId] ?: return 0
        return (end - now).coerceAtLeast(0)
    }

    fun enqueue(hit: PendingHit) {
        val at = pending.indexOfFirst { it.dueTick > hit.dueTick }
        if (at < 0) pending.addLast(hit) else pending.add(at, hit)
    }
}

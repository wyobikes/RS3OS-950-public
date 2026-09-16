package com.opennxt.model.world

import com.opennxt.model.combat.NpcCombat
import com.opennxt.model.combat.NpcCombatDefinition
import com.opennxt.model.combat.SeedData
import com.opennxt.model.combat.SeededValue
import com.opennxt.model.entity.LivingEntity
import com.opennxt.model.entity.movement.Movement
import com.opennxt.model.entity.movement.MovementSpeed
import com.opennxt.model.entity.rendering.EntityRenderer
import com.opennxt.model.entity.rendering.npc.NpcUpdates

class WorldNpc(val spawn: NpcSpawnData.Spawn) :
    LivingEntity(TileLocation(spawn.x, spawn.y, spawn.plane)) {
    override val renderer = EntityRenderer(this)

    override val movement = Movement(this).also { it.speed = MovementSpeed.WALK }

    val pendingUpdates = NpcUpdates()

    val gameId: Int = spawn.npcId

    val effectiveId: Int = NpcMorph.effectiveId(gameId).also { if (it != gameId) NpcMorph.countStubSpawn() }

    var infoIndex: Int = -1
        internal set

    val lifepoints: SeededValue? = SeedData.lifepoints(gameId) ?: SeedData.lifepoints(effectiveId)

    val combat: NpcCombatDefinition? = NpcMorph.combat(gameId)

    var currentLifepoints: Int? = lifepoints?.value
        private set

    val name: String? = spawn.name ?: if (effectiveId != gameId) combat?.name else null

    var deathAnimation: com.opennxt.model.combat.NpcAnimTable.DeathAnimation? = null

    var alive: Boolean = true
        private set

    var respawnTicksRemaining: Int = -1
        private set

    var respawnTotal: Int = RESPAWN_TICKS

    val damageBy: LinkedHashMap<String, Int> = LinkedHashMap()

    val styleBy: LinkedHashMap<String, com.opennxt.model.combat.CombatStyle> = LinkedHashMap()

    val necromancyBy: LinkedHashSet<String> = LinkedHashSet()

    internal fun recordDamage(
        attacker: String,
        amount: Int,
        style: com.opennxt.model.combat.CombatStyle? = null,
        necromancy: Boolean = false
    ) {
        if (amount <= 0) return
        damageBy[attacker] = (damageBy[attacker] ?: 0) + amount
        if (necromancy) {
            necromancyBy.add(attacker)
            styleBy.remove(attacker)
        } else if (style != null) {
            styleBy[attacker] = style
            necromancyBy.remove(attacker)
        }
    }

    fun topDamageDealer(): String? {
        var best: String? = null; var bestAmount = 0
        for ((name, amount) in damageBy) if (amount > bestAmount) { best = name; bestAmount = amount }
        return best
    }

    fun damageShare(attacker: String): Double {
        val total = damageBy.values.sum()
        return if (total <= 0) 0.0 else (damageBy[attacker] ?: 0).toDouble() / total
    }

    var despawned: Boolean = false
        private set

    internal fun markDespawned() {
        despawned = true
        alive = false
        respawnTicksRemaining = -1
        damageBy.clear(); styleBy.clear(); necromancyBy.clear()
    }

    val spawnTile: TileLocation get() = TileLocation(spawn.x, spawn.y, spawn.plane)

    internal fun damage(amount: Int): Int {
        val lp = currentLifepoints ?: throw IllegalStateException(
            "npc $gameId (${name ?: "unnamed"}) has no lifepoints defined and cannot take damage"
        )
        check(alive) { "npc $gameId (${name ?: "unnamed"}) is already dead" }
        if (amount <= 0) return 0
        val applied = minOf(amount, lp)
        currentLifepoints = lp - applied
        return applied
    }

    fun heal(amount: Int): Int {
        val lp = currentLifepoints ?: return 0
        val max = lifepoints?.value ?: return 0
        if (!alive || amount <= 0) return 0
        val restored = minOf(amount, max - lp)
        currentLifepoints = lp + restored
        if (restored > 0) queueLifepointsBlock()
        return restored
    }

    fun queueLifepointsBlock() {
        val current = currentLifepoints ?: return
        val maximum = lifepoints?.value ?: return
        if (maximum <= 0) return
        pendingUpdates.lifepoints(current.coerceIn(0, maximum), maximum)
    }

    internal fun die(respawnTicks: Int) {
        alive = false
        respawnTicksRemaining = respawnTicks
        respawnTotal = respawnTicks
        movement.reset()
    }

    internal fun tickRespawn(): Boolean {
        if (despawned) return false
        if (alive) return false
        if (respawnTicksRemaining > 0) {
            respawnTicksRemaining--
            if (respawnTicksRemaining > 0) return false
        }
        location = spawnTile
        previousLocation = location
        movement.reset()
        currentLifepoints = lifepoints?.value
        alive = true
        respawnTicksRemaining = -1
        damageBy.clear(); styleBy.clear(); necromancyBy.clear()
        return true
    }

    override fun clean() {
    }

    override fun toString(): String =
        "WorldNpc(${name ?: "npc$gameId"}($gameId) @ (${location.x},${location.y},${location.plane}) " +
            "lp=${currentLifepoints?.toString() ?: "null"}/${lifepoints?.toString() ?: "null"} alive=$alive)"

    companion object {
        const val RESPAWN_TICKS = 50
    }
}

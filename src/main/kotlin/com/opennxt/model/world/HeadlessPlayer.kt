package com.opennxt.model.world

import com.opennxt.api.stat.Stat
import com.opennxt.api.stat.levelForXp
import com.opennxt.content.ContentPlayer
import com.opennxt.content.ContentRegistry
import com.opennxt.content.DispatchResult
import com.opennxt.model.combat.CombatFormulas
import com.opennxt.model.combat.CombatStyle
import com.opennxt.model.combat.Lifepoints
import com.opennxt.model.entity.PlayerEntity
import com.opennxt.model.entity.movement.Movement
import com.opennxt.model.entity.movement.MovementSpeed
import com.opennxt.model.map.PathFinder
import java.util.EnumMap
import java.util.Random

class HeadlessPlayer private constructor(
    val name: String,
    start: TileLocation,
    private val explicitAccuracy: Int?,
    private val explicitMaxHit: Int?
) : CombatDefender {
    constructor(name: String, start: TileLocation, accuracy: Int, maxHit: Int) :
        this(name, start, explicitAccuracy = accuracy, explicitMaxHit = maxHit)

    constructor(name: String, start: TileLocation) :
        this(name, start, explicitAccuracy = null, explicitMaxHit = null)

    val derivesStats: Boolean get() = explicitAccuracy == null

    val skills: MutableMap<Stat, Int> = EnumMap(Stat::class.java)

    override fun level(stat: Stat): Int = skills[stat] ?: 1

    fun setLevel(stat: Stat, level: Int) {
        require(level >= 1) { "level must be at least 1; got $level" }
        skills[stat] = level
    }

    val xpTenths: MutableMap<Stat, Int> = EnumMap(Stat::class.java)

    fun xp(stat: Stat): Int = (xpTenths[stat] ?: 0) / 10

    val levelUps = ArrayList<LevelUp>()

    val deaths = ArrayList<PlayerDeath>()

    var lastXpAward: CombatXpAward? = null
        private set

    private val xpExactTenths: MutableMap<Stat, Double> = EnumMap(Stat::class.java)

    fun grantKillXp(death: DeathResult, npcId: Int?): Map<Stat, Int> {
        val damage = death.damageLedger[name] ?: return emptyMap()
        val style = death.styleLedger[name] ?: CombatStyle.MELEE
        val out = LinkedHashMap<Stat, Int>()
        for ((stat, xp) in com.opennxt.model.combat.CombatXp.splitAward(npcId, damage, style)) {
            val before = xpTenths[stat] ?: 0
            val exact = (xpExactTenths[stat] ?: before.toDouble()) + xp * 10.0
            xpExactTenths[stat] = exact
            val after = Math.floor(exact + 1e-9).toInt()
            xpTenths[stat] = after
            out[stat] = after - before
            if (stat.loaded) {
                val old = level(stat)
                val fromXp = levelForXp(stat, xp(stat))
                if (fromXp > old) { skills[stat] = fromXp; levelUps += LevelUp(stat, old, fromXp) }
            }
        }
        lastKillXp = out
        return out
    }
    var lastKillXp: Map<Stat, Int> = emptyMap()
        private set

    fun grantCombatXp(style: CombatStyle, damage: Int, npcId: Int? = null): CombatXpAward {
        if (damage <= 0) {
            return CombatXpAward(style, 0, 0, 0, emptyList(), null).also { lastXpAward = it }
        }
        val styleStat = when (style) {
            CombatStyle.MELEE -> Stat.ATTACK
            CombatStyle.RANGED -> Stat.RANGED
            CombatStyle.MAGIC -> Stat.MAGIC
        }
        val styleXp = com.opennxt.model.combat.CombatXp.styleXp(npcId, damage)
        val constitutionXp = com.opennxt.model.combat.CombatXp.constitutionXp(styleXp)
        fun award(stat: Stat, xp: Double): Int {
            val before = xpTenths[stat] ?: 0
            val exact = (xpExactTenths[stat] ?: before.toDouble()) + xp * 10.0
            xpExactTenths[stat] = exact
            val after = Math.floor(exact + 1e-9).toInt()
            xpTenths[stat] = after
            return after - before
        }
        val styleTenths = award(styleStat, styleXp)
        val constitutionTenths = award(Stat.CONSTITUTION, constitutionXp)

        val ups = ArrayList<LevelUp>()
        var notComputed: String? = null
        for ((stat, tenths) in listOf(styleStat to styleTenths, Stat.CONSTITUTION to constitutionTenths)) {
            if (!stat.loaded) {
                notComputed = "no experience table for $stat; levels not recomputed"
                continue
            }
            val old = level(stat)
            val fromXp = levelForXp(stat, xp(stat))
            if (fromXp > old) {
                skills[stat] = fromXp
                val up = LevelUp(stat, old, fromXp)
                ups += up
                levelUps += up
            }
        }
        return CombatXpAward(style, damage, styleTenths, constitutionTenths, ups, notComputed)
            .also { lastXpAward = it }
    }

    override val maxLifepoints: Int get() = Lifepoints.forConstitutionLevel(level(Stat.CONSTITUTION))

    override var currentLifepoints: Int = Lifepoints.forConstitutionLevel(1)
        private set

    override fun takeDamage(amount: Int): PlayerDeath? {
        if (amount <= 0) return null
        currentLifepoints = (currentLifepoints - amount).coerceAtLeast(0)
        if (currentLifepoints > 0) return null

        val diedAt = TileLocation(location.x, location.y, location.plane)
        placeAt(LUMBRIDGE_RESPAWN_X, LUMBRIDGE_RESPAWN_Y, LUMBRIDGE_RESPAWN_PLANE)
        currentLifepoints = maxLifepoints
        val death = PlayerDeath(
            player = name,
            diedAt = diedAt,
            respawnedAt = TileLocation(LUMBRIDGE_RESPAWN_X, LUMBRIDGE_RESPAWN_Y, LUMBRIDGE_RESPAWN_PLANE),
            lifepointsRestored = currentLifepoints
        )
        deaths += death
        return death
    }

    fun restoreLifepoints() {
        currentLifepoints = maxLifepoints
    }

    companion object {
        @Deprecated("Use CombatXp.")
        const val COMBAT_XP_TENTHS_PER_DAMAGE = 4

        @Deprecated("Use CombatXp.CONSTITUTION_SHARE.")
        const val CONSTITUTION_XP_TENTHS_PER_100_DAMAGE = 133

        const val LUMBRIDGE_RESPAWN_X = 3222
        const val LUMBRIDGE_RESPAWN_Y = 3222
        const val LUMBRIDGE_RESPAWN_PLANE = 0
    }

    var weapon: EquippedWeapon? = null
        private set

    var unarmedReason: String = "nothing equipped"
        private set

    fun equip(itemId: Int): EquippedWeapon? {
        val looked = PlayerCombatStats.lookupWeapon(itemId)
        weapon = looked
        unarmedReason = if (looked == null)
            "item $itemId not found"
        else "${looked.definition.name ?: "item"} (${looked.definition.id}) equipped"
        return looked
    }

    fun derivedStats(): PlayerDerivedStats = PlayerCombatStats.derive(
        attackLevel = level(Stat.ATTACK),
        strengthLevel = level(Stat.STRENGTH),
        magicLevel = level(Stat.MAGIC),
        rangedLevel = level(Stat.RANGED),
        defenceLevel = level(Stat.DEFENCE),
        weapon = weapon
    )

    val accuracy: Int?
        get() = explicitAccuracy ?: derivedStats().effectiveAccuracy

    val maxHit: Int?
        get() = explicitMaxHit ?: derivedStats().effectiveMaxHitX10?.div(10)

    val entity = PlayerEntity(TileLocation(start.x, start.y, start.plane))

    val contentPlayer = ContentPlayer(name, TileLocation(start.x, start.y, start.plane))

    val location: TileLocation get() = entity.location
    val isMoving: Boolean get() = entity.movement.hasSteps

    init {
        entity.movement.speed = MovementSpeed.WALK
    }

    fun placeAt(x: Int, y: Int, plane: Int = 0) {
        entity.movement.reset()
        entity.location = TileLocation(x, y, plane)
        entity.previousLocation = entity.location
    }

    var lastPathTerminus: PathFinder.Step? = null
        private set

    fun pathTo(x: Int, z: Int, search: Int = PathFinder.SEARCH): Int {
        val loc = entity.location
        Movement.clipPathEnds(loc.x, loc.y, x, z, loc.plane)
        val path = PathFinder.find(loc.x, loc.y, x, z, loc.plane, search) ?: return -1
        lastPathTerminus = path.last()
        entity.movement.reset()
        var queued = 0
        for (step in path.drop(1)) {
            if (!entity.movement.addStep(step.x, step.z)) break
            queued++
        }
        return queued
    }

    fun tick() {
        entity.movement.process()
    }

    fun interactWithLoc(locId: Int, action: String, x: Int, z: Int, plane: Int = location.plane): DispatchResult {
        contentPlayer.location = TileLocation(entity.location.x, entity.location.y, entity.location.plane)
        val result = ContentRegistry.dispatchLoc(contentPlayer, locId, action, x, z, plane)
        entity.movement.reset()
        entity.location = TileLocation(
            contentPlayer.location.x, contentPlayer.location.y, contentPlayer.location.plane
        )
        entity.previousLocation = entity.location
        return result
    }

    fun attack(
        npc: WorldNpc,
        npcs: WorldNpcs,
        random: Random,
        groundItems: GroundItems? = null
    ): AttackOutcome {
        val accuracy = this.accuracy ?: return AttackOutcome.NotComputable(
            "player $name has no derivable accuracy: ${derivedStats().gaps.joinToString("; ")}"
        )
        val maxHit = this.maxHit ?: return AttackOutcome.NotComputable(
            "player $name has no derivable max hit: ${derivedStats().gaps.joinToString("; ")}"
        )
        if (!npc.alive) return AttackOutcome.NotComputable("target is dead (respawns in ${npc.respawnTicksRemaining} ticks)")
        if (npc.lifepoints == null) return AttackOutcome.NotComputable(
            "target ${npc.gameId} has no lifepoints"
        )
        val combat = npc.combat ?: return AttackOutcome.NotComputable(
            "no combat definition for game id ${npc.gameId}"
        )
        val armour = combat.armour ?: return AttackOutcome.NotComputable(
            "target ${npc.gameId} has no armour param (2865)"
        )
        val affinity = combat.affinityFor(CombatStyle.MELEE)
            ?: return AttackOutcome.NotComputable(
                "target ${npc.gameId} has no melee affinity"
            )

        val chance = CombatFormulas.hitChance(accuracy, armour, affinity)
        if (random.nextDouble() >= chance) return AttackOutcome.Missed(chance)

        val damage = CombatFormulas.damageRoll(maxHit, random, CombatFormulas.PLAYER_AUTO_ATTACK_FLOOR_PERCENT)
        val applied = minOf(damage, npc.currentLifepoints!!)
        val death = npcs.applyDamage(npc, damage, random, groundItems, owner = name, style = CombatStyle.MELEE)
        if (death != null) grantKillXp(death, npc.gameId)
        return if (death != null) AttackOutcome.Killed(damage, death)
        else AttackOutcome.Hit(damage, npc.currentLifepoints!!)
    }

    override fun toString() =
        "HeadlessPlayer($name at ${location.x},${location.y},${location.plane}, " +
            if (derivesStats)
                "accuracy=${accuracy ?: "null"} maxHit=${maxHit ?: "null"} " +
                    "weapon=${weapon?.definition?.name ?: "unarmed: $unarmedReason"})"
            else "accuracy=$accuracy maxHit=$maxHit, explicit)"
}

sealed class AttackOutcome {
    data class Missed(val hitChance: Double) : AttackOutcome()
    data class Hit(val damage: Int, val targetLifepointsLeft: Int) : AttackOutcome()
    data class Killed(val damage: Int, val death: DeathResult) : AttackOutcome()
    data class NotComputable(val reason: String) : AttackOutcome()
}

data class LevelUp(val stat: Stat, val oldLevel: Int, val newLevel: Int) {
    override fun toString() = "LevelUp($stat $oldLevel -> $newLevel)"
}

data class CombatXpAward(
    val style: CombatStyle,
    val damage: Int,
    val styleXpTenths: Int,
    val constitutionXpTenths: Int,
    val levelUps: List<LevelUp>,
    val levelsNotComputed: String?
)

data class PlayerDeath(
    val player: String,
    val diedAt: TileLocation,
    val respawnedAt: TileLocation,
    val lifepointsRestored: Int
)

interface CombatDefender {
    fun level(stat: Stat): Int

    val currentLifepoints: Int

    val maxLifepoints: Int

    fun takeDamage(amount: Int): PlayerDeath?

    fun combatLoadout(): CombatLoadout? = null
}

package com.opennxt.content.ability

import com.opennxt.content.ActionLock
import com.opennxt.content.ability.AbilityActivation.Outcome
import com.opennxt.content.ability.AbilityActivation.Reason
import com.opennxt.content.ability.AbilityDefinitions.Definition
import com.opennxt.model.combat.PlayerCombat
import com.opennxt.model.entity.movement.CompassPoint
import com.opennxt.model.entity.movement.Movement
import com.opennxt.model.map.CollisionMap
import com.opennxt.model.world.TileLocation
import com.opennxt.model.world.WorldPlayer
import mu.KotlinLogging

object MovementAbilities {
    private val logger = KotlinLogging.logger { }

    const val FLAG = "opennxt.combat.movementAbilities"
    val enabled: Boolean get() = System.getProperty(FLAG)?.trim()?.lowercase().let { it == "on" || it == "true" }

    const val SURGE = 14726
    const val ESCAPE = 14665
    const val ESCAPE_DEFAULT_TILES = 7
    const val ESCAPE_MIN_TILES = 3
    const val ESCAPE_MAX_TILES = 8

    fun escapeTiles(player: WorldPlayer): Int {
        val weapon = player.wornWeapon ?: return ESCAPE_DEFAULT_TILES
        if (AbilityActivation.weaponStyle(weapon) != AbilityDefinitions.Style.RANGED) return ESCAPE_DEFAULT_TILES
        val range = runCatching { PlayerCombat.playerAttackRange(player) }.getOrNull() ?: return ESCAPE_DEFAULT_TILES
        return (range - 1).coerceIn(ESCAPE_MIN_TILES, ESCAPE_MAX_TILES)
    }

    data class Dash(val structId: Int, val tiles: Int, val backwards: Boolean, val source: String)

    val DASHES: Map<Int, Dash> = mapOf(
        SURGE to Dash(SURGE, 10, false, "Surge: 10 tiles straight ahead in the facing direction, stops at obstacles"),
        ESCAPE to Dash(ESCAPE, ESCAPE_DEFAULT_TILES, true, "Escape: backwards by the ranged weapon's attack range minus 1 (3..8), 7 without one"),
    )

    var dashes = 0; private set
    var tilesMoved = 0L; private set
    var blockedShort = 0; private set

    fun handles(def: Definition): Boolean = enabled && def.structId in DASHES

    fun facing(player: WorldPlayer): CompassPoint {
        val loc = player.entity.location
        val target = runCatching { PlayerCombat.targetOf(player) }.getOrNull()
        if (target != null && target.alive && target.location.plane == loc.plane) {
            val size = (target.combat?.size ?: 1).coerceAtLeast(1)
            val dx = Integer.signum((target.location.x * 2 + size - 1) - loc.x * 2)
            val dz = Integer.signum((target.location.y * 2 + size - 1) - loc.y * 2)
            CompassPoint.forDelta(dx, dz)?.let { return it }
        }
        return player.entity.movement.lastStepDirection ?: CompassPoint.SOUTH
    }

    fun trace(x: Int, z: Int, plane: Int, dx: Int, dz: Int, tiles: Int): TileLocation {
        runCatching { Movement.clipPathEnds(x, z, x + dx * tiles, z + dz * tiles, plane) }
        var cx = x
        var cz = z
        for (i in 0 until tiles) {
            if (!CollisionMap.canStep(cx, cz, dx, dz, plane)) break
            cx += dx
            cz += dz
        }
        return TileLocation(cx, cz, plane)
    }

    internal fun activate(player: WorldPlayer, st: AbilityState, def: Definition, via: String, queueSlot: Pair<Int, Int>?,
                          refuse: (Reason, String, Boolean) -> Outcome.Refused): Outcome {
        val dash = DASHES.getValue(def.structId)
        val now = AbilityActivation.clock
        if (player.currentLifepoints <= 0) return refuse(Reason.DEAD, "lifepoints ${player.currentLifepoints}", false)
        ActionLock.reasonFor(player.contentPlayer)?.let { return refuse(Reason.LOCKED, it, false) }
        def.level?.let { req ->
            val have = player.stats.getLevel(AbilityActivation.levelStat(def.style))
            if (have < req) return refuse(Reason.LEVEL, "${AbilityActivation.levelStat(def.style).name} $have < $req", false)
        }
        val left = st.cooldownRemaining(def.structId, now)
        if (left > 0) {
            val q = queueSlot?.let { (qi, qs) -> AbilityQueue.offer(player, st, def, qi, qs, now) }
            return if (q != null) refuse(Reason.COOLDOWN, "${def.name} cools down for $left more tick(s); $q", false)
            else refuse(Reason.COOLDOWN, "${def.name} cools down for $left more tick(s)", true)
        }

        AbilityQueue.onCommit(player, st, def)
        val face = facing(player)
        val dx = if (dash.backwards) -face.dx else face.dx
        val dz = if (dash.backwards) -face.dy else face.dy
        val loc = player.entity.location
        val tiles = if (dash.structId == ESCAPE) escapeTiles(player) else dash.tiles
        val to = trace(loc.x, loc.y, loc.plane, dx, dz, tiles)
        val moved = maxOf(Math.abs(to.x - loc.x), Math.abs(to.y - loc.y))
        if (moved > 0) player.entity.movement.teleport(to)
        if (moved < tiles) blockedShort++
        tilesMoved += moved
        dashes++
        val cooldownEnd = now + def.cooldownTicks
        if (def.cooldownTicks > 0) st.cooldownEnd[def.structId] = cooldownEnd
        st.activations++
        val (animation, graphic) = AbilityGraphics.play(player, def, AbilityGraphics.Tier.LOW)
        AbilityActivation.dashWire(player, def, now, cooldownEnd)
        logger.info {
            "abilities: ${player.name} ${def.name.uppercase()} via $via at tick $now: facing $face, ${if (dash.backwards) "backwards " else ""}" +
                "($dx,$dz) x $tiles -> (${loc.x},${loc.y}) to (${to.x},${to.y}), $moved tile(s)" +
                (if (moved < tiles) " (stopped by collision)" else "") + ", cooldown to $cooldownEnd, animation ${animation ?: "none"}, graphic ${graphic ?: "none"}"
        }
        return Outcome.Activated(def, null, now, st.adrenaline.tenths, st.adrenaline.tenths, st.gcdEnd, cooldownEnd.takeIf { def.cooldownTicks > 0 },
            emptyList(), def.hitSource, animation, 0, null, listOf("dash:$tiles${if (dash.backwards) ":back" else ""}->(${to.x},${to.y})"))
    }
}

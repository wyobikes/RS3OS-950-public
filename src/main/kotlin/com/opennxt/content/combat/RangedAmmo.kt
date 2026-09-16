package com.opennxt.content.combat

import com.opennxt.content.ability.AbilityActivation
import com.opennxt.content.ability.AbilityDefinitions
import com.opennxt.content.ability.AbilityDefinitions.Definition
import com.opennxt.content.ability.PendingHit
import com.opennxt.model.combat.CombatStyle
import com.opennxt.model.combat.NpcCombatParams
import com.opennxt.model.combat.PlayerCombat
import com.opennxt.model.entity.player.PlayerInventory
import com.opennxt.model.items.Item
import com.opennxt.model.world.EquippedWeapon
import com.opennxt.model.world.PlayerCombatStats
import com.opennxt.model.world.WorldNpc
import com.opennxt.model.world.WorldPlayer
import com.opennxt.net.game.serverprot.MessageGame
import mu.KotlinLogging
import java.util.Collections
import java.util.Random
import java.util.WeakHashMap

object RangedAmmo {
    private val logger = KotlinLogging.logger { }

    const val FLAG = "opennxt.combat.ammo"
    const val ABILITY_CHANCE_FLAG = "opennxt.combat.ammo.abilityChance"
    const val THROWN_FLAG = "opennxt.combat.ammo.thrown"

    val enabled: Boolean get() = System.getProperty(FLAG) != "off"
    val abilityChancePercent: Int
        get() = System.getProperty(ABILITY_CHANCE_FLAG)?.trim()?.toIntOrNull()?.coerceIn(0, 100) ?: DEFAULT_ABILITY_CHANCE_PERCENT
    val thrownEnabled: Boolean get() = System.getProperty(THROWN_FLAG) != "off"

    const val DEFAULT_ABILITY_CHANCE_PERCENT = 15

    const val AMMO_SLOT = 13
    const val PARAM_AMMO_CATEGORY = 21
    const val PARAM_NEEDS_AMMO = 2822
    const val PARAM_PROJECTILE = 2940
    const val PARAM_TIER = 750
    const val DAMAGE_X10_PER_TIER = 96

    const val MESSAGE_NO_AMMO = "You have no ammunition."
    const val MESSAGE_WRONG_AMMO = "You can't fire that ammunition from this weapon."

    const val AMMO_SEED = 0x414D4D4FL
    var random: Random = Random(PlayerCombat.bootSeed xor AMMO_SEED)

    var projectileHook: ((WorldPlayer, WorldNpc, Int) -> Unit)? = null

    var observer: ((WorldPlayer, String) -> Unit)? = null

    var swingsConsumed = 0; private set
    var thrownConsumed = 0; private set
    var castsRolled = 0; private set
    var castsConsumed = 0; private set
    var refusalsNoAmmo = 0; private set
    var refusalsWrongAmmo = 0; private set
    var capsApplied = 0; private set
    var projectilesRequested = 0; private set
    var stacksEmptied = 0; private set
    var messagesSent = 0; private set

    private val messaged: MutableMap<WorldPlayer, String> = Collections.synchronizedMap(WeakHashMap())
    private val loggedUncapped = HashSet<Int>()
    private var installed = false

    sealed class Need {
        object None : Need()
        data class Quiver(val category: Int?) : Need()
        object Thrown : Need()
    }

    fun needOf(weapon: EquippedWeapon?): Need {
        if (weapon == null || weapon.style != CombatStyle.RANGED) return Need.None
        val p = weapon.params
        if (p[PARAM_NEEDS_AMMO] == 1) return Need.Quiver(p[PARAM_AMMO_CATEGORY])
        if (p.containsKey(PARAM_AMMO_CATEGORY)) return Need.None
        val thrown = weapon.definition.stackable && p.containsKey(PARAM_PROJECTILE)
        return if (thrown) Need.Thrown else Need.None
    }

    data class Loaded(val slot: Int, val item: Item, val params: Map<Int, Int>) {
        val projectile: Int? get() = params[PARAM_PROJECTILE]
        val tier: Int? get() = params[PARAM_TIER]
        val damageTermX10: Int? get() = params[NpcCombatParams.ITEM_RANGED_DAMAGE] ?: tier?.let { it * DAMAGE_X10_PER_TIER }
    }

    sealed class Resolution {
        object NotNeeded : Resolution()
        data class Ready(val loaded: Loaded) : Resolution()
        object NoAmmo : Resolution()
        data class WrongCategory(val held: Item, val heldCategory: Int?, val wanted: Int) : Resolution()
    }

    fun ammoFor(player: WorldPlayer, weapon: EquippedWeapon? = player.wornWeapon): Resolution {
        if (!enabled) return Resolution.NotNeeded
        return when (val need = needOf(weapon)) {
            Need.None -> Resolution.NotNeeded
            is Need.Quiver -> {
                val held = player.worn[AMMO_SLOT] ?: return Resolution.NoAmmo
                if (held.amount <= 0) return Resolution.NoAmmo
                val def = PlayerCombatStats.lookupWeapon(held.id) ?: return Resolution.NoAmmo
                val category = def.definition.category
                if (need.category != null && category != need.category) return Resolution.WrongCategory(held, category, need.category)
                Resolution.Ready(Loaded(AMMO_SLOT, held, def.params))
            }
            Need.Thrown -> {
                if (!thrownEnabled) return Resolution.NotNeeded
                val held = player.worn[PlayerInventory.WEAPON_SLOT] ?: return Resolution.NoAmmo
                if (held.amount <= 0) return Resolution.NoAmmo
                Resolution.Ready(Loaded(PlayerInventory.WEAPON_SLOT, held, weapon!!.params))
            }
        }
    }

    fun gate(player: WorldPlayer, npc: WorldNpc, style: CombatStyle): String? {
        if (!enabled || style != CombatStyle.RANGED) return null
        val weapon = player.wornWeapon
        return when (val r = ammoFor(player, weapon)) {
            Resolution.NotNeeded, is Resolution.Ready -> { messaged.remove(player); null }
            Resolution.NoAmmo -> {
                refusalsNoAmmo++
                message(player, MESSAGE_NO_AMMO)
                "${player.name}'s ${weapon?.definition?.name ?: "weapon"} needs ammunition (param $PARAM_NEEDS_AMMO) and worn slot $AMMO_SLOT is empty"
            }
            is Resolution.WrongCategory -> {
                refusalsWrongAmmo++
                message(player, MESSAGE_WRONG_AMMO)
                "${player.name}'s ${weapon?.definition?.name ?: "weapon"} fires category ${r.wanted} (param $PARAM_AMMO_CATEGORY); " +
                    "slot $AMMO_SLOT holds item ${r.held.id} of category ${r.heldCategory ?: "none"}"
            }
        }
    }

    fun capMaxHitX10(player: WorldPlayer, style: CombatStyle, derivedX10: Int): Int {
        if (!enabled || style != CombatStyle.RANGED) return derivedX10
        val weapon = player.wornWeapon ?: return derivedX10
        val need = needOf(weapon)
        if (need !is Need.Quiver) return derivedX10
        val ready = ammoFor(player, weapon) as? Resolution.Ready ?: return derivedX10
        val weaponTerm = weapon.params[NpcCombatParams.ITEM_RANGED_DAMAGE] ?: return derivedX10
        val ammoTerm = ready.loaded.damageTermX10
        if (ammoTerm == null) {
            if (loggedUncapped.add(ready.loaded.item.id)) logger.info {
                "AMMO ${player.name}: ammunition ${ready.loaded.item.id} carries neither 643 nor $PARAM_TIER - no tier cap on the swing (absent is not zero). Noted once per item."
            }
            return derivedX10
        }
        val capped = derivedX10 - weaponTerm + minOf(weaponTerm, ammoTerm)
        if (capped < derivedX10) capsApplied++
        return capped
    }

    fun consumeOnSwing(player: WorldPlayer, npc: WorldNpc, style: CombatStyle) {
        if (!enabled || style != CombatStyle.RANGED) return
        val ready = ammoFor(player) as? Resolution.Ready ?: return
        if (consumeOne(player, ready.loaded, "swing")) {
            swingsConsumed++
            if (ready.loaded.slot == PlayerInventory.WEAPON_SLOT) thrownConsumed++
        }
        fire(player, npc, ready.loaded.projectile)
    }

    fun castSpendsAmmo(player: WorldPlayer, def: Definition): Boolean {
        val weapon = player.wornWeapon
        if (needOf(weapon) == Need.None) return false
        return def.style == AbilityDefinitions.Style.RANGED ||
            (!def.style.isCombatBook && def.hits.isNotEmpty() && AbilityActivation.weaponStyle(weapon) == AbilityDefinitions.Style.RANGED)
    }

    fun onCast(player: WorldPlayer, def: Definition) {
        if (!enabled || !castSpendsAmmo(player, def)) return
        val ready = ammoFor(player) as? Resolution.Ready ?: return
        castsRolled++
        val roll = random.nextInt(100)
        if (roll < abilityChancePercent) {
            if (consumeOne(player, ready.loaded, "cast of ${def.name}")) castsConsumed++
        }
    }

    fun onAbilityHit(player: WorldPlayer, hit: PendingHit, npc: WorldNpc, applied: Int) {
        if (!enabled || hit.style != AbilityDefinitions.Style.RANGED) return
        val ready = ammoFor(player) as? Resolution.Ready ?: return
        fire(player, npc, ready.loaded.projectile)
    }

    private fun consumeOne(player: WorldPlayer, loaded: Loaded, why: String): Boolean {
        val worn = player.worn
        val held = worn[loaded.slot]
        if (held == null || held.id != loaded.item.id || held.amount <= 0) {
            logger.warn { "AMMO ${player.name}: slot ${loaded.slot} no longer holds ${loaded.item.id} at the $why - nothing spent" }
            return false
        }
        val left = held.amount - 1
        worn[loaded.slot] = if (left > 0) Item(held.id, left) else null
        PlayerInventory.sendWorn(player)
        if (left <= 0) {
            stacksEmptied++
            player.entity.model.dirty = true
            logger.info { "AMMO ${player.name}: the last ${loaded.item.id} left worn slot ${loaded.slot} on a $why" }
        }
        return true
    }

    private fun fire(player: WorldPlayer, npc: WorldNpc, spotanim: Int?) {
        val hook = projectileHook ?: return
        if (spotanim == null) return
        projectilesRequested++
        runCatching { hook(player, npc, spotanim) }.onFailure { logger.error(it) { "AMMO projectile hook threw for ${player.name}; contained" } }
    }

    private fun message(player: WorldPlayer, text: String) {
        if (messaged[player] == text) return
        messaged[player] = text
        messagesSent++
        observer?.invoke(player, text)
        runCatching { player.client.write(MessageGame(0, text)) }.onFailure { logger.debug { "AMMO could not write '$text' to ${player.name}: ${it.message}" } }
    }

    fun install() {
        if (installed) return
        installed = true
        val prevGate = PlayerCombat.swingGate
        PlayerCombat.swingGate = { p, n, s -> prevGate?.invoke(p, n, s) ?: gate(p, n, s) }
        val prevCap = PlayerCombat.swingMaxHitX10Override
        PlayerCombat.swingMaxHitX10Override = { p, s, x -> capMaxHitX10(p, s, prevCap?.invoke(p, s, x) ?: x) }
        val prevConsume = PlayerCombat.swingConsumer
        PlayerCombat.swingConsumer = { p, n, s -> prevConsume?.invoke(p, n, s); consumeOnSwing(p, n, s) }
        val prevCast = AbilityActivation.onAbilityCast
        AbilityActivation.onAbilityCast = { p, d -> prevCast?.invoke(p, d); onCast(p, d) }
        val prevHit = AbilityActivation.onAbilityHitLanded
        AbilityActivation.onAbilityHitLanded = { p, h, n, a -> prevHit?.invoke(p, h, n, a); onAbilityHit(p, h, n, a) }
        val prevAbilityCap = AbilityActivation.abilityMaxHitX10Override
        AbilityActivation.abilityMaxHitX10Override = { p, s, x -> abilityCapMaxHitX10(p, s, if (prevAbilityCap != null) prevAbilityCap(p, s, x) else x) }
        logger.info {
            "content: ranged ammunition installed - $FLAG=${if (enabled) "on" else "off"}, ability cast chance $abilityChancePercent%, " +
                "thrown ${if (thrownEnabled) "consumed" else "kept"}"
        }
    }

    fun abilityCapMaxHitX10(player: WorldPlayer, style: AbilityDefinitions.Style, derivedX10: Int?): Int? {
        if (!enabled || style != AbilityDefinitions.Style.RANGED || derivedX10 == null) return derivedX10
        return capMaxHitX10(player, CombatStyle.RANGED, derivedX10)
    }
}

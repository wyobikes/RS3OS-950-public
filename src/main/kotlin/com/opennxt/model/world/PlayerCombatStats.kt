package com.opennxt.model.world

import com.opennxt.api.stat.Stat
import com.opennxt.api.stat.levelForXp
import com.opennxt.model.combat.NpcCombat
import com.opennxt.model.combat.NpcCombatParams
import com.opennxt.resources.sqlite.ItemDefinition
import com.opennxt.resources.sqlite.RsDatabase
import com.opennxt.resources.sqlite.SqliteItemCodec

object PlayerCombatStats {
    const val ABILITY_DAMAGE_PER_LEVEL_X10 = 25

    fun tierFunction(x: Int): Double {
        val v = x.coerceAtLeast(1).toDouble()
        return 0.0008 * v * v * v + 4.0 * v + 40.0
    }

    fun levelAccuracyTerm(level: Int): Int = Math.floor(tierFunction(level)).toInt()

    fun effectiveAccuracy(styleLevel: Int, weapon: EquippedWeapon?): Int? {
        val levelTerm = levelAccuracyTerm(styleLevel)
        if (weapon == null) return levelTerm
        val weaponTerm = weapon.accuracy ?: return null
        return levelTerm + weaponTerm
    }

    fun effectiveMaxHitX10(damageSkillLevel: Int, weapon: EquippedWeapon?): Int? {
        val levelTerm = ABILITY_DAMAGE_PER_LEVEL_X10 * damageSkillLevel.coerceAtLeast(1)
        if (weapon == null) return levelTerm
        val weaponTerm = weapon.damageX10 ?: return null
        return levelTerm + weaponTerm
    }

    fun defence(defenceLevel: Int): Int = levelAccuracyTerm(defenceLevel)

    fun combatLevel(
        attack: Int, strength: Int, defence: Int, constitution: Int, prayer: Int, summoning: Int,
        ranged: Int, magic: Int, necromancy: Int
    ): Int {
        val base = 0.25 * (defence + constitution + prayer / 2 + summoning / 2)
        val best = maxOf(attack + strength, 2 * ranged, 2 * magic, 2 * necromancy)
        return (base + 0.325 * best).toInt()
    }

    fun combatLevel(player: WorldPlayer): Int = combatLevel(
        player.level(Stat.ATTACK), player.level(Stat.STRENGTH), player.level(Stat.DEFENCE),
        player.level(Stat.CONSTITUTION), player.level(Stat.PRAYER), player.level(Stat.SUMMONING),
        player.level(Stat.RANGED), player.level(Stat.MAGIC), player.level(Stat.NECROMANCY)
    )

    fun derive(
        attackLevel: Int,
        strengthLevel: Int,
        magicLevel: Int,
        rangedLevel: Int,
        defenceLevel: Int,
        weapon: EquippedWeapon?,
        loadout: CombatLoadout? = null
    ): PlayerDerivedStats {
        val defenceRating = if (loadout == null) defence(defenceLevel) else defenceRating(defenceLevel, loadout)
        if (weapon == null) {
            val unarmedX10 = effectiveMaxHitX10(strengthLevel, null)
            return PlayerDerivedStats(
                effectiveAccuracy = effectiveAccuracy(attackLevel, null),
                effectiveMaxHitX10 = if (loadout == null || unarmedX10 == null) unarmedX10
                    else loadoutMaxHitX10(strengthLevel, com.opennxt.model.combat.CombatStyle.MELEE, null, unarmedX10, loadout),
                defence = defenceRating,
                gaps = emptyList()
            )
        }
        val gaps = mutableListOf<String>()
        val skill = weapon.requirementSkill
        val style = weapon.style
        val (accuracyLevel, damageLevel) = when (style) {
            com.opennxt.model.combat.CombatStyle.MELEE -> attackLevel to strengthLevel
            com.opennxt.model.combat.CombatStyle.RANGED -> rangedLevel to rangedLevel
            com.opennxt.model.combat.CombatStyle.MAGIC -> magicLevel to magicLevel
            null -> {
                gaps += "weapon ${weapon.definition.id} (${weapon.definition.name}) has no combat style " +
                    (if (skill == null) "(no style or requirement-skill param)"
                     else "(requirement skill $skill)")
                return PlayerDerivedStats(null, null, defenceRating, gaps)
            }
        }
        val accuracyParam = when (style) {
            com.opennxt.model.combat.CombatStyle.RANGED -> NpcCombatParams.ITEM_RANGED_ACCURACY
            com.opennxt.model.combat.CombatStyle.MAGIC -> NpcCombatParams.ITEM_MAGIC_ACCURACY
            else -> NpcCombatParams.ITEM_ACCURACY
        }
        val damageParam = when (style) {
            com.opennxt.model.combat.CombatStyle.RANGED -> NpcCombatParams.ITEM_RANGED_DAMAGE
            com.opennxt.model.combat.CombatStyle.MAGIC -> NpcCombatParams.ITEM_MAGIC_DAMAGE
            else -> NpcCombatParams.ITEM_DAMAGE
        }
        val accuracy = effectiveAccuracy(accuracyLevel, weapon)
        if (accuracy == null) gaps += "weapon ${weapon.definition.id} (${weapon.definition.name}) " +
            "has no $style accuracy param ($accuracyParam)"
        val weaponMaxHitX10 = effectiveMaxHitX10(damageLevel, weapon)
        val maxHitX10 = if (loadout == null || weaponMaxHitX10 == null) weaponMaxHitX10
            else loadoutMaxHitX10(damageLevel, style ?: com.opennxt.model.combat.CombatStyle.MELEE, weapon, weaponMaxHitX10, loadout)
        if (maxHitX10 == null) gaps += "weapon ${weapon.definition.id} (${weapon.definition.name}) " +
            "has no $style damage param ($damageParam)"
        val range = weapon.attackRange
        if (range == null) gaps += "weapon ${weapon.definition.id} (${weapon.definition.name}) " +
            "has no attack-range param (13)"
        return PlayerDerivedStats(accuracy, maxHitX10, defenceRating, gaps, style, range)
    }

    const val ITEM_ARMOUR_X10 = 2870

    const val ITEM_AFFINITY_VS_MELEE = 2866
    const val ITEM_AFFINITY_VS_RANGED = 2867
    const val ITEM_AFFINITY_VS_MAGIC = 2868

    const val OFFHAND_SLOT = 5

    const val AMMO_SLOT = 13

    const val TWO_HANDED_EQUIP_ID = 5

    fun loadoutOf(player: WorldPlayer): CombatLoadout {
        val worn = player.worn
        var main: EquippedWeapon? = null
        var off: EquippedWeapon? = null
        val rest = ArrayList<EquippedWeapon>()
        for (slot in 0 until worn.size) {
            val item = worn[slot] ?: continue
            val piece = lookupWeapon(item.id) ?: continue
            when (slot) {
                com.opennxt.model.entity.player.PlayerInventory.WEAPON_SLOT -> main = piece
                OFFHAND_SLOT -> off = piece
                AMMO_SLOT -> {}
                else -> rest += piece
            }
        }
        return CombatLoadout(main, off, rest)
    }

    fun styleDamageParamX10(piece: EquippedWeapon, style: com.opennxt.model.combat.CombatStyle): Int? = when (style) {
        com.opennxt.model.combat.CombatStyle.MELEE -> piece.params[NpcCombatParams.ITEM_DAMAGE]
        com.opennxt.model.combat.CombatStyle.RANGED -> piece.params[NpcCombatParams.ITEM_RANGED_DAMAGE]
        com.opennxt.model.combat.CombatStyle.MAGIC -> piece.params[NpcCombatParams.ITEM_MAGIC_DAMAGE]
    }

    fun isOffHandWeapon(piece: EquippedWeapon): Boolean =
        piece.params[NpcCombatParams.ITEM_STYLE_MELEE] == 1 || piece.params[NpcCombatParams.ITEM_STYLE_RANGED] == 1 ||
            piece.params[NpcCombatParams.ITEM_STYLE_MAGIC] == 1 || piece.params[NpcCombatParams.ATTACK_SPEED] != null

    fun damageBonusX10(style: com.opennxt.model.combat.CombatStyle, loadout: CombatLoadout): Int {
        var b = 0
        for (piece in loadout.armour) b += styleDamageParamX10(piece, style)?.coerceAtLeast(0) ?: 0
        val off = loadout.offHand
        if (off != null && !isOffHandWeapon(off)) b += styleDamageParamX10(off, style)?.coerceAtLeast(0) ?: 0
        return b
    }

    fun loadoutMaxHitX10(
        damageSkillLevel: Int,
        style: com.opennxt.model.combat.CombatStyle,
        mainHand: EquippedWeapon?,
        weaponMaxHitX10: Int,
        loadout: CombatLoadout
    ): Int {
        val b = damageBonusX10(style, loadout)
        if (mainHand != null && mainHand.definition.equipId == TWO_HANDED_EQUIP_ID) return weaponMaxHitX10 + (3 * b) / 2
        var total = weaponMaxHitX10 + b
        val off = loadout.offHand
        if (off != null && isOffHandWeapon(off) && off.style == style) {
            val offDamage = styleDamageParamX10(off, style)
            if (offDamage != null) {
                val levelTerm = ABILITY_DAMAGE_PER_LEVEL_X10 * damageSkillLevel.coerceAtLeast(1)
                total += (levelTerm + offDamage + b) / 2
            }
        }
        return total
    }

    fun wornArmourX10(loadout: CombatLoadout): Int =
        loadout.all.sumOf { (it.params[ITEM_ARMOUR_X10] ?: 0).coerceAtLeast(0) }

    fun defenceRating(defenceLevel: Int, loadout: CombatLoadout): Int =
        Math.floor(tierFunction(defenceLevel) + wornArmourX10(loadout) / 10.0).toInt()

    fun defenderAffinity(attackStyle: com.opennxt.model.combat.CombatStyle, loadout: CombatLoadout): Int {
        val param = when (attackStyle) {
            com.opennxt.model.combat.CombatStyle.MELEE -> ITEM_AFFINITY_VS_MELEE
            com.opennxt.model.combat.CombatStyle.RANGED -> ITEM_AFFINITY_VS_RANGED
            com.opennxt.model.combat.CombatStyle.MAGIC -> ITEM_AFFINITY_VS_MAGIC
        }
        var weight = 0L
        var sum = 0L
        for (piece in loadout.all) {
            val a = (piece.params[ITEM_ARMOUR_X10] ?: 0).coerceAtLeast(0)
            if (a == 0) continue
            weight += a
            sum += a.toLong() * (piece.params[param] ?: 60)
        }
        if (weight == 0L) return NpcRetaliation.PLAYER_AFFINITY
        val mean = sum.toDouble() / weight
        return (NpcRetaliation.PLAYER_AFFINITY + (mean - 60.0)).toInt()
    }

    fun levelFromXp(stat: Stat, xp: Int): Int = levelForXp(stat, xp)

    fun lookupWeapon(itemId: Int): EquippedWeapon? {
        synchronized(weaponCache) {
            if (weaponCache.containsKey(itemId)) return weaponCache[itemId]
        }
        val definition = SqliteItemCodec.load(itemId)
        val weapon = if (definition == null) null else {
            val json = RsDatabase.queryOne(
                "SELECT value FROM items_attr WHERE id = ? AND field = 'extra'", itemId
            ) { it.getString("value") }
            val params = json?.let { NpcCombat.parseParams(it) } ?: emptyMap()
            EquippedWeapon(definition, params)
        }
        synchronized(weaponCache) { weaponCache[itemId] = weapon }
        return weapon
    }

    private val weaponCache = HashMap<Int, EquippedWeapon?>()
}

data class EquippedWeapon(
    val definition: ItemDefinition,
    val params: Map<Int, Int>
) {
    val style: com.opennxt.model.combat.CombatStyle? get() = when {
        params[NpcCombatParams.ITEM_STYLE_RANGED] == 1 -> com.opennxt.model.combat.CombatStyle.RANGED
        params[NpcCombatParams.ITEM_STYLE_MAGIC] == 1 -> com.opennxt.model.combat.CombatStyle.MAGIC
        params[NpcCombatParams.ITEM_STYLE_MELEE] == 1 -> com.opennxt.model.combat.CombatStyle.MELEE
        else -> when (requirementSkill) {
            Stat.ATTACK.id, Stat.STRENGTH.id -> com.opennxt.model.combat.CombatStyle.MELEE
            Stat.RANGED.id -> com.opennxt.model.combat.CombatStyle.RANGED
            Stat.MAGIC.id -> com.opennxt.model.combat.CombatStyle.MAGIC
            else -> null
        }
    }

    val accuracy: Int? get() = when (style) {
        com.opennxt.model.combat.CombatStyle.RANGED -> params[NpcCombatParams.ITEM_RANGED_ACCURACY]
        com.opennxt.model.combat.CombatStyle.MAGIC -> params[NpcCombatParams.ITEM_MAGIC_ACCURACY]
        else -> params[NpcCombatParams.ITEM_ACCURACY]
    }

    val damageX10: Int? get() = when (style) {
        com.opennxt.model.combat.CombatStyle.RANGED -> params[NpcCombatParams.ITEM_RANGED_DAMAGE]
        com.opennxt.model.combat.CombatStyle.MAGIC -> params[NpcCombatParams.ITEM_MAGIC_DAMAGE]
        else -> params[NpcCombatParams.ITEM_DAMAGE]
    }

    val attackRange: Int? get() = params[NpcCombatParams.ITEM_ATTACK_RANGE]?.takeIf { it > 0 }
        ?: if (style == com.opennxt.model.combat.CombatStyle.MELEE) 1 else null

    val requirementSkill: Int? get() = params[NpcCombatParams.ITEM_REQUIREMENT_SKILL]

    val requirementLevel: Int? get() = params[NpcCombatParams.ITEM_REQUIREMENT_LEVEL]

    val attackSpeedTicks: Int? get() = params[NpcCombatParams.ATTACK_SPEED]

    val equipSlot: Int? get() = definition.equipSlotId

    override fun toString() =
        "EquippedWeapon(${definition.id} ${definition.name ?: "unnamed"}, " +
            "accuracy=${accuracy ?: "null"}, damageX10=${damageX10 ?: "null"}, " +
            "req=${requirementSkill ?: "null"}/${requirementLevel ?: "null"})"
}

data class CombatLoadout(
    val mainHand: EquippedWeapon?,
    val offHand: EquippedWeapon?,
    val armour: List<EquippedWeapon>
) {
    val all: List<EquippedWeapon> get() = listOfNotNull(mainHand, offHand) + armour

    companion object {
        val EMPTY = CombatLoadout(null, null, emptyList())
    }
}

data class PlayerDerivedStats(
    val effectiveAccuracy: Int?,
    val effectiveMaxHitX10: Int?,
    val defence: Int,
    val gaps: List<String>,
    val style: com.opennxt.model.combat.CombatStyle = com.opennxt.model.combat.CombatStyle.MELEE,
    val attackRange: Int? = 1
)

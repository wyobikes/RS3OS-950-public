package com.opennxt.model.combat

object CombatXp {
    const val BASE_XP_PER_LIFEPOINT = 0.05

    const val CONSTITUTION_SHARE = 0.33

    fun selectedSkills(style: CombatStyle): List<com.opennxt.api.stat.Stat> = when (style) {
        CombatStyle.MELEE -> listOf(com.opennxt.api.stat.Stat.ATTACK, com.opennxt.api.stat.Stat.STRENGTH, com.opennxt.api.stat.Stat.DEFENCE)
        CombatStyle.RANGED -> listOf(com.opennxt.api.stat.Stat.RANGED, com.opennxt.api.stat.Stat.DEFENCE)
        CombatStyle.MAGIC -> listOf(com.opennxt.api.stat.Stat.MAGIC, com.opennxt.api.stat.Stat.DEFENCE)
    }

    fun splitAward(npcId: Int?, damage: Int, style: CombatStyle): Map<com.opennxt.api.stat.Stat, Double> =
        split(npcId, damage, selectedSkills(style))

    val NECROMANCY_SKILLS: List<com.opennxt.api.stat.Stat> =
        listOf(com.opennxt.api.stat.Stat.NECROMANCY, com.opennxt.api.stat.Stat.DEFENCE)

    fun necromancyAward(npcId: Int?, damage: Int): Map<com.opennxt.api.stat.Stat, Double> =
        split(npcId, damage, NECROMANCY_SKILLS)

    fun killAward(death: com.opennxt.model.world.DeathResult, name: String, npcId: Int?): Map<com.opennxt.api.stat.Stat, Double> {
        val damage = death.damageLedger[name] ?: return emptyMap()
        return if (name in death.necromancyLedger) necromancyAward(npcId, damage)
        else splitAward(npcId, damage, death.styleLedger[name] ?: CombatStyle.MELEE)
    }

    private fun split(npcId: Int?, damage: Int, skills: List<com.opennxt.api.stat.Stat>): Map<com.opennxt.api.stat.Stat, Double> {
        if (damage <= 0) return emptyMap()
        val block = styleXp(npcId, damage)
        val out = LinkedHashMap<com.opennxt.api.stat.Stat, Double>()
        for (stat in skills) out[stat] = block / skills.size
        out[com.opennxt.api.stat.Stat.CONSTITUTION] = constitutionXp(block)
        return out
    }

    data class Rate(val xpPerLifepoint: Double, val provenance: String, val source: String) {
        override fun toString() = "%.4f xp/lp [%s: %s]".format(xpPerLifepoint, provenance, source)
    }

    val BASE = Rate(BASE_XP_PER_LIFEPOINT, "DOCUMENTED-BASE",
        "default rate: 0.05 xp per lifepoint of damage")

    fun rateFor(npcId: Int): Rate {
        val combat = SeedData.refCombat(npcId) ?: return BASE
        val xp = combat.experience ?: return BASE
        val lp = SeedData.refLifepoints(npcId)?.value ?: return BASE
        if (xp <= 0.0 || lp <= 0) return BASE
        return Rate(xp / lp, "DOCUMENTED",
            "${SeedData.refLifepoints(npcId)?.source}: experience $xp over $lp lifepoints")
    }

    fun styleXp(npcId: Int?, damage: Int): Double =
        damage * (npcId?.let { rateFor(it) } ?: BASE).xpPerLifepoint

    fun constitutionXp(styleXp: Double): Double = styleXp * CONSTITUTION_SHARE

    val PROVENANCE: String =
        "combat xp: per-monster rate (default 0.05 xp/lp), paid on kill by damage share; Constitution gets 0.33"
}

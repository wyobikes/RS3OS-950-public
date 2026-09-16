package com.opennxt.content.skills

import com.opennxt.api.stat.Stat

object SkillBones {
    enum class Level { FIXED, REF_TABLE, COMBAT, BONES, NONE }

    data class Entry(val stat: Stat, val level: Level, val where: String, val triggers: String, val missing: String)

    val TABLE: List<Entry> = listOf(
        Entry(Stat.ATTACK, Level.COMBAT, "PlayerCombat, content/ability", "Attack an npc; melee abilities", "PvP; most ability effects"),
        Entry(Stat.DEFENCE, Level.COMBAT, "PlayerCombat, CombatXp", "combat xp split", "defensive abilities' effects"),
        Entry(Stat.STRENGTH, Level.COMBAT, "PlayerCombat, content/ability", "melee", "most ability effects"),
        Entry(Stat.CONSTITUTION, Level.COMBAT, "CombatXp, WorldPlayer.takeDamage", "combat xp split", "lifepoint regeneration"),
        Entry(Stat.RANGED, Level.COMBAT, "PlayerCombat, content/ability", "bows, ranged abilities", "ammo consumption, projectiles"),
        Entry(Stat.PRAYER, Level.BONES, "Bury, SkillInteractions", "Bury; Scatter; altar Offer; altar Pray", "prayer points, prayers/curses, altar bonus"),
        Entry(Stat.MAGIC, Level.BONES, "content/ability (magic book), SkillRecipes category 4", "magic abilities; alchemy and Superheat Item on a backpack item; orb recipes via item-on-item",
            "teleports, enchanting and other spells; elemental staves"),
        Entry(Stat.COOKING, Level.FIXED, "Cooking", "raw food on a fire", "ranges, make-X count"),
        Entry(Stat.WOODCUTTING, Level.FIXED, "Skilling", "Chop down", "wood box"),
        Entry(Stat.FLETCHING, Level.FIXED, "Fletching + ProductionActions", "Craft/Feather/Tip; item-on-item (string, arrows)", "make-X count"),
        Entry(Stat.FISHING, Level.FIXED, "Fishing", "fishing spot options", "fishing bait consumption variants"),
        Entry(Stat.FIREMAKING, Level.FIXED, "Firemaking", "Light", "bonfires, fire lifetime"),
        Entry(Stat.CRAFTING, Level.BONES, "ProductionActions (cache recipes, 1,219)", "Craft/Spin/Weave rows; chisel on gem, needle on leather, moulds", "spinning wheel / pottery wheel / furnace locs, make-X panel"),
        Entry(Stat.SMITHING, Level.FIXED, "Smithing, SmithingPanel, MetalBanks", "Smelt/Heat/Smith", "batches"),
        Entry(Stat.MINING, Level.FIXED, "Skilling", "Mine", "per-swing rock progress, ore box, geodes"),
        Entry(Stat.HERBLORE, Level.BONES, "ProductionActions (cache recipes, 459)", "Clean; herb on vial; secondary on unfinished potion", "potion effects, dose decanting"),
        Entry(Stat.AGILITY, Level.BONES, "Obstacles (move) + SkillInteractions (xp)", "crossing a course obstacle", "course laps, fail chance, real per-obstacle xp (placeholder values)"),
        Entry(Stat.THIEVING, Level.REF_TABLE, "Thieving", "Pickpocket", "stalls, chests, safes"),
        Entry(Stat.SLAYER, Level.BONES, "SkillInteractions", "Get task at a master; kills count down", "slayer points/shop, assignment weights, HUD task counter"),
        Entry(Stat.FARMING, Level.BONES, "SkillInteractions", "Harvest (placed crops), Clear", "Rake/Water/Cure, planting, growth cycles, patch varbits"),
        Entry(Stat.RUNECRAFTING, Level.BONES, "SkillInteractions -> ProductionActions", "Craft runes at an altar", "ruins/tiara entry, multiple runes per essence, Runespan"),
        Entry(Stat.HUNTER, Level.BONES, "SkillInteractions", "Catch implings/butterflies", "traps (Lay/Check/Dismantle), nets and jars required"),
        Entry(Stat.CONSTRUCTION, Level.BONES, "ProductionActions (planks etc.) + SkillInteractions", "Build (a line); cache construction recipes", "player-owned houses (instancing)"),
        Entry(Stat.SUMMONING, Level.BONES, "SkillInteractions -> ProductionActions", "Infuse-pouch at an obelisk; Renew points", "familiars, scrolls, summoning points"),
        Entry(Stat.DUNGEONEERING, Level.BONES, "SkillBones only", "none (Daemonheim is instanced)", "dungeons"),
        Entry(Stat.DIVINATION, Level.BONES, "SkillInteractions + ProductionActions (transmutation)", "Harvest wisps/springs; Convert memories at a rift", "wisp depletion, energy, enriched memories"),
        Entry(Stat.INVENTION, Level.BONES, "ProductionActions (cache recipes, 244)", "Disassemble (a line, nothing consumed); manufacturing via item-on-item", "disassembly materials, augmentors, perks"),
        Entry(Stat.ARCHAEOLOGY, Level.BONES, "SkillInteractions + ProductionActions (restoration)", "Excavate material caches and hotspots", "artefacts, chronotes, real xp (placeholder values)"),
        Entry(Stat.NECROMANCY, Level.BONES, "content/ability (necromancy book), ProductionActions (ritual components)", "necromancy abilities; ritual components via item-on-item", "rituals, conjures")
    )

    fun entryFor(stat: Stat): Entry? = TABLE.firstOrNull { it.stat == stat }

    fun uncovered(): List<Stat> = Stat.values().filter { entryFor(it) == null }

    fun describe(): List<String> = TABLE.map { e ->
        val recipes = runCatching { SkillRecipes.recipesFor(e.stat).size }.getOrDefault(0)
        "${e.stat.name.padEnd(13)} ${e.level.name.padEnd(8)} ${e.where}; triggers: ${e.triggers}" +
            (if (recipes > 0) "; $recipes cache recipes" else "") + "; missing: ${e.missing}"
    }
}

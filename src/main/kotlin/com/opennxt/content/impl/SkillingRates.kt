package com.opennxt.content.impl

object SkillingRates {
    val WOODCUTTING_XP_TENTHS: Map<String, Int> = mapOf(
        "Logs" to 250,
        "Oak logs" to 375,
        "Willow logs" to 675,
        "Magic logs" to 3650,
        "Elder logs" to 4250
    )

    val FISHING_XP_TENTHS: Map<String, Int> = mapOf(
        "Raw shrimps" to 100,
        "Raw mackerel" to 400,
        "Raw cod" to 900,
        "Raw lobster" to 1800,
        "Raw shark" to 2200
    )

    val COOKING_XP_TENTHS: Map<String, Int> = mapOf(
        "Shrimps" to 330,
        "Cooked meat" to 330,
        "Mackerel" to 1200,
        "Cod" to 1500,
        "Lobster" to 2400,
        "Shark" to 4200
    )

    val BURY_XP_TENTHS: Map<String, Int> = mapOf(
        "Bones" to 45,
        "Bat bones" to 53
    )

    val MINING_XP_TENTHS: Map<String, Int> = mapOf("Tin ore" to 75)

    val FIREMAKING_XP_TENTHS: Map<String, Int> = mapOf(
        "Logs" to 400,
        "Oak logs" to 600
    )

    val FLETCHING_XP_TENTHS: Map<String, Int> = mapOf(
        "Oak longbow (unstrung)" to 145,
        "Elder shaft" to 225
    )

    const val PULL_WEED_XP_TENTHS = 40

    const val PLANT_ONION_SEEDS_XP_TENTHS = 95

    const val DIVINATION_PALE_HARVEST_XP_TENTHS = 13
    const val DIVINATION_PALE_MEMORY_TO_XP_TENTHS = 40
    const val DIVINATION_PALE_MEMORY_TO_ENERGY_XP_TENTHS = 10
    const val DIVINATION_PALE_ENHANCED_XP_TENTHS = 50

    const val DIVINATION_GLOWING_HARVEST_XP_TENTHS = 91
    const val DIVINATION_GLOWING_ENRICHED_HARVEST_XP_TENTHS = 186
    const val DIVINATION_GLOWING_MEMORY_TO_ENERGY_XP_TENTHS = 20

    const val DIVINATION_ENHANCED_ENERGY_COST = 5

    const val ARCHAEOLOGY_EXCAVATE_TICK_XP_TENTHS = 62
    const val ARCHAEOLOGY_MATERIAL_FOUND_XP_TENTHS = 590

    const val HUNTER_IMPLING_LOW_XP_TENTHS = 250
    const val HUNTER_IMPLING_HIGH_XP_TENTHS = 2250

    const val SMITHING_PROGRESS_TICK_XP_TENTHS = 33

    const val AGILITY_OBSTACLE_LOW_XP_TENTHS = 20
    const val AGILITY_OBSTACLE_HIGH_XP_TENTHS = 40

    const val FISHING_LOBSTER_XP_TENTHS = 180
    const val FISHING_TUNA_XP_TENTHS = 160

    const val COOKING_SWORDFISH_XP_TENTHS = 280
    const val COOKING_LOBSTER_XP_TENTHS = 240

    const val THIEVING_PICKPOCKET_XP_TENTHS = 26
    const val THIEVING_PICKPOCKET_COINS_MIN = 14
    const val THIEVING_PICKPOCKET_COINS_MAX = 46

    const val FARMING_PICK_DWELLBERRIES_XP_TENTHS = 12
    const val FARMING_CHECK_BUSH_HEALTH_XP_TENTHS = 178

    const val INVENTION_DISASSEMBLE_TICK_XP_TENTHS = 1

    const val HUNTER_IMPLING_MID_XP_TENTHS = 113

}

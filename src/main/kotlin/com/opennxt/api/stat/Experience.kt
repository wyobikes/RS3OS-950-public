package com.opennxt.api.stat

fun maxLevel(stat: Stat): Int {
    requireLoaded(stat)
    return minOf(stat.table.size, stat.def.cap).coerceAtLeast(1)
}

fun xpForLevel(stat: Stat, level: Int): Int {
    requireLoaded(stat)
    val clamped = level.coerceIn(1, maxLevel(stat))
    return stat.table[clamped - 1]
}

fun levelForXp(stat: Stat, xp: Int): Int {
    requireLoaded(stat)
    val table = stat.table
    val max = maxLevel(stat)
    if (xp <= table[0]) return 1

    var low = 1
    var high = max
    while (low < high) {
        val mid = (low + high + 1) / 2
        if (table[mid - 1] <= xp) low = mid else high = mid - 1
    }
    return low
}

fun xpToNextLevel(stat: Stat, xp: Int): Int {
    val level = levelForXp(stat, xp)
    if (level >= maxLevel(stat)) return 0
    return xpForLevel(stat, level + 1) - maxOf(xp, 0)
}

private fun requireLoaded(stat: Stat) {
    if (!stat.loaded) {
        throw IllegalStateException(
            "Stat.$stat has no experience table yet - Stat.reload() must run " +
                    "against the cache before levels can be calculated."
        )
    }
}

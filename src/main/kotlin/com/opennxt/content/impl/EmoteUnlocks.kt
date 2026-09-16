package com.opennxt.content.impl

import com.opennxt.model.vars.SqliteVarBits
import com.opennxt.resources.sqlite.RsDatabase

object EmoteUnlocks {
    data class Unlock(val emoteName: String, val varbit: Int)

    val unlocks: List<Unlock> by lazy {
        if (!RsDatabase.available) emptyList()
        else RsDatabase.queryAll(
            "SELECT n.stringvalue AS name, s.intvalue AS vb FROM struct_param s " +
                "JOIN struct_param n ON n.struct_id = s.struct_id AND n.prop = 1419 " +
                "WHERE s.prop = 1421 ORDER BY s.struct_id"
        ) { Unlock(it.getString("name") ?: "?", it.getInt("vb")) }
    }

    fun varpsForAll(current: (Int) -> Int): Pair<Map<Int, Int>, List<Unlock>> {
        val out = LinkedHashMap<Int, Int>()
        val skipped = ArrayList<Unlock>()
        for (u in unlocks) {
            val def = SqliteVarBits.definition(u.varbit)
            if (def == null || !def.isWellFormed || !def.fits(1)) { skipped += u; continue }
            val varp = def.varId and 0xffff
            val base = out[varp] ?: current(varp)
            out[varp] = def.write(base, 1)
        }
        return out to skipped
    }
}

package com.opennxt.tools.impl

import com.opennxt.Constants
import com.opennxt.api.stat.Stat
import com.opennxt.api.stat.maxLevel
import com.opennxt.api.stat.xpForLevel
import com.opennxt.content.impl.EmoteUnlocks
import com.opennxt.content.impl.LoginVarps
import com.opennxt.filesystem.sqlite.SqliteFilesystem
import com.opennxt.model.account.AccountStore
import com.opennxt.model.account.PlayerSave
import com.opennxt.model.permissions.ModProfiles
import com.opennxt.model.permissions.Rights
import com.opennxt.resources.FilesystemResources

object MakeModAccount {
    @JvmStatic
    fun main(args: Array<String>) {
        if (args.size < 2 || args[0].isBlank() || args[1].isEmpty()) {
            System.err.println("usage: MakeModAccount <username> <password> [--emotes]")
            System.exit(2)
        }
        val username = args[0].trim()
        val password = args[1]
        val fs = SqliteFilesystem(Constants.CACHE_PATH)
        FilesystemResources(fs, Constants.RESOURCE_PATH)
        Stat.reload()

        val store = AccountStore.instance
        val created = store.register(username, password)
        println(if (created) "account '$username' registered" else "account '$username' exists - password unchanged")

        val existing = store.loadSave(username)
        val base = existing ?: PlayerSave.fromNew(username)
        val xp = LinkedHashMap<Stat, Double>()
        for (stat in Stat.values()) xp[stat] = maxOf(base.xp[stat] ?: 0.0, xpForLevel(stat, maxLevel(stat)).toDouble())

        val current: (Int) -> Int = { varp -> base.varps[varp] ?: LoginVarps.table.firstOrNull { it.first == varp }?.second ?: 0 }
        val withEmotes = args.drop(2).any { it == "--emotes" }
        val (emoteVarps, skipped) = if (withEmotes) EmoteUnlocks.varpsForAll(current) else (emptyMap<Int, Int>() to emptyList())
        val varps = LinkedHashMap(base.varps); varps.putAll(emoteVarps)

        val save = base.copy(xp = xp, rights = Rights.MOD.id, varps = varps)
        store.storeSave(username, save)
        val granted = ModProfiles.grant(username, Rights.MOD)
        println("save written: ${Stat.values().size} stats at their caps (" +
            Stat.values().joinToString(", ") { "${it.name.lowercase()} ${maxLevel(it)}" } + ")")
        if (!withEmotes) println("emotes: not written (pass --emotes; not compatible with the 950 client)")
        else println("emotes: ${EmoteUnlocks.unlocks.size - skipped.size} unlocked over ${emoteVarps.size} varps" +
            (if (skipped.isNotEmpty()) "; ${skipped.size} skipped: ${skipped.joinToString { it.emoteName }}" else ""))
        println("rights: mod (2); mods.json ${if (granted) "updated at ${ModProfiles.path}" else "not written (grant refused)"}")
        println("developer console commands: cosmetic <item>, max, item, npc, boss, tele, lodestone")
        store.close()
        System.exit(if (granted) 0 else 1)
    }
}

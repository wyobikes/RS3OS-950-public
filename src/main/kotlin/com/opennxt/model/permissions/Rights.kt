package com.opennxt.model.permissions

import com.opennxt.Constants
import com.google.gson.JsonParser
import mu.KotlinLogging
import java.nio.file.Files

enum class Rights(val id: Int) {
    PLAYER(0),
    MOD(2);

    companion object {
        fun of(id: Int): Rights = if (id >= MOD.id) MOD else PLAYER
    }
}

object Powers {
    const val SPAWN_ITEM = "spawn-item"
    const val SPAWN_NPC = "spawn-npc"
    const val TELEPORT = "teleport"
    const val LODESTONE = "lodestone"
    const val GRANT = "grant"

    val ALL = setOf(SPAWN_ITEM, SPAWN_NPC, TELEPORT, LODESTONE, GRANT)
}

object ModProfiles {
    private val logger = KotlinLogging.logger { }

    data class Profile(val username: String, val rights: Rights, val powers: Set<String>, val note: String?)

    val path get() = Constants.DATA_PATH.resolve("config").resolve("mods.json")

    @Volatile
    private var profiles: Map<String, Profile> = emptyMap()

    @Volatile
    private var loaded = false

    fun of(username: String): Profile? {
        if (!loaded) reload()
        return profiles[username.lowercase().trim()]
    }

    fun size(): Int {
        if (!loaded) reload()
        return profiles.size
    }

    fun reload(): Int {
        loaded = true
        val file = path
        if (!Files.isRegularFile(file)) {
            profiles = emptyMap()
            logger.info { "ModProfiles: no $file - no game mods are configured (this is the default)" }
            return 0
        }
        val out = LinkedHashMap<String, Profile>()
        try {
            val root = JsonParser().parse(Files.newBufferedReader(file)).asJsonObject
            val arr = root.getAsJsonArray("mods") ?: throw IllegalArgumentException("no 'mods' array")
            for (el in arr) {
                val o = el.asJsonObject
                val username = o.get("username")?.asString?.lowercase()?.trim()
                if (username.isNullOrEmpty()) {
                    logger.warn { "ModProfiles: an entry has no username - skipped" }
                    continue
                }
                val rights = Rights.of(o.get("rights")?.asInt ?: Rights.MOD.id)
                val powers = o.getAsJsonArray("powers")?.map { it.asString.lowercase().trim() }?.toSet()
                    ?: Powers.ALL
                out[username] = Profile(username, rights, powers, o.get("note")?.asString)
            }
        } catch (e: Exception) {
            logger.warn(e) { "ModProfiles: $file could not be read - NO mods are in force until it is fixed" }
            profiles = emptyMap()
            return 0
        }
        profiles = out
        logger.info {
            "ModProfiles: ${out.size} profile(s) from $file - " +
                out.values.joinToString { "${it.username}=${it.rights}(${it.powers.size} powers)" }
        }
        return out.size
    }

    fun grant(username: String, rights: Rights, powers: Set<String> = Powers.ALL): Boolean {
        if (!loaded) reload()
        val key = username.lowercase().trim()
        val merged = LinkedHashMap(profiles)
        merged[key] = Profile(key, rights, powers, "granted in-game")
        return try {
            Files.createDirectories(path.parent)
            val sb = StringBuilder("{\n  \"mods\": [\n")
            merged.values.forEachIndexed { i, p ->
                sb.append("    { \"username\": \"").append(p.username).append("\", \"rights\": ").append(p.rights.id)
                    .append(", \"powers\": [").append(p.powers.joinToString { "\"$it\"" }).append("]")
                p.note?.let { sb.append(", \"note\": \"").append(it.replace("\"", "'")).append("\"") }
                sb.append(" }").append(if (i == merged.size - 1) "\n" else ",\n")
            }
            sb.append("  ]\n}\n")
            Files.write(path, sb.toString().toByteArray())
            profiles = merged
            logger.info { "ModProfiles: $key is now ${rights} - written to $path" }
            true
        } catch (e: Exception) {
            logger.warn(e) { "ModProfiles: could not write $path - $key was NOT granted" }
            false
        }
    }
}

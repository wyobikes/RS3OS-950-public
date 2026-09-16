package com.opennxt.config

import com.moandjiezana.toml.Toml
import com.opennxt.Constants

class ServerConfig : TomlConfig() {
    companion object {
        val DEFAULT_PATH = Constants.CONFIG_PATH.resolve("server.toml")

        const val SUPPORTED_BUILD = 949

        val EXPERIMENTAL_BUILDS = setOf(950)

        fun experimentalBuildOverride(): Int? =
            System.getProperty("opennxt.prot.experimentalBuild")?.trim()?.toIntOrNull()
    }

    data class Ports(var game: Int = 43594, var http: Int = 80, var https: Int = 443)

    var ports = Ports()
    var hostname = "127.0.0.1"
    var configUrl = "http://127.0.0.1/jav_config.ws?binaryType=2"

    var build = -1

    fun requireSupportedBuild(source: String) {
        if (build == SUPPORTED_BUILD) return
        if (build in EXPERIMENTAL_BUILDS && experimentalBuildOverride() == build) return
        val what = if (build == -1)
            "no `build` key was found in $source"
        else
            "$source says build $build"
        throw IllegalStateException(
            "Unsupported build: $what. Set `build = $SUPPORTED_BUILD` in $source; " +
                "experimental builds $EXPERIMENTAL_BUILDS also need -Dopennxt.prot.experimentalBuild=<build>" +
                (experimentalBuildOverride()?.let { " (currently $it)" } ?: " (not set)") +
                "."
        )
    }

    override fun save(map: MutableMap<String, Any>) {
        map["networking"] = mapOf(
            "ports" to mapOf(
                "game" to ports.game,
                "http" to ports.http,
                "https" to ports.https
            )
        )
        map["hostname"] = hostname
        map["configUrl"] = configUrl
        map["build"] = build
    }

    override fun load(toml: Toml) {
        hostname = toml.getString("hostname", hostname)
        configUrl = toml.getString("configUrl", configUrl)
        build = toml.getLong("build", build.toLong()).toInt()

        val networking = toml.getTable("networking")
        if (networking != null) {
            val ports = networking.getTable("ports")
            if (ports != null) {
                this.ports.game = ports.getLong("game", this.ports.game.toLong()).toInt()
                this.ports.http = ports.getLong("http", this.ports.http.toLong()).toInt()
                this.ports.https = ports.getLong("https", this.ports.https.toLong()).toInt()
            }
        }
    }
}

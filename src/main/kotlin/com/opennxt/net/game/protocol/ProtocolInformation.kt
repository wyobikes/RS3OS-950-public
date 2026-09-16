package com.opennxt.net.game.protocol

import com.opennxt.OpenNXT
import com.opennxt.config.TomlConfig
import com.opennxt.net.game.PacketRegistry
import mu.KotlinLogging
import java.nio.file.Files
import java.nio.file.Path
import kotlin.system.exitProcess

class ProtocolInformation(val path: Path) {
    private val logger = KotlinLogging.logger {  }
    lateinit var clientProtSizes: Opcode2SizeConfig
    lateinit var serverProtSizes: Opcode2SizeConfig
    lateinit var clientProtNames: Name2OpcodeConfig
    lateinit var serverProtNames: Name2OpcodeConfig

    var effectiveBuild: Int = -1
        private set

    fun load() {
        logger.info { "Loading protocol information from $path" }

        val configured = OpenNXT.config.build
        val borrow = System.getProperty("opennxt.prot.borrowBuild")?.toIntOrNull()
        var readFrom = path
        effectiveBuild = configured

        if (!Files.exists(path.resolve("serverProtNames.toml")) && borrow != null) {
            val alt = path.resolveSibling(borrow.toString())
            if (!Files.exists(alt.resolve("serverProtNames.toml"))) {
                logger.error { "-Dopennxt.prot.borrowBuild=$borrow, but $alt has no serverProtNames.toml" }
                exitProcess(1)
            }
            readFrom = alt
            effectiveBuild = borrow
            repeat(3) {
                logger.warn {
                    "Using build $borrow's protocol tables for build $configured; in-game opcodes will be wrong. " +
                        "Restore data/prot/$configured to fix."
                }
            }
        }

        fun read(file: String): Any = try {
            TomlConfig.load<Opcode2SizeConfig>(readFrom.resolve(file), saveAfterLoad = false, mustExist = true)
        } catch (e: Exception) {
            logger.error(e) { "Protocol information not found for build $configured." }
            logger.error { " Looked in: ${readFrom.resolve(file)}" }
            if (borrow == null)
                logger.error {
                    " data/prot/ holds: " +
                        (path.parent?.toFile()?.list()?.sorted()?.joinToString(", ") ?: "(unreadable)") +
                        " (-Dopennxt.prot.borrowBuild=<build> loads another build's tables)"
                }
            exitProcess(1)
        }

        @Suppress("UNCHECKED_CAST")
        run {
            clientProtSizes = read("clientProtSizes.toml") as Opcode2SizeConfig
            serverProtSizes = read("serverProtSizes.toml") as Opcode2SizeConfig
        }
        clientProtNames = try {
            TomlConfig.load(readFrom.resolve("clientProtNames.toml"), saveAfterLoad = false, mustExist = true)
        } catch (e: Exception) {
            logger.error(e) { "clientProtNames.toml missing at ${readFrom.resolve("clientProtNames.toml")}" }
            exitProcess(1)
        }
        serverProtNames = try {
            TomlConfig.load(readFrom.resolve("serverProtNames.toml"), saveAfterLoad = false, mustExist = true)
        } catch (e: Exception) {
            logger.error(e) { "serverProtNames.toml missing at ${readFrom.resolve("serverProtNames.toml")}" }
            exitProcess(1)
        }

        logger.info { "Protocol tables in use: build $effectiveBuild (server build $configured)" }
        refreshPacketCodecs()
    }

    fun refreshPacketCodecs() {
        logger.info { "Refreshing packet codecs" }

        if (!Files.exists(path.resolve("clientProt")))
            Files.createDirectories(path.resolve("clientProt"))

        if (!Files.exists(path.resolve("serverProt")))
            Files.createDirectories(path.resolve("serverProt"))

        PacketRegistry.reload()
    }
}

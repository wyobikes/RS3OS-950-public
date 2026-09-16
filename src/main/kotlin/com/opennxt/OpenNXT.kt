package com.opennxt

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.opennxt.api.stat.Stat
import com.opennxt.config.RsaConfig
import com.opennxt.content.impl.Dialogue
import com.opennxt.content.impl.DialogueWiring
import com.opennxt.content.impl.Banks
import com.opennxt.content.impl.BanksWiring
import com.opennxt.content.impl.Doors
import com.opennxt.model.map.LocClipping
import com.opennxt.content.impl.Ladders
import com.opennxt.content.impl.Shops
import com.opennxt.content.impl.Skilling
import com.opennxt.content.impl.SkillingWiring
import com.opennxt.content.impl.Stairs
import com.opennxt.content.impl.Gatherables
import com.opennxt.content.impl.Searchables
import com.opennxt.config.ServerConfig
import com.opennxt.config.TomlConfig
import com.opennxt.filesystem.ChecksumTable
import com.opennxt.filesystem.Container
import com.opennxt.filesystem.Filesystem
import com.opennxt.filesystem.prefetches.PrefetchTable
import com.opennxt.filesystem.sqlite.SqliteFilesystem
import com.opennxt.login.LoginThread
import com.opennxt.model.commands.CommandRepository
import com.opennxt.model.lobby.Lobby
import com.opennxt.model.tick.TickEngine
import com.opennxt.model.world.World
import com.opennxt.net.RSChannelInitializer
import com.opennxt.net.game.protocol.ProtocolInformation
import com.opennxt.net.DiagnosticLog
import com.opennxt.net.http.HttpServer
import com.opennxt.net.proxy.ProxyConfig
import com.opennxt.net.proxy.ProxyConnectionFactory
import com.opennxt.net.proxy.ProxyConnectionHandler
import com.opennxt.resources.FilesystemResources
import com.opennxt.resources.sqlite.RsDatabase
import io.netty.bootstrap.ServerBootstrap
import io.netty.channel.ChannelOption
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.nio.NioServerSocketChannel
import mu.KotlinLogging
import java.io.FileNotFoundException
import java.nio.file.Files
import kotlin.system.exitProcess
import com.opennxt.model.map.CollisionMap

object OpenNXT : CliktCommand(name = "run-server", help = "Launches the OpenNXT server)") {
    val skipHttpFileVerification by option(help = "Skips file verification when http server starts").flag(default = false)
    val enableProxySupport by option(help = "Enables proxy support. Disable this on live or when you won't use it.").flag(
        default = false
    )

    private val logger = KotlinLogging.logger {}

    lateinit var config: ServerConfig
    lateinit var rsaConfig: RsaConfig
    lateinit var proxyConfig: ProxyConfig

    lateinit var http: HttpServer

    lateinit var filesystem: Filesystem
    lateinit var resources: FilesystemResources
    lateinit var prefetches: PrefetchTable
    lateinit var checksumTable: ByteArray
    lateinit var httpChecksumTable: ByteArray

    lateinit var proxyConnectionFactory: ProxyConnectionFactory
    lateinit var proxyConnectionHandler: ProxyConnectionHandler
    lateinit var protocol: ProtocolInformation
    lateinit var tickEngine: TickEngine

    lateinit var world: World
    lateinit var lobby: Lobby

    lateinit var commands: CommandRepository

    private val bootstrap = ServerBootstrap()

    private fun loadConfigurations() {
        logger.info { "Loading configuration files from ${Constants.CONFIG_PATH}" }
        config = TomlConfig.load(Constants.CONFIG_PATH.resolve("server.toml"))

        ServerConfig.experimentalBuildOverride()?.let { requested ->
            if (requested !in ServerConfig.EXPERIMENTAL_BUILDS) {
                logger.error { "-Dopennxt.prot.experimentalBuild=$requested names a build that is not" }
                logger.error { " experimental here. Known experimental builds: ${ServerConfig.EXPERIMENTAL_BUILDS}." }
                exitProcess(1)
            }
            logger.warn { "============== EXPERIMENTAL BUILD ==============" }
            logger.warn { " -Dopennxt.prot.experimentalBuild=$requested is set." }
            logger.warn { " server.toml says build ${config.build}; running as $requested instead." }
            logger.warn { " Some packet layouts for this build are unverified." }
            logger.warn { "================================================" }
            config.build = requested
        }

        config.requireSupportedBuild(Constants.CONFIG_PATH.resolve("server.toml").toString())

        System.getProperty("opennxt.prot.borrowBuild")?.let { borrowed ->
            logger.warn { "================= BUILD MISMATCH =================" }
            logger.warn { " -Dopennxt.prot.borrowBuild=$borrowed is set." }
            logger.warn { " server.toml says build ${config.build}, but protocol tables" }
            logger.warn { " will be read from build $borrowed instead." }
            logger.warn { " Remove the flag for a normal run." }
            logger.warn { "==================================================" }
        }

        rsaConfig = try {
            TomlConfig.load(RsaConfig.DEFAULT_PATH, mustExist = true)
        } catch (e: FileNotFoundException) {
            logger.info { "Could not find RSA config: $e. Please run `run-tool rsa-key-generator`" }
            exitProcess(1)
        }
        proxyConfig = TomlConfig.load(Constants.CONFIG_PATH.resolve("proxy.toml"))
    }

    fun reloadContent() {
        Stat.reload()

        val locCount = RsDatabase.maxId("locs")
        if (RsDatabase.available && locCount > 0) {
            if (LocClipping.enabled) {
                val warmed = LocClipping.warm()
                logger.info {
                    "loc clipping: warmed $warmed loc clip definitions"
                }
            } else {
                logger.warn {
                    "loc clipping is disabled (-Dopennxt.experiment.locClipping=false); walls and fences will not block movement"
                }
            }
            Doors.install()

            com.opennxt.model.map.BridgeFlags.init(filesystem as SqliteFilesystem)
            logger.info { "bridge flags: initialized, loaded squares will be cached on demand" }

            Ladders.install()
            Stairs.install()
            Banks.install()
            com.opennxt.content.impl.Obstacles.install()
            Searchables.install()
            Gatherables.install()
            BanksWiring.install()
            Shops.install()
            try {
                val talkers = Dialogue.install()
                DialogueWiring.install()
                logger.info { "content: dialogue installed across $talkers npc ids" }
            } catch (t: Throwable) {
                logger.error(t) {
                    "content: dialogue failed to install; 'Talk to' will do nothing"
                }
            }

            try {
                val skilling = Skilling.install()
                SkillingWiring.install()
                logger.info { "content: skilling installed - $skilling" }
            } catch (t: Throwable) {
                logger.error(t) {
                    "content: skilling failed to install; trees and rocks will do nothing"
                }
            }

            try {
                val fishing = com.opennxt.content.impl.Fishing.install()
                com.opennxt.content.impl.FishingWiring.install()
                logger.info { "content: fishing installed - $fishing spot action(s)" }
            } catch (t: Throwable) {
                logger.error(t) { "content: fishing failed to install" }
            }
            try {
                val thieving = com.opennxt.content.impl.Thieving.install()
                com.opennxt.content.impl.ThievingWiring.install()
                logger.info { "content: thieving installed - 'Pickpocket' across $thieving npc id(s)" }
            } catch (t: Throwable) {
                logger.error(t) { "content: thieving failed to install" }
            }
            try {
                com.opennxt.content.impl.FiremakingWiring.install()
                logger.info { "content: firemaking installed" }
            } catch (t: Throwable) {
                logger.error(t) { "content: firemaking failed to install" }
            }
            try {
                val cooking = com.opennxt.content.impl.CookingWiring.install()
                logger.info { "content: cooking installed - $cooking fire loc(s)" }
            } catch (t: Throwable) {
                logger.error(t) { "content: cooking failed to install" }
            }
            try {
                com.opennxt.content.impl.SmithingWiring.install()
                logger.info { "content: smithing installed" }
            } catch (t: Throwable) {
                logger.error(t) { "content: smithing failed to install" }
            }
            try {
                com.opennxt.content.impl.FletchingWiring.install()
                logger.info { "content: fletching installed" }
            } catch (t: Throwable) {
                logger.error(t) { "content: fletching failed to install" }
            }
            try {
                com.opennxt.content.impl.BuryWiring.install()
                logger.info { "content: burying installed" }
            } catch (t: Throwable) {
                logger.error(t) { "content: burying failed to install" }
            }
            try {
                if (com.opennxt.content.impl.MoneyPouch.enabled) com.opennxt.content.impl.MoneyPouch.install()
                logger.info { "content: money pouch ${if (com.opennxt.content.impl.MoneyPouch.enabled) "installed" else "disabled (-Dopennxt.content.moneyPouch=off)"}" }
            } catch (t: Throwable) {
                logger.error(t) { "content: money pouch failed to install" }
            }
            try {
                com.opennxt.content.impl.ToolBeltWiring.install()
                logger.info { "content: tool belt installed" }
                com.opennxt.content.impl.RunToggle.install()
                logger.info { com.opennxt.content.impl.MakeXPanel.describe() }
                logger.info { com.opennxt.content.impl.Lodestones.describe() }
                logger.info { com.opennxt.content.impl.Teleports.describe() }
                logger.info { com.opennxt.content.impl.ToolBeltPanel.describe() }
            } catch (t: Throwable) {
                logger.error(t) { "content: tool belt failed to install" }
            }
            try {
                val skills = com.opennxt.content.skills.SkillsWiring.install()
                logger.info { "content: $skills" }
            } catch (t: Throwable) {
                logger.error(t) { "content: skills failed to install" }
            }
        } else if (RsDatabase.available) {
            logger.warn {
                "Definition database has no loc definitions; skipping content modules"
            }
        }

        try {
            val abilities = com.opennxt.content.ability.AbilityDefinitions.current()
            logger.info {
                "content: ${abilities.byStruct.size} abilities loaded, GCD ${abilities.gcdTicks} " +
                    "tick(s), ${abilities.problems.size} problem(s)"
            }
        } catch (t: Throwable) {
            logger.error(t) { "content: ability definitions failed to load; will retry on first use" }
        }
        com.opennxt.content.ability.AbilityActivation.install()
        com.opennxt.content.combat.RangedAmmo.install()
        com.opennxt.content.combat.CombatSpells.install()
        com.opennxt.content.combat.Conjures.install()
        com.opennxt.content.combat.Revolution.install()
        com.opennxt.content.combat.CombatVisuals.install()
        runCatching { val n = com.opennxt.content.impl.PrayerBook.rows.size; logger.info { "content: $n prayers (${if (com.opennxt.content.impl.PrayerBook.enabled) "enabled" else "disabled"})" } }
            .onFailure { logger.error(it) { "content: prayer table failed to load" } }
    }

    override fun run() {
        logger.info { "Starting OpenNXT" }
        loadConfigurations()

        if (enableProxySupport) {
            logger.warn { "---------------- WARNING ----------------" }
            logger.warn { " You are running in proxy-enabled mode." }
            logger.warn { " Disable this in production environments" }
            logger.warn { " or when you are not going to use this." }
            logger.warn { "" }
            logger.warn { " Remove flag '--enable-proxy-support'." }
            logger.warn { " to disable." }
            logger.warn { "---------------- WARNING ----------------" }

            logger.info { "Setting up proxy connection factory" }
            proxyConnectionFactory = ProxyConnectionFactory()
            proxyConnectionHandler = ProxyConnectionHandler()
        }

        val protPath = Constants.PROT_PATH.resolve(config.build.toString())
        if (!Files.exists(protPath) && System.getProperty("opennxt.prot.borrowBuild") == null) {
            logger.error { "Protocol information not found for build ${config.build}." }
            logger.error { " Looked in: $protPath" }
            logger.error {
                " data/prot holds: " +
                    (Constants.PROT_PATH.toFile().list()?.sorted()?.joinToString(", ") ?: "(unreadable)")
            }
            logger.error { " Use -Dopennxt.prot.borrowBuild=<build> to load another build's tables (login screen only)." }
            exitProcess(1)
        }

        protocol = ProtocolInformation(protPath)
        protocol.load()

        logger.info { "Setting up HTTP server" }
        http = HttpServer(config)
        http.init(skipHttpFileVerification)

        logger.info { "Opening filesystem from ${Constants.CACHE_PATH}" }
        filesystem = SqliteFilesystem(Constants.CACHE_PATH)

        run {
            val discovered = (0..254).filter { filesystem.exists(it, 0) || runCatching { filesystem.readReferenceTable(it) != null }.getOrDefault(false) }
            val unreadable = (0..254).filter { i ->
                runCatching { filesystem.readReferenceTable(i) }.getOrNull() == null &&
                    runCatching { filesystem.exists(i, 0) }.getOrDefault(false)
            }
            logger.info { "Cache check: ${discovered.size} indices discovered, ${unreadable.size} unreadable" }
            if (unreadable.isNotEmpty()) {
                logger.warn { "-------------------------------------------------------------" }
                logger.warn { " Cache incomplete: these indices have no readable reference table" }
                logger.warn { " and cannot be served: $unreadable" }
                logger.warn { " Point -Dopennxt.cache at a private copy, not a live client's cache." }
                logger.warn { "-------------------------------------------------------------" }
            }
        }

        logger.info { "Generating prefetch table" }
        prefetches = PrefetchTable.of(filesystem)

        logger.info { "Generating & encoding checksum tables" }
        checksumTable = Container.wrap(
            ChecksumTable.create(filesystem, false)
                .encode(rsaConfig.js5.modulus, rsaConfig.js5.exponent)
        ).array()
        httpChecksumTable = Container.wrap(
            ChecksumTable.create(filesystem, true)
                .encode(rsaConfig.js5.modulus, rsaConfig.js5.exponent)
        ).array()

        logger.info { "Setting up filesystem resource manager" }
        resources = FilesystemResources(filesystem, Constants.RESOURCE_PATH)

        logger.info { "Loading Interface Slots" }
        val slotReload = com.opennxt.content.interfaces.InterfaceSlot.reload()
        logger.info { "Loaded ${slotReload.mapped} Interface Slots, ${slotReload.unmapped.size} unmapped" }

        logger.info { "Loading sequences from cache" }
        com.opennxt.model.definitions.SeqDefinitions.load(filesystem)

        logger.info { "Setting up command repository" }
        commands = CommandRepository()

        logger.info { "Starting js5 thread" }
        Js5Thread.start()

        logger.info { "Starting login thread" }
        LoginThread.start()

        logger.info { "Starting tick engine" }
        tickEngine = TickEngine()

        logger.info { "Instantiating game world" }
        world = World()

        logger.info { "Instantiating lobby" }
        lobby = Lobby()
        tickEngine.submitTickable(lobby)

        if (enableProxySupport) {
            logger.info { "Registering proxy connection handler to tick engine" }
            tickEngine.submitTickable(proxyConnectionHandler)
        }

        logger.info { "Reloading content-related things" }
        reloadContent()

        logger.info { "Loading collision map" }
        if (CollisionMap.available) {
            logger.info { "Collision ready - ${CollisionMap.loadedSquares()} map squares" }
        } else {
            logger.warn { "No collision data (is rs3.sqlite missing?); pathfinding will reject all routes" }
        }

        if (RsDatabase.available) {
            val populated = world.npcs.populate()
            if (System.getProperty("opennxt.fishing.spawn") != "off") {
                val tiles = com.opennxt.content.impl.Fishing.SPOT_FLAG_TILES.distinct()
                var spots = 0
                for (tile in tiles) {
                    runCatching { spots += world.npcs.spawnAt(com.opennxt.content.impl.Fishing.CRAYFISH.npcId, tile) }
                        .onFailure { logger.warn(it) { "could not spawn a fishing spot at $tile" } }
                }
                logger.info {
                    "content: $spots fishing spot(s) spawned at ${tiles.joinToString()}"
                }
            }
            com.opennxt.model.combat.NpcCombatDefs.load()
            com.opennxt.model.combat.NpcAnimTable.load()
            com.opennxt.model.drops.DropData.load()
            com.opennxt.model.world.WorldSpawns.load()
            logger.info { com.opennxt.model.world.WorldSpawns.bootLine() }
            logger.info { com.opennxt.model.world.SpawnActivation.warm() }
            logger.info { com.opennxt.model.world.NpcMorph.statusLine() }
            logger.info {
                if (com.opennxt.model.world.SpawnActivation.enabled) {
                    "World spawn activation enabled: " +
                        "${com.opennxt.model.world.WorldSpawns.all().count { it.activatable }} rows " +
                        "(${com.opennxt.model.world.WorldSpawns.countOf("ref")} ref, " +
                        "${com.opennxt.model.world.WorldSpawns.countOf("sampled")} sampled) over " +
                        "${com.opennxt.model.world.WorldSpawns.distinctSquares()} squares, radius " +
                        "${com.opennxt.model.world.SpawnActivation.radius}, " +
                        "${com.opennxt.model.world.SpawnActivation.MAX_SQUARES_PER_TICK} squares/tick, " +
                        "despawn after ${com.opennxt.model.world.SpawnActivation.DEACTIVATE_GRACE_TICKS} ticks"
                } else {
                    "World spawn activation disabled (-Dopennxt.experiment.npcs.worldspawns=off)"
                }
            }
            com.opennxt.model.combat.NpcBossData.load()
            logger.info { com.opennxt.model.combat.NpcBossData.bootLine() }
            logger.info {
                val placed = com.opennxt.model.combat.NpcBossData.placedCountsByTier()
                "Boss encounters: specials " +
                    (if (com.opennxt.model.combat.BossEncounters.specialsEnabled) "on" else "off") +
                    ", phases " +
                    (if (com.opennxt.model.combat.BossEncounters.phasesEnabled) "on" else "off") +
                    ", respawn " +
                    (if (com.opennxt.model.combat.BossEncounters.respawnEnabled) "on" else "off") +
                    "; placed " +
                    placed.entries.joinToString(", ") { "${it.key}=${it.value}" } +
                    " (${placed.values.sum()} of ${com.opennxt.model.combat.NpcBossData.all().size}), " +
                    "${com.opennxt.model.combat.NpcBossData.all().count { !it.placed }} available via ::boss"
            }
            logger.info {
                "Combat data loaded: definitions ${com.opennxt.model.combat.NpcCombatDefs.ids().size}, " +
                    "animations ${com.opennxt.model.combat.NpcAnimTable.animNpcIds().size} ids, " +
                    "drop tables ${com.opennxt.model.drops.DropData.refMonsters().size} monsters"
            }
            logger.info {
                if (com.opennxt.model.world.WorldNpcs.spawnsEnabled) {
                    "World populated with $populated npcs from " +
                        "${com.opennxt.model.world.NpcSpawnData.spawns().size} spawn records " +
                        "(lifepoints sources: ${world.npcs.lifepointProvenanceBreakdown()}, " +
                        "layers: ${world.npcs.lifepointLayerBreakdown()})"
                } else {
                    "Cache npc spawns are disabled " +
                        "(-Dopennxt.experiment.npcs.spawns=false); world has $populated npc(s)"
                }
            }
            world.npcs.combatDemoNpc()?.let { demo ->
                logger.info {
                    "Combat demo npc placed: ${demo.name ?: "npc${demo.gameId}"} (${demo.gameId}) at " +
                        "(${demo.location.x},${demo.location.y},plane ${demo.location.plane}) slot " +
                        "${demo.infoIndex}, lp ${demo.currentLifepoints}/${demo.lifepoints?.value} " +
                        "(${demo.lifepoints?.provenance}), wander " +
                        "${if (com.opennxt.model.world.WorldNpcs.combatDemoWander) "on" else "off"} " +
                        "(-Dopennxt.experiment.combat.demospawn=off to disable)"
                }
            }

            logger.info { com.opennxt.model.combat.SeedData.VARIANT_PROVENANCE }

            logger.info { com.opennxt.model.entity.movement.Movement.PROVENANCE }

            com.opennxt.model.combat.PlayerCombat.logProvenanceOnce()
        } else {
            logger.warn { "No rs3.sqlite - world starts with no npcs" }
        }

        tickEngine.submitTickable(world)

        logger.info { "Starting network" }
        bootstrap.group(NioEventLoopGroup())
            .channel(NioServerSocketChannel::class.java)
            .childHandler(RSChannelInitializer())
            .childOption(ChannelOption.SO_REUSEADDR, true)
            .childOption(ChannelOption.TCP_NODELAY, true)
            .childOption(ChannelOption.CONNECT_TIMEOUT_MILLIS, 30_000)
            .childOption(ChannelOption.WRITE_BUFFER_WATER_MARK, com.opennxt.net.OutboundBackpressure.waterMark())

        logger.info { "Binding game server to 0.0.0.0:${config.ports.game}" }
        val result = bootstrap.bind("0.0.0.0", config.ports.game).await()
        if (!result.isSuccess) {
            logger.error(result.cause()) { "Failed to bind to 0.0.0.0:${config.ports.game}" }
            exitProcess(1)
        }
        logger.info { "Game server bound to 0.0.0.0:${config.ports.game}" }

        http.bind()

        DiagnosticLog.arm()
        DiagnosticLog.currentFile()?.let { path ->
            logger.info { "Diagnostic recorder writing to $path" }
        }
    }
}

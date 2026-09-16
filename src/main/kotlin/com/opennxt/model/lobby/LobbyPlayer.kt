package com.opennxt.model.lobby

import com.opennxt.api.stat.Stat
import com.opennxt.api.stat.StatContainer
import com.opennxt.content.impl.Banks
import com.opennxt.impl.stat.PlayerStatContainer
import com.opennxt.model.account.AccountStore
import com.opennxt.model.account.PlayerSave
import com.opennxt.model.InterfaceHash
import com.opennxt.model.entity.BasePlayer
import com.opennxt.model.entity.player.InterfaceManager
import com.opennxt.model.worldlist.WorldList
import com.opennxt.net.ConnectedClient
import com.opennxt.net.game.GamePacket
import com.opennxt.net.game.clientprot.EventAppletFocus
import com.opennxt.net.game.clientprot.EventCameraPosition
import com.opennxt.net.game.clientprot.EventMouseClick
import com.opennxt.net.game.clientprot.ClientCheat
import com.opennxt.net.game.clientprot.ChatSetMode
import com.opennxt.net.game.clientprot.MessagePrivate
import com.opennxt.net.game.clientprot.UndecodedClientPacket
import com.opennxt.net.game.clientprot.WindowStatus
import com.opennxt.net.game.clientprot.WorldlistFetch
import com.opennxt.net.game.handlers.EventAppletFocusHandler
import com.opennxt.net.game.handlers.EventCameraPositionHandler
import com.opennxt.net.game.handlers.EventMouseClickHandler
import com.opennxt.net.game.handlers.ClientCheatHandler
import com.opennxt.net.game.handlers.ChatSetModeHandler
import com.opennxt.net.game.handlers.MessagePrivateHandler
import com.opennxt.net.game.handlers.NoTimeoutHandler
import com.opennxt.net.game.handlers.UndecodedClientPacketHandler
import com.opennxt.net.game.handlers.WindowStatusHandler
import com.opennxt.net.game.handlers.WorldlistFetchHandler
import com.opennxt.net.game.pipeline.GamePacketHandler
import com.opennxt.net.game.serverprot.*
import com.opennxt.net.game.serverprot.ifaces.IfOpenSub
import com.opennxt.net.game.serverprot.RunClientScript
import com.opennxt.net.game.serverprot.variables.ClientSetvarcLarge
import com.opennxt.net.game.serverprot.variables.ClientSetvarcSmall
import com.opennxt.net.game.serverprot.variables.ClientSetvarcstrSmall
import com.opennxt.net.game.serverprot.variables.ResetClientVarcache
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap
import com.opennxt.net.game.pipeline.InboundCensus
import mu.KotlinLogging
import kotlin.reflect.KClass

val DEFAULT_LOBBY_MOUNTS: List<Pair<Int, Int>> = listOf(
    907 to 44, 910 to 45, 909 to 46, 912 to 48, 589 to 47, 911 to 49, 914 to 144, 915 to 145, 913 to 146, 815 to 154,
    803 to 148, 822 to 149, 825 to 100, 821 to 101, 808 to 99, 820 to 151, 811 to 147, 826 to 51, 801 to 139
)

class LobbyPlayer(
    client: ConnectedClient,
    name: String,
    loadedSave: PlayerSave? = null
) : BasePlayer(client, name) {
    companion object {
        const val REPLAYED_VARP_COUNT = 1385
    }

    private val handlers =
        Object2ObjectOpenHashMap<KClass<out GamePacket>, GamePacketHandler<in BasePlayer, out GamePacket>>()
    private val logger = KotlinLogging.logger { }

    val save: PlayerSave = loadedSave ?: AccountStore.instance.loadSave(name) ?: PlayerSave.fromNew(name)

    override val interfaces: InterfaceManager = InterfaceManager(this)

    override val stats: StatContainer = PlayerStatContainer(this, save.xp)

    var restoreSkippedForWorldSession: Boolean = false
        private set

    private val worldAtLogin: com.opennxt.model.world.World? = runCatching { com.opennxt.OpenNXT.world }.getOrNull()
    private val worldGenerationAtLogin: Long = worldAtLogin?.sessionGeneration(name) ?: 0L

    fun worldSessionOwnsSave(): Boolean {
        if (restoreSkippedForWorldSession) return true
        val world = runCatching { com.opennxt.OpenNXT.world }.getOrNull() ?: return false
        val baseline = if (world === worldAtLogin) worldGenerationAtLogin else 0L
        return world.sessionGeneration(name) != baseline
    }

    init {
        val world = runCatching { com.opennxt.OpenNXT.world }.getOrNull()
        if (world != null && world.isOnline(name)) {
            restoreSkippedForWorldSession = true
            logger.warn {
                "Lobby login for '$name' while the account is in the world; not restoring the stored bank"
            }
        } else {
            Banks.restoreBank(name, save.bankItems(), save.bankTabs)
            com.opennxt.content.impl.MetalBanks.restore(name, save.metalBankItems())
        }
    }

    fun toSave(): PlayerSave {
        val xp = LinkedHashMap<Stat, Double>()
        Stat.values().forEach { stat -> xp[stat] = stats.get(stat).experience }
        return save.copy(
            xp = xp,
            bank = PlayerSave.bankContents(Banks.bankForAccount(name)),
            bankTabs = Banks.bankForAccount(name).savedTabs(),
            metalBank = PlayerSave.metalBankContents(com.opennxt.content.impl.MetalBanks.savedContents(name)),
        )
    }

    val worldList = WorldList(WorldList.demoEntries())

    init {
        handlers[NoTimeout::class] = NoTimeoutHandler
        handlers[ClientCheat::class] = ClientCheatHandler
        handlers[WorldlistFetch::class] = WorldlistFetchHandler

        handlers[WindowStatus::class] = WindowStatusHandler

        handlers[com.opennxt.net.game.clientprot.VarcTransmit::class] =
            com.opennxt.net.game.handlers.VarcTransmitHandler

        handlers[EventMouseClick::class] = EventMouseClickHandler

        handlers[com.opennxt.net.game.clientprot.IfButton1::class] =
            com.opennxt.net.game.handlers.IfButtonNHandler
        handlers[com.opennxt.net.game.clientprot.IfButton2::class] =
            com.opennxt.net.game.handlers.IfButtonNHandler
        handlers[com.opennxt.net.game.clientprot.IfButton3::class] =
            com.opennxt.net.game.handlers.IfButtonNHandler
        handlers[com.opennxt.net.game.clientprot.IfButton4::class] =
            com.opennxt.net.game.handlers.IfButtonNHandler
        handlers[com.opennxt.net.game.clientprot.IfButton5::class] =
            com.opennxt.net.game.handlers.IfButtonNHandler
        handlers[com.opennxt.net.game.clientprot.IfButton6::class] =
            com.opennxt.net.game.handlers.IfButtonNHandler
        handlers[com.opennxt.net.game.clientprot.IfButton7::class] =
            com.opennxt.net.game.handlers.IfButtonNHandler
        handlers[com.opennxt.net.game.clientprot.IfButton8::class] =
            com.opennxt.net.game.handlers.IfButtonNHandler
        handlers[com.opennxt.net.game.clientprot.IfButton9::class] =
            com.opennxt.net.game.handlers.IfButtonNHandler
        handlers[com.opennxt.net.game.clientprot.IfButton10::class] =
            com.opennxt.net.game.handlers.IfButtonNHandler

        handlers[UndecodedClientPacket::class] = UndecodedClientPacketHandler

        com.opennxt.net.game.GeneratedRegistrations.installClientHandlers(handlers)

        handlers[EventAppletFocus::class] = EventAppletFocusHandler
        handlers[EventCameraPosition::class] = EventCameraPositionHandler

        handlers[ChatSetMode::class] = ChatSetModeHandler
        handlers[MessagePrivate::class] = MessagePrivateHandler
    }

    fun handleIncomingPackets() {
        val queue = client.incomingQueue
        var handled = 0
        while (handled < com.opennxt.net.InboundDrainLimit.MAX_PER_TICK) {
            val packet = client.pollIncoming() ?: return
            handled++

            val handler = handlers[packet::class] as? GamePacketHandler<in BasePlayer, GamePacket>
            if (handler != null) {
                handler.handle(this, packet)
            } else {
                InboundCensus.noHandler(packet)
            }
        }
        val remaining = queue.size
        if (remaining > 0) com.opennxt.net.InboundDrainLimit.reportBacklog(name, handled, remaining)
    }

    fun added() {
        client.write(ResetClientVarcache)

        val interfacesFirst = System.getProperty("opennxt.experiment.interfacesFirst")?.toBoolean() ?: false
        val varpLimit = System.getProperty("opennxt.experiment.varpLimit")?.toIntOrNull()

        val varpDelay = System.getProperty("opennxt.experiment.varpDelayMs")?.toLongOrNull() ?: 0L

        val varpsAfterReady = System.getProperty("opennxt.experiment.varpsAfterReady")?.toBoolean() ?: false

        fun replayVarps() {
            if (varpsAfterReady) {
                logger.warn { "varps: waiting for the client's first packet before sending" }
                val t = Thread({
                    val deadline = System.currentTimeMillis() + 30_000
                    while (!client.clientHasSpoken && client.channel.isActive && System.currentTimeMillis() < deadline)
                        Thread.sleep(20)
                    if (!client.channel.isActive) {
                        logger.warn { "varps: client disconnected before sending a packet; varps not sent" }
                        return@Thread
                    }
                    if (!client.clientHasSpoken) {
                        logger.warn { "varps: no packet from the client within 30s; varps not sent" }
                        return@Thread
                    }
                    logger.warn { "varps: client ready, sending varps" }
                    try {
                        val sent = TODORefactorThisClass.sendDefaultVarps(client, varpLimit, varpDelay)
                        logger.warn { "varps: sent $sent varps" }
                    } catch (e: Exception) {
                        logger.warn { "varps: sending stopped: ${e.message}" }
                    }
                }, "varp-replay-gated")
                t.isDaemon = true
                t.start()
                return
            }

            if (varpDelay > 0) {
                logger.warn {
                    "varps: sending on a background thread, ${varpDelay}ms apart"
                }
                val t = Thread({
                    try {
                        val sent = TODORefactorThisClass.sendDefaultVarps(client, varpLimit, varpDelay)
                        logger.warn { "varps: sent $sent varps ${varpDelay}ms apart" }
                    } catch (e: Exception) {
                        logger.warn { "varps: sending stopped: ${e.message}" }
                    }
                }, "varp-replay")
                t.isDaemon = true
                t.start()
                return
            }
            val sent = TODORefactorThisClass.sendDefaultVarps(client, varpLimit)
            logger.info { "sent $sent default varps" +
                if (varpLimit != null) " (limited to $varpLimit)" else "" }
        }

        if (!interfacesFirst) replayVarps()
        else logger.warn { "lobby: opening interfaces before varps" }

        if (System.getProperty("opennxt.experiment.lobbyInterfaces") == "off") {
            logger.warn {
                "lobby: interfaces disabled (-Dopennxt.experiment.lobbyInterfaces=off)"
            }
            if (interfacesFirst) replayVarps()
            return
        }

        val defaultLobby = com.opennxt.model.entity.rendering.UpdateBlockType.experimentalBuild()
        if (defaultLobby) {
            client.write(ClientSetvarcLarge(2771, 43650361))
            client.write(ClientSetvarcSmall(3496, 0))
            client.write(ClientSetvarcSmall(7108, 1))
            client.write(ClientSetvarcstrSmall(2508, name))
            client.write(ClientSetvarcSmall(1027, 1))
            client.write(ClientSetvarcSmall(1034, 2))
            client.write(ClientSetvarcLarge(3699, 4096))
        }
        interfaces.openTop(id = 906)
        if (defaultLobby) {
            for ((id, comp) in DEFAULT_LOBBY_MOUNTS) interfaces.open(id = id, parent = 906, component = comp, walkable = true, native949 = true)
            client.write(RunClientScript(script = 7486, args = arrayOf(29808402, 52494341)))
            client.write(RunClientScript(script = 7486, args = arrayOf(29808402, 59637775)))
            interfaces.open(id = 1322, parent = 906, component = 171, walkable = true, native949 = true)
            interfaces.open(id = 814, parent = 906, component = 140, walkable = true, native949 = true)
            client.write(ClientSetvarcSmall(4659, 74))
            client.write(ClientSetvarcLarge(4660, 130490))
            client.write(ClientSetvarcSmall(1800, 0))
            client.write(ClientSetvarcSmall(4968, 0))
            client.write(ClientSetvarcSmall(4969, 20))
            for (c in intArrayOf(39, 75, 46, 101)) interfaces.events(id = 907, component = c, from = 0, to = 0, mask = 16777218)
            client.write(ClientSetvarcSmall(4266, 0))
            client.write(RunClientScript(script = 10936, args = arrayOf()))
        } else {
        interfaces.open(id = 907, parent = 906, component = 65, walkable = true)
        interfaces.open(id = 910, parent = 906, component = 66, walkable = true)
        interfaces.open(id = 909, parent = 906, component = 67, walkable = true)
        interfaces.open(id = 912, parent = 906, component = 69, walkable = true)
        interfaces.open(id = 589, parent = 906, component = 68, walkable = true)
        interfaces.open(id = 911, parent = 906, component = 70, walkable = true)
        interfaces.open(id = 914, parent = 906, component = 128, walkable = true)
        interfaces.open(id = 915, parent = 906, component = 129, walkable = true)
        interfaces.open(id = 913, parent = 906, component = 130, walkable = true)
        interfaces.open(id = 815, parent = 906, component = 137, walkable = true)
        interfaces.open(id = 803, parent = 906, component = 132, walkable = true)
        interfaces.open(id = 822, parent = 906, component = 133, walkable = true)
        interfaces.open(id = 825, parent = 906, component = 115, walkable = true)
        interfaces.open(id = 821, parent = 906, component = 116, walkable = true)
        interfaces.open(id = 808, parent = 906, component = 114, walkable = true)
        interfaces.open(id = 820, parent = 906, component = 134, walkable = true)
        interfaces.open(id = 811, parent = 906, component = 131, walkable = true)
        interfaces.open(id = 826, parent = 906, component = 82, walkable = true)
        interfaces.open(id = 801, parent = 906, component = 36, walkable = true)
        }

        if (!defaultLobby) {
        interfaces.open(id = 1322, parent = 906, component = 151, walkable = true)
        interfaces.open(id = 814, parent = 906, component = 37, walkable = true)
        }

        if (interfacesFirst) replayVarps()

        System.getProperty("opennxt.experiment.scriptFile")?.let { p ->
            val f = java.io.File(p)
            if (!f.exists()) {
                logger.error { "scriptFile: $p does not exist - sending no scripts" }
                return@let
            }
            var sent = 0
            var skipped = 0
            f.forEachLine { line ->
                val s = line.trim()
                if (s.isEmpty() || s.startsWith("#")) return@forEachLine
                val parts = s.split('\t')
                if (parts.size < 2) return@forEachLine
                val sid = parts[0].toIntOrNull() ?: return@forEachLine
                val types = parts[1]
                val raw = if (parts.size > 2 && parts[2].isNotBlank()) parts[2].split(',') else emptyList()
                if (raw.size != types.length) { skipped++; return@forEachLine }
                val args = ArrayList<Any>(raw.size)
                var okLine = true
                types.forEachIndexed { i, t ->
                    val v: Any? = when (t) {
                        'i' -> raw[i].trim().toIntOrNull()
                        'l' -> raw[i].trim().toLongOrNull()
                        else -> null
                    }
                    if (v == null) okLine = false else args.add(v)
                }
                if (!okLine) { skipped++; return@forEachLine }
                client.write(RunClientScript(script = sid, args = args.toTypedArray()))
                sent++
            }
            logger.warn { "scriptFile: replayed $sent clientscript call(s) from $p, $skipped skipped (non-int args)" }
        }

    }

    override fun tick() {
    }
}

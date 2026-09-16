package com.opennxt.net.game

import com.opennxt.Constants
import com.opennxt.OpenNXT
import com.opennxt.model.files.FileChecker
import com.opennxt.net.Side
import com.opennxt.net.game.clientprot.*
import com.opennxt.net.game.pipeline.DynamicGamePacketCodec
import com.opennxt.net.game.pipeline.GamePacketCodec
import com.opennxt.net.game.protocol.PacketFieldDeclaration
import com.opennxt.net.game.serverprot.*
import com.opennxt.net.game.serverprot.ifaces.*
import com.opennxt.net.game.serverprot.variables.*
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap
import mu.KotlinLogging
import java.nio.file.Files
import kotlin.reflect.KClass
import kotlin.reflect.jvm.javaType
import com.opennxt.net.game.serverprot.audio.MidiSong
import com.opennxt.net.game.serverprot.audio.MidiSongStop
import com.opennxt.net.game.serverprot.audio.SoundGroupRelease
import com.opennxt.net.game.serverprot.audio.SoundGroupStop
import com.opennxt.net.game.serverprot.audio.SoundMixbussSetvolume
import com.opennxt.net.game.serverprot.interfaces.IfMovesub
import com.opennxt.net.game.serverprot.interfaces.IfSetcolour
import com.opennxt.net.game.clientprot.VarcTransmit
import com.opennxt.net.game.serverprot.variables.StoreServerpermVarcsAck
import com.opennxt.net.game.serverprot.variables.ClientSetvarcbitLarge
import com.opennxt.net.game.serverprot.variables.ClientSetvarcbitSmall
import com.opennxt.net.game.serverprot.variables.VarbitLarge
import com.opennxt.net.game.serverprot.variables.VarbitSmall

object PacketRegistry {
    private val logger = KotlinLogging.logger { }

    private val serverProtByOpcode = Int2ObjectOpenHashMap<Registration>()
    private val clientProtByOpcode = Int2ObjectOpenHashMap<Registration>()

    private val serverProtByClass = Object2ObjectOpenHashMap<KClass<*>, Registration>()
    private val clientProtByClass = Object2ObjectOpenHashMap<KClass<*>, Registration>()

    private val unmapped = HashMap<Side, java.util.SortedSet<String>>()

    private val missingDeclaration = HashMap<Side, java.util.SortedSet<String>>()

    fun unmappedNames(side: Side): Set<String> =
        (unmapped[side] ?: emptySet<String>()) + (missingDeclaration[side] ?: emptySet<String>())

    fun registeredCount(side: Side): Int =
        if (side == Side.CLIENT) clientProtByOpcode.size else serverProtByOpcode.size

    data class Registration(
        val name: String,
        val opcode: Int,
        val clazz: KClass<*>,
        val codec: GamePacketCodec<*>
    )

    fun <T : GamePacket> register(
        side: Side,
        name: String,
        clazz: KClass<T>,
        codecType: KClass<out DynamicGamePacketCodec<T>>
    ) {
        val constructor = codecType.constructors
            .first { it.parameters.size == 1 && it.parameters[0].type.javaType == Array<PacketFieldDeclaration>::class.java }

        val build = OpenNXT.protocol.effectiveBuild.takeIf { it > 0 } ?: OpenNXT.config.build
        val packetPath = Constants.PROT_PATH.resolve(build.toString())
            .resolve(if (side == Side.CLIENT) "clientProt" else "serverProt")
            .resolve("$name.txt")

        if (opcodeFor(side, name) == null) {
            unmapped.getOrPut(side) { sortedSetOf<String>() }.add(name)
            return
        }

        if (!Files.exists(packetPath)) {
            logger.warn { "Build $build maps '$name' but has no field file at $packetPath; packet disabled" }
            missingDeclaration.getOrPut(side) { sortedSetOf<String>() }.add(name)
            return
        }

        val fields = Files.readAllLines(packetPath)
            .mapIndexed { i, line -> (i + 1) to line }
            .filter { it.second.isNotBlank() && !it.second.trimStart().startsWith("#") }
            .map { (lineNo, line) -> PacketFieldDeclaration.fromString(line, "$packetPath:$lineNo") }
            .toTypedArray()

        val codec = constructor.call(fields)

        register(side, name, clazz, codec)
    }

    private fun opcodeFor(side: Side, name: String): Int? {
        @Suppress("DEPRECATION")
        return (if (side == Side.CLIENT) OpenNXT.protocol.clientProtNames else OpenNXT.protocol.serverProtNames)
            .values[name]
    }

    fun <T : GamePacket> register(side: Side, name: String, clazz: KClass<T>, codec: GamePacketCodec<T>?) {
        val opcode = opcodeFor(side, name)

        if (opcode == null) {
            unmapped.getOrPut(side) { sortedSetOf<String>() }.add(name)
            return
        }

        if (codec == null) {
            logger.warn { "Skipping registering packet $name on side $side: Codec is null" }
            return
        }

        val registration = Registration(name, opcode!!, clazz, codec)

        logger.info { "Registered packet ${clazz.simpleName} on side $side to opcode $opcode with codec ${codec::class.simpleName}" }

        if (side == Side.CLIENT) {
            clientProtByClass[clazz] = registration
            clientProtByOpcode[opcode] = registration
        } else {
            serverProtByClass[clazz] = registration
            serverProtByOpcode[opcode] = registration
        }
    }

    fun registerUndecoded(side: Side, opcode: Int) {
        val existing = if (side == Side.CLIENT) clientProtByOpcode[opcode] else serverProtByOpcode[opcode]
        if (existing != null) {
            logger.warn {
                "Opcode $opcode on side $side is already registered as '${existing.name}'; " +
                    "remove it from UndecodedClientPacket.OPCODES"
            }
            return
        }

        val sizes = if (side == Side.CLIENT) OpenNXT.protocol.clientProtSizes else OpenNXT.protocol.serverProtSizes
        if (!sizes.values.containsKey(opcode)) {
            logger.warn {
                "Opcode $opcode on side $side has no size entry for build " +
                    "${OpenNXT.protocol.effectiveBuild}; not registered"
            }
            return
        }

        val registration = Registration(
            "UNDECODED_$opcode", opcode, UndecodedClientPacket::class, UndecodedClientPacket.Codec(opcode)
        )

        if (side == Side.CLIENT) clientProtByOpcode[opcode] = registration
        else serverProtByOpcode[opcode] = registration

        seenValues.getOrPut(side) { sortedSetOf<Int>() }.add(opcode)
    }

    private val seenValues = HashMap<Side, java.util.SortedSet<Int>>()

    fun undecodedOpcodes(side: Side): Set<Int> = seenValues[side] ?: emptySet()

    fun describeOpcode(side: Side, opcode: Int): String {
        val registration = getRegistration(side, opcode) ?: return "UNMAPPED"
        return if (registration.name.startsWith(UNDECODED_PREFIX)) "UNDECODED-ONLY" else registration.name
    }

    fun isUndecoded(side: Side, opcode: Int): Boolean =
        getRegistration(side, opcode)?.name?.startsWith(UNDECODED_PREFIX) == true

    const val UNDECODED_PREFIX = "UNDECODED_"

    fun registrations(side: Side): List<Registration> {
        val map = if (side == Side.CLIENT) clientProtByOpcode else serverProtByOpcode
        return map.values.sortedBy { it.opcode }
    }

    fun sizeLabel(side: Side, opcode: Int): String {
        val sizes = if (side == Side.CLIENT) OpenNXT.protocol.clientProtSizes else OpenNXT.protocol.serverProtSizes
        if (!sizes.values.containsKey(opcode)) return "NO SIZE ENTRY"
        return when (val size = sizes.values.get(opcode)) {
            -1 -> "var-byte"
            -2 -> "var-short"
            else -> "size $size"
        }
    }

    fun reload() {
        unmapped.clear()
        missingDeclaration.clear()
        seenValues.clear()
        clientProtByOpcode.clear()
        clientProtByClass.clear()
        serverProtByClass.clear()
        serverProtByOpcode.clear()

        register(Side.SERVER, "UPDATE_STAT", UpdateStat::class, UpdateStat.Codec::class)
        register(Side.SERVER, "VARP_SMALL", VarpSmall::class, VarpSmall.Codec::class)
        register(Side.SERVER, "VARP_LARGE", VarpLarge::class, VarpLarge.Codec::class)

        register(Side.SERVER, "VARBIT_SMALL", VarbitSmall::class, VarbitSmall.Codec::class)
        register(Side.SERVER, "VARBIT_LARGE", VarbitLarge::class, VarbitLarge.Codec::class)
        if (com.opennxt.config.ServerConfig.experimentalBuildOverride() == 950) {
            register(Side.SERVER, "VARBIT_VARINT", com.opennxt.net.game.serverprot.variables.VarbitVarint::class, com.opennxt.net.game.serverprot.variables.VarbitVarint.Codec::class)
            register(Side.SERVER, "VARBIT_VARINT_ALT", com.opennxt.net.game.serverprot.variables.VarbitVarintAlt::class, com.opennxt.net.game.serverprot.variables.VarbitVarintAlt.Codec::class)
        }
        register(Side.SERVER, "CLIENT_SETVARCBIT_SMALL", ClientSetvarcbitSmall::class, ClientSetvarcbitSmall.Codec::class)
        register(Side.SERVER, "CLIENT_SETVARCBIT_LARGE", ClientSetvarcbitLarge::class, ClientSetvarcbitLarge.Codec::class)
        register(Side.SERVER, "IF_SETCOLOUR", IfSetcolour::class, IfSetcolour.Codec::class)
        register(Side.SERVER, "IF_MOVESUB", IfMovesub::class, IfMovesub.Codec::class)
        register(Side.SERVER, "MIDI_SONG", MidiSong::class, MidiSong.Codec::class)
        register(Side.SERVER, "MIDI_SONG_STOP", MidiSongStop::class, EmptyPacketCodec(MidiSongStop))
        register(Side.SERVER, "SOUND_GROUP_STOP", SoundGroupStop::class, SoundGroupStop.Codec::class)
        register(Side.SERVER, "SOUND_GROUP_RELEASE", SoundGroupRelease::class, SoundGroupRelease.Codec::class)
        register(Side.SERVER, "SOUND_MIXBUSS_SETVOLUME", SoundMixbussSetvolume::class, SoundMixbussSetvolume.Codec::class)
        register(
            Side.SERVER,
            "RESET_CLIENT_VARCACHE",
            ResetClientVarcache::class,
            EmptyPacketCodec(ResetClientVarcache)
        )
        register(Side.SERVER, "CLIENT_SETVARC_SMALL", ClientSetvarcSmall::class, ClientSetvarcSmall.Codec::class)
        register(Side.SERVER, "CLIENT_SETVARC_LARGE", ClientSetvarcLarge::class, ClientSetvarcLarge.Codec::class)
        register(Side.SERVER, "NO_TIMEOUT", NoTimeout::class, EmptyPacketCodec(NoTimeout))
        register(Side.SERVER, "CAM_RESET", com.opennxt.net.game.serverprot.CamReset::class,
            EmptyPacketCodec(com.opennxt.net.game.serverprot.CamReset))
        register(Side.SERVER, "RESET_ANIMS", com.opennxt.net.game.serverprot.ResetAnims::class,
            EmptyPacketCodec(com.opennxt.net.game.serverprot.ResetAnims))
        register(Side.SERVER, "RUNCLIENTSCRIPT", RunClientScript::class, RunClientScript.Codec)
        register(Side.SERVER, "PLAYER_SNAPSHOT", com.opennxt.net.game.serverprot.PlayerSnapshot::class,
            com.opennxt.net.game.serverprot.PlayerSnapshot.Codec)
        register(
            Side.SERVER,
            "CLIENT_SETVARCSTR_SMALL",
            ClientSetvarcstrSmall::class,
            ClientSetvarcstrSmall.Codec::class
        )
        register(
            Side.SERVER,
            "CLIENT_SETVARCSTR_LARGE",
            ClientSetvarcstrLarge::class,
            ClientSetvarcstrLarge.Codec::class
        )
        register(Side.SERVER, "WORLDLIST_FETCH_REPLY", WorldListFetchReply::class, WorldListFetchReply.Codec)
        register(Side.SERVER, "IF_OPENTOP", IfOpenTop::class, IfOpenTop.Codec::class)
        register(Side.SERVER, "IF_OPENSUB", IfOpenSub::class, IfOpenSub.Codec::class)
        register(Side.SERVER, "IF_OPENSUB_ACTIVE_NPC", IfOpensubActiveNpc::class, IfOpensubActiveNpc.Codec)
        register(Side.SERVER, "IF_CLOSESUB", IfClosesub::class, IfClosesub.Codec::class)
        register(Side.SERVER, "IF_SETEVENTS", IfSetevents::class, IfSetevents.Codec::class)
        register(Side.SERVER, "IF_SETTEXT", IfSettext::class, IfSettext.Codec::class)
        register(Side.SERVER, "IF_SETHIDE", IfSethide::class, IfSethide.Codec::class)
        register(Side.SERVER, "IF_SETMODEL", IfModelK1::class, IfModelK1.Codec::class)
        register(Side.SERVER, "IF_SETNPCHEAD", IfModelK2::class, IfModelK2.Codec::class)
        register(Side.SERVER, "IF_SETPLAYERHEAD", IfModelK3Self::class, IfModelK3Self.Codec::class)
        register(Side.SERVER, "IF_SETPLAYERMODEL_SELF", IfModelK5Self::class, IfModelK5Self.Codec::class)
        register(
            Side.SERVER,
            "CHAT_FILTER_SETTINGS_PRIVATECHAT",
            ChatFilterSettingsPrivatechat::class,
            ChatFilterSettingsPrivatechat.Codec::class
        )
        register(Side.SERVER, "FRIENDLIST_LOADED", FriendlistLoaded::class, EmptyPacketCodec(FriendlistLoaded))
        register(Side.SERVER, "MESSAGE_GAME", MessageGame::class, MessageGame.Codec)
        register(Side.SERVER, "CONSOLE_FEEDBACK", ConsoleFeedback::class, ConsoleFeedback.Codec)
        register(Side.SERVER, "REBUILD_NORMAL", RebuildNormal::class, RebuildNormal.Codec::class)
        register(Side.SERVER, "SERVER_TICK_END", ServerTickEnd::class, EmptyPacketCodec(ServerTickEnd))
        register(Side.SERVER, "SET_MAP_FLAG", SetMapFlag::class, SetMapFlag.Codec::class)

        register(Side.SERVER, "UPDATE_INV_FULL", UpdateInvFull::class, UpdateInvFull.Codec)
        register(Side.SERVER, "UPDATE_INV_PARTIAL", UpdateInvPartial::class, UpdateInvPartial.Codec)
        register(
            Side.SERVER,
            "UPDATE_INV_STOP_TRANSMIT",
            UpdateInvStopTransmit::class,
            UpdateInvStopTransmit.Codec::class
        )

        register(
            Side.SERVER,
            "UPDATE_ZONE_PARTIAL_FOLLOWS",
            UpdateZonePartialFollows::class,
            UpdateZonePartialFollows.Codec::class
        )
        register(
            Side.SERVER,
            "UPDATE_ZONE_FULL_FOLLOWS",
            UpdateZoneFullFollows::class,
            UpdateZoneFullFollows.Codec::class
        )
        register(Side.SERVER, "OBJ_ADD", ObjAdd::class, ObjAdd.Codec::class)
        register(Side.SERVER, "OBJ_DEL", ObjDel::class, ObjDel.Codec::class)
        register(Side.SERVER, "OBJ_DEL_949", ObjDel949::class, ObjDel949.Codec::class)
        register(Side.SERVER, "OBJ_ADD_949", ObjAdd949::class, ObjAdd949.Codec)
        register(Side.SERVER, "OBJ_COUNT", ObjCount::class, ObjCount.Codec::class)
        register(Side.SERVER, "OBJ_COUNT_949", ObjCount949::class, ObjCount949.Codec::class)
        register(Side.SERVER, "OBJ_REVEAL", ObjReveal::class, ObjReveal.Codec::class)
        register(Side.SERVER, "OBJ_REVEAL_949", ObjReveal949::class, ObjReveal949.Codec::class)
        register(Side.SERVER, "LOC_ADD_CHANGE", LocAddChange::class, LocAddChange.Codec::class)
        register(Side.SERVER, "LOC_DEL", LocDel::class, LocDel.Codec::class)
        register(Side.SERVER, "LOC_ANIM", LocAnim::class, LocAnim.Codec::class)
        register(Side.SERVER, "UPDATE_ZONE_PARTIAL_ENCLOSED", UpdateZonePartialEnclosed::class, UpdateZonePartialEnclosed.Codec)

        register(Side.SERVER, "MESSAGE_PUBLIC", MessagePublicOut::class, MessagePublicOut.Codec)

        register(Side.SERVER, "SOUND_MIXBUSS_ADD", SoundMixbussAdd::class, SoundMixbussAdd.Codec::class)

        register(Side.CLIENT, "NO_TIMEOUT", NoTimeout::class, EmptyPacketCodec(NoTimeout))
        register(Side.CLIENT, "CLIENT_CHEAT", ClientCheat::class, ClientCheat.Codec::class)
        register(Side.CLIENT, "WORLDLIST_FETCH", WorldlistFetch::class, WorldlistFetch.Codec::class)
        register(Side.CLIENT, "MOVE_GAMECLICK", MoveGameClick::class, MoveGameClick.Codec::class)
        register(Side.CLIENT, "MOVE_MINIMAPCLICK", MoveMinimapClick::class, MoveMinimapClick.Codec::class)
        register(Side.CLIENT, "WINDOW_STATUS", WindowStatus::class, WindowStatus.Codec::class)
        register(Side.CLIENT, "IF_BUTTON1", IfButton1::class, IfButton1.Codec::class)

        register(Side.CLIENT, "IF_BUTTON2", IfButton2::class, IfButton2.Codec::class)
        register(Side.CLIENT, "IF_BUTTON3", IfButton3::class, IfButton3.Codec::class)
        register(Side.CLIENT, "IF_BUTTON4", IfButton4::class, IfButton4.Codec::class)
        register(Side.CLIENT, "IF_BUTTON5", IfButton5::class, IfButton5.Codec::class)
        register(Side.CLIENT, "IF_BUTTON6", IfButton6::class, IfButton6.Codec::class)
        register(Side.CLIENT, "IF_BUTTON7", IfButton7::class, IfButton7.Codec::class)
        register(Side.CLIENT, "IF_BUTTON8", IfButton8::class, IfButton8.Codec::class)
        register(Side.CLIENT, "IF_BUTTON9", IfButton9::class, IfButton9.Codec::class)
        register(Side.CLIENT, "IF_BUTTON10", IfButton10::class, IfButton10.Codec::class)

        register(Side.CLIENT, "IF_BUTTON_LABELLED", IfButtonLabelled::class, IfButtonLabelled.Codec::class)

        register(Side.CLIENT, "OPLOC1", OpLoc1::class, OpLoc1.Codec::class)
        register(Side.CLIENT, "OPLOC2", OpLoc2::class, OpLoc2.Codec::class)
        register(Side.CLIENT, "OPLOC3", OpLoc3::class, OpLoc3.Codec::class)
        register(Side.CLIENT, "OPLOC4", OpLoc4::class, OpLoc4.Codec::class)
        register(Side.CLIENT, "OPLOC5", OpLoc5::class, OpLoc5.Codec::class)
        register(Side.CLIENT, "OPLOC6", OpLoc6::class, OpLoc6.Codec::class)

        register(Side.CLIENT, "OPNPC1", OpNpc1::class, OpNpc1.Codec::class)
        register(Side.CLIENT, "OPNPC2", OpNpc2::class, OpNpc2.Codec::class)
        register(Side.CLIENT, "OPNPC3", OpNpc3::class, OpNpc3.Codec::class)
        register(Side.CLIENT, "OPNPC4", OpNpc4::class, OpNpc4.Codec::class)
        register(Side.CLIENT, "OPNPC5", OpNpc5::class, OpNpc5.Codec::class)
        register(Side.CLIENT, "OPNPC6", OpNpc6::class, OpNpc6.Codec::class)

        register(Side.CLIENT, "OPPLAYER1", OpPlayer1::class, OpPlayer1.Codec::class)
        register(Side.CLIENT, "OPPLAYER2", OpPlayer2::class, OpPlayer2.Codec::class)
        register(Side.CLIENT, "OPPLAYER3", OpPlayer3::class, OpPlayer3.Codec::class)
        register(Side.CLIENT, "OPPLAYER4", OpPlayer4::class, OpPlayer4.Codec::class)
        register(Side.CLIENT, "OPPLAYER5", OpPlayer5::class, OpPlayer5.Codec::class)
        register(Side.CLIENT, "OPPLAYER6", OpPlayer6::class, OpPlayer6.Codec::class)
        register(Side.CLIENT, "OPPLAYER7", OpPlayer7::class, OpPlayer7.Codec::class)
        register(Side.CLIENT, "OPPLAYER8", OpPlayer8::class, OpPlayer8.Codec::class)
        register(Side.CLIENT, "OPPLAYER9", OpPlayer9::class, OpPlayer9.Codec::class)
        register(Side.CLIENT, "OPPLAYER10", OpPlayer10::class, OpPlayer10.Codec::class)

        register(Side.CLIENT, "OPOBJ1", OpObj1::class, OpObj1.Codec::class)
        register(Side.CLIENT, "OPOBJ2", OpObj2::class, OpObj2.Codec::class)
        register(Side.CLIENT, "OPOBJ3", OpObj3::class, OpObj3.Codec::class)
        register(Side.CLIENT, "OPOBJ4", OpObj4::class, OpObj4.Codec::class)
        register(Side.CLIENT, "OPOBJ5", OpObj5::class, OpObj5.Codec::class)
        register(Side.CLIENT, "OPOBJ6", OpObj6::class, OpObj6.Codec::class)

        register(Side.CLIENT, "EVENT_APPLET_FOCUS", EventAppletFocus::class, EventAppletFocus.Codec::class)
        register(Side.CLIENT, "EVENT_CAMERA_POSITION", EventCameraPosition::class, EventCameraPosition.Codec::class)

        register(Side.CLIENT, "EVENT_MOUSE_CLICK", EventMouseClick::class, EventMouseClick.Codec::class)

        register(Side.CLIENT, "EVENT_KEYBOARD", EventKeyboard::class, EventKeyboard.Codec())
        register(Side.CLIENT, "VARC_TRANSMIT", VarcTransmit::class, VarcTransmit.Codec)
        register(Side.SERVER, "STORE_SERVERPERM_VARCS_ACK", StoreServerpermVarcsAck::class, StoreServerpermVarcsAck.Codec)

        register(Side.CLIENT, "MESSAGE_PUBLIC", MessagePublic::class, MessagePublic.Codec)
        register(Side.CLIENT, "MESSAGE_PRIVATE", MessagePrivate::class, MessagePrivate.Codec)
        register(Side.CLIENT, "CHAT_SETMODE", ChatSetMode::class, ChatSetMode.Codec::class)

        GeneratedRegistrations.registerAll()

        UndecodedClientPacket.OPCODES.forEach { registerUndecoded(Side.CLIENT, it) }

        reportUnmapped()
    }

    private fun reportUnmapped() {
        val server = unmapped[Side.SERVER] ?: emptySet<String>()
        val client = unmapped[Side.CLIENT] ?: emptySet<String>()
        val noDeclServer = missingDeclaration[Side.SERVER] ?: emptySet<String>()
        val noDeclClient = missingDeclaration[Side.CLIENT] ?: emptySet<String>()

        logger.info {
            "Protocol coverage for build ${OpenNXT.config.build}: " +
                "${serverProtByOpcode.size} server packets registered (${server.size} unmapped), " +
                "${clientProtByOpcode.size} client packets registered (${client.size} unmapped)"
        }

        val undecodedClient = seenValues[Side.CLIENT] ?: emptySet<Int>()
        if (undecodedClient.isNotEmpty()) {
            logger.info {
                "${undecodedClient.size} client opcode(s) are read without decoding: " +
                    undecodedClient.joinToString(", ") { "$it(${sizeLabel(Side.CLIENT, it)})" }
            }
            val clientNamesByOpcode = OpenNXT.protocol.clientProtNames.reversedValues()
            val namedButUndecoded = undecodedClient.filter { clientNamesByOpcode.containsKey(it) }
            if (namedButUndecoded.isNotEmpty()) {
                logger.warn {
                    "${namedButUndecoded.size} named client opcode(s) have no registered codec: " +
                        namedButUndecoded.joinToString(", ") { "$it=${clientNamesByOpcode[it]}" }
                }
            }
        }

        if (server.isEmpty() && client.isEmpty() && noDeclServer.isEmpty() && noDeclClient.isEmpty()) return

        logger.warn { "-------------------------------------------------------------" }
        logger.warn { " Incomplete protocol table for build ${OpenNXT.config.build}." }
        logger.warn { " The packets below have no opcode mapping or field file," }
        logger.warn { " so they cannot be sent or received and features that" }
        logger.warn { " depend on them will not work:" }
        if (server.isNotEmpty()) logger.warn { "   no opcode  (server): ${server.joinToString(", ")}" }
        if (client.isNotEmpty()) logger.warn { "   no opcode  (client): ${client.joinToString(", ")}" }
        if (noDeclServer.isNotEmpty()) logger.warn { "   no fields  (server): ${noDeclServer.joinToString(", ")}" }
        if (noDeclClient.isNotEmpty()) logger.warn { "   no fields  (client): ${noDeclClient.joinToString(", ")}" }
        logger.warn { "" }
        logger.warn { " This is expected for a build with incomplete tables and" }
        logger.warn { " does not prevent the server from starting." }
        logger.warn { "-------------------------------------------------------------" }
    }

    fun getRegistration(side: Side, opcode: Int): Registration? {
        return if (side == Side.CLIENT) {
            clientProtByOpcode[opcode]
        } else {
            serverProtByOpcode[opcode]
        }
    }

    fun getRegistration(side: Side, clazz: KClass<*>): Registration? {
        return if (side == Side.CLIENT) {
            clientProtByClass[clazz]
        } else {
            serverProtByClass[clazz]
        }
    }
}

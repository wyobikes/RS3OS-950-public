package com.opennxt.model.commands.impl.player

import com.opennxt.model.commands.CommandSender
import com.opennxt.model.commands.SimpleCommand
import com.opennxt.model.world.WorldPlayer
import com.opennxt.net.game.serverprot.audio.MidiSong
import com.opennxt.net.game.serverprot.audio.MidiSongStop
import com.opennxt.net.game.serverprot.audio.SoundGroupRelease
import com.opennxt.net.game.serverprot.audio.SoundGroupStop
import com.opennxt.net.game.serverprot.audio.SoundMixbussSetvolume
import com.opennxt.net.game.serverprot.interfaces.IfMovesub
import com.opennxt.net.game.serverprot.interfaces.IfSetcolour
import com.opennxt.net.game.serverprot.variables.ClientSetvarcbitLarge
import com.opennxt.net.game.serverprot.variables.ClientSetvarcbitSmall
import com.opennxt.net.game.serverprot.variables.VarbitLarge
import com.opennxt.net.game.serverprot.variables.VarbitSmall

object ProtTestCommand : SimpleCommand() {
    private val USAGE = listOf(
        "::prottest colour <component> <rgb15>   IF_SETCOLOUR (op 108)",
        "::prottest movesub <from> <to>          IF_MOVESUB (op 88)",
        "::prottest varbit <id> [value]          VARBIT_SMALL (op 22)",
        "::prottest varbitbig <id> <value>       VARBIT_LARGE (op 51)",
        "::prottest varcbit <id> [value]         CLIENT_SETVARCBIT_SMALL (op 54)",
        "::prottest varcbitbig <id> <value>      CLIENT_SETVARCBIT_LARGE (op 50)",
        "::prottest song <id> [volume]           MIDI_SONG (op 37)",
        "::prottest songstop                     MIDI_SONG_STOP (op 228)",
        "::prottest soundstop <group>            SOUND_GROUP_STOP (op 195)",
        "::prottest soundrelease <group>         SOUND_GROUP_RELEASE (op 130)",
        "::prottest mixbuss <bus> <gain>         SOUND_MIXBUSS_SETVOLUME (op 128)"
    )

    override fun execute(sender: CommandSender, alias: String, command: String) {
        if (sender !is WorldPlayer) {
            sender.error("This command can only be used by a player.")
            return
        }
        val args = command.split(" ").drop(1)
        if (args.isEmpty()) {
            sender.console("Protocol test: sends a single server packet.")
            sender.console("Each subcommand prints the expected client effect.")
            USAGE.forEach { sender.console(it) }
            return
        }

        fun arg(i: Int, name: String): Int? {
            val v = args.getOrNull(i)?.toIntOrNull()
            if (v == null) sender.error("Expected an integer for <$name>.")
            return v
        }

        when (args[0].lowercase()) {
            "colour" -> {
                val comp = arg(1, "component") ?: return
                val rgb = arg(2, "rgb15") ?: return
                sender.console("Expect: component $comp changes colour (rgb15, 0x7fff is white).")
                sender.write(IfSetcolour(rgb, comp))
            }
            "movesub" -> {
                val from = arg(1, "from") ?: return
                val to = arg(2, "to") ?: return
                sender.console("Expect: the sub-interface at $from moves to $to.")
                sender.write(IfMovesub(from, to))
            }
            "varbit" -> {
                val id = arg(1, "id") ?: return
                val v = args.getOrNull(2)?.toIntOrNull() ?: 1
                sender.console("Expect: varbit $id set to $v (out-of-range values are ignored by the client).")
                sender.write(VarbitSmall(id, v))
            }
            "varbitbig" -> {
                val id = arg(1, "id") ?: return
                val v = arg(2, "value") ?: return
                sender.console("Expect: varbit $id set to $v.")
                sender.write(VarbitLarge(v, id))
            }
            "varcbit" -> {
                val id = arg(1, "id") ?: return
                val v = args.getOrNull(2)?.toIntOrNull() ?: 1
                sender.console("Expect: client varbit $id set to $v.")
                sender.write(ClientSetvarcbitSmall(v, id))
            }
            "varcbitbig" -> {
                val id = arg(1, "id") ?: return
                val v = arg(2, "value") ?: return
                sender.console("Expect: client varbit $id set to $v.")
                sender.write(ClientSetvarcbitLarge(v, id))
            }
            "song" -> {
                val id = arg(1, "id") ?: return
                val vol = args.getOrNull(2)?.toIntOrNull() ?: 255
                sender.console("Expect: music track $id starts.")
                sender.write(MidiSong(vol, id))
            }
            "songstop" -> {
                sender.console("Expect: the current music stops.")
                sender.write(MidiSongStop)
            }
            "soundstop" -> {
                val g = arg(1, "group") ?: return
                sender.console("Expect: sounds in group $g stop.")
                sender.write(SoundGroupStop(g))
            }
            "soundrelease" -> {
                val g = arg(1, "group") ?: return
                sender.console("Expect: sounds in group $g are released.")
                sender.write(SoundGroupRelease(g))
            }
            "mixbuss" -> {
                val bus = arg(1, "bus") ?: return
                val gain = arg(2, "gain") ?: return
                sender.console("Expect: volume change on bus $bus (65536 = unity, 0 = silent).")
                sender.write(SoundMixbussSetvolume(bus, gain))
            }
            else -> {
                sender.error("Unknown subcommand '${args[0]}'.")
                USAGE.forEach { sender.console(it) }
            }
        }
    }
}

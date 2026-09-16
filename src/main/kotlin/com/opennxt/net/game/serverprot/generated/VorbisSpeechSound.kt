package com.opennxt.net.game.serverprot.generated

import com.opennxt.net.game.GamePacket
import com.opennxt.net.game.pipeline.DynamicGamePacketCodec
import com.opennxt.net.game.protocol.PacketFieldDeclaration

data class VorbisSpeechSound(val unk0: Int, val unk4: Int, val unk5: Int, val unk7: Int) : GamePacket {
    class Codec(fields: Array<PacketFieldDeclaration>) : DynamicGamePacketCodec<VorbisSpeechSound>(fields) {
        override fun fromMap(packet: Map<String, Any>): VorbisSpeechSound = VorbisSpeechSound(packet["unk0"] as Int, packet["unk4"] as Int, packet["unk5"] as Int, packet["unk7"] as Int)
        override fun toMap(packet: VorbisSpeechSound): Map<String, Any> = mapOf("unk0" to packet.unk0, "unk4" to packet.unk4, "unk5" to packet.unk5, "unk7" to packet.unk7)
    }
}

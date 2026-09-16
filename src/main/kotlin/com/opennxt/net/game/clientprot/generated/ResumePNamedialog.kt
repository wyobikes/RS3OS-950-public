package com.opennxt.net.game.clientprot.generated

import com.opennxt.net.game.GamePacket
import com.opennxt.net.game.pipeline.DynamicGamePacketCodec
import com.opennxt.net.game.protocol.PacketFieldDeclaration

data class ResumePNamedialog(val hi: Int, val lo: Int) : GamePacket {
    class Codec(fields: Array<PacketFieldDeclaration>) : DynamicGamePacketCodec<ResumePNamedialog>(fields) {
        override fun fromMap(packet: Map<String, Any>): ResumePNamedialog = ResumePNamedialog(packet["hi"] as Int, packet["lo"] as Int)
        override fun toMap(packet: ResumePNamedialog): Map<String, Any> = mapOf("hi" to packet.hi, "lo" to packet.lo)
    }
}

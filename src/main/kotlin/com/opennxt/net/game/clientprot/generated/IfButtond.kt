package com.opennxt.net.game.clientprot.generated

import com.opennxt.net.game.GamePacket
import com.opennxt.net.game.pipeline.DynamicGamePacketCodec
import com.opennxt.net.game.protocol.PacketFieldDeclaration

data class IfButtond(val targetslot: Int, val targetobj: Int, val sourceobj: Int, val sourceslot: Int, val targethash: Int, val sourcehash: Int) : GamePacket {
    class Codec(fields: Array<PacketFieldDeclaration>) : DynamicGamePacketCodec<IfButtond>(fields) {
        override fun fromMap(packet: Map<String, Any>): IfButtond = IfButtond(packet["targetslot"] as Int, packet["targetobj"] as Int, packet["sourceobj"] as Int, packet["sourceslot"] as Int, packet["targethash"] as Int, packet["sourcehash"] as Int)
        override fun toMap(packet: IfButtond): Map<String, Any> = mapOf("targetslot" to packet.targetslot, "targetobj" to packet.targetobj, "sourceobj" to packet.sourceobj, "sourceslot" to packet.sourceslot, "targethash" to packet.targethash, "sourcehash" to packet.sourcehash)
    }
}

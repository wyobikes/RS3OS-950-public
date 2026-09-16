package com.opennxt.net.game.clientprot

import com.opennxt.net.game.GamePacket
import com.opennxt.net.game.pipeline.DynamicGamePacketCodec
import com.opennxt.net.game.protocol.PacketFieldDeclaration

class IfButtonLabelled(
    val slot: Int,
    val op: Int,
    val label: String,
    val hash: Int,
    val defFlag: Int
) : GamePacket {
    val interfaceId: Int get() = (hash shr 16) and 0xffff

    val component: Int get() = hash and 0xffff

    val slotSigned: Int get() = if (slot == 0xffff) -1 else slot

    override fun toString(): String =
        "IF_BUTTON_LABELLED(interface=$interfaceId, component=$component, op=$op, " +
            "slot=$slotSigned, label='$label', defFlag=$defFlag)"

    class Codec(fields: Array<PacketFieldDeclaration>) : DynamicGamePacketCodec<IfButtonLabelled>(fields) {
        override fun fromMap(packet: Map<String, Any>): IfButtonLabelled = IfButtonLabelled(
            slot = packet["slot"] as Int,
            op = packet["op"] as Int,
            label = packet["label"] as String,
            hash = packet["hash"] as Int,
            defFlag = packet["defFlag"] as Int
        )

        override fun toMap(packet: IfButtonLabelled): Map<String, Any> = mapOf(
            "slot" to packet.slot,
            "op" to packet.op,
            "label" to packet.label,
            "hash" to packet.hash,
            "defFlag" to packet.defFlag
        )
    }

    companion object {
        const val OPCODE = 102

        val FIELD_KEYS = listOf("slot", "op", "label", "hash", "defFlag")

        val FIELD_TYPES = listOf("ushortle", "ubyte", "string", "int", "ubytec")

        const val PROVENANCE: String =
            "IF_BUTTON_LABELLED (opcode 102): slot u16le, op u8, label string, hash int, defFlag u8 (meaning unknown)."
    }
}

package com.opennxt.net.game.serverprot.variables

import com.opennxt.net.buf.GamePacketBuilder
import com.opennxt.net.buf.GamePacketReader
import com.opennxt.net.game.GamePacket
import com.opennxt.net.game.pipeline.GamePacketCodec

object StoreServerpermVarcsAck : GamePacket {
    object Codec : GamePacketCodec<StoreServerpermVarcsAck> {
        override fun encode(packet: StoreServerpermVarcsAck, buf: GamePacketBuilder) = Unit

        override fun decode(buf: GamePacketReader): StoreServerpermVarcsAck = StoreServerpermVarcsAck
    }

    override fun toString(): String = "StoreServerpermVarcsAck"
}

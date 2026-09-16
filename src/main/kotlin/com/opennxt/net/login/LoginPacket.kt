package com.opennxt.net.login

import com.opennxt.config.ServerConfig
import com.opennxt.model.Build
import com.opennxt.net.GenericResponse
import com.opennxt.net.IncomingPacket
import com.opennxt.net.OutgoingPacket
import io.netty.buffer.ByteBuf

sealed class LoginPacket : IncomingPacket, OutgoingPacket {
    data class SendUniqueId(val id: Long) : LoginPacket()
    class LobbyLoginRequest(
        val build: Build,
        val header: LoginRSAHeader,
        val username: String,
        val password: String,
        val remaining: ByteBuf
    ) : LoginPacket()

    class GameLoginRequest(
        val build: Build,
        val header: LoginRSAHeader,
        val username: String,
        val password: String,
        val remaining: ByteBuf
    ) : LoginPacket()

    data class GameLoginResponse(
        val byte0: Int,
        val rights: Int,
        val byte2: Int,
        val byte3: Int,
        val byte4: Int,
        val byte5: Int,
        val byte6: Int,
        val playerIndex: Int,
        val byte8: Int,
        val medium9: Int,
        val isMember: Int,
        val username: String,
        val short12: Int,
        val int13: Int,
        val long14: Long = 0L,
        val long15: Long = 0L
    ) : LoginPacket() {
        companion object {
            fun forWorld(playerIndex: Int, username: String): GameLoginResponse {
                val member = com.opennxt.config.Membership.wireFlag()
                return GameLoginResponse(
                    byte0 = 0,
                    rights = 2,
                    byte2 = 0,
                    byte3 = 0,
                    byte4 = 0,
                    byte5 = 0,
                    byte6 = 0,
                    playerIndex = playerIndex,
                    byte8 = member,
                    medium9 = 0,
                    isMember = member,
                    username = username,
                    short12 = 0,
                    int13 = 0
                )
            }
        }
    }

    data class LobbyLoginResponse(
        val byte0: Int,
        val rights: Int,
        val byte2: Int,
        val byte3: Int,
        val medium4: Int,
        val byte5: Int,
        val byte6: Int,
        val byte7: Int,
        val long8: Long,
        val int9: Int,
        val byte10: Int,
        val byte11: Int,
        val int12: Int,
        val int13: Int,
        val short14: Int,
        val short15: Int,
        val short16: Int,
        val ip: Int,
        val byte17: Int,
        val short18: Int,
        val short19: Int,
        val byte20: Int,
        val username: String,
        val byte22: Int,
        val int23: Int,
        val short24: Int,
        val defaultWorld: String,
        val defaultWorldPort1: Int,
        val defaultWorldPort2: Int
    ) : LoginPacket() {
        companion object {
            fun packIpv4(address: String): Int {
                val parts = address.split('.')
                require(parts.size == 4) { "not a dotted-quad IPv4 address: '$address'" }
                var packed = 0
                for (part in parts) {
                    val octet = part.toIntOrNull()
                    require(octet != null && octet in 0..255) { "bad IPv4 octet '$part' in '$address'" }
                    packed = (packed shl 8) or octet
                }
                return packed
            }

            fun forAccount(username: String, config: ServerConfig): LobbyLoginResponse = LobbyLoginResponse(
                byte0 = 0,
                rights = 2,
                byte2 = 0,
                byte3 = 0,
                medium4 = 0,
                byte5 = 0,
                byte6 = 0,
                byte7 = 0,
                long8 = 0,
                int9 = 0,
                byte10 = 0,
                byte11 = com.opennxt.config.Membership.wireFlag(),
                int12 = 0,
                int13 = 0,
                short14 = 0,
                short15 = 0,
                short16 = 0,
                ip = packIpv4("127.0.0.1"),
                byte17 = 0,
                short18 = 0,
                short19 = 0,
                byte20 = 0,
                username = username,
                byte22 = 0,
                int23 = 0,
                short24 = 0,
                defaultWorld = config.hostname,
                defaultWorldPort1 = config.ports.game,
                defaultWorldPort2 = config.ports.game,
            )
        }
    }

    data class LoginResponse(val code: GenericResponse) : LoginPacket()

    object GameLoginContinue : LoginPacket()

    data class ServerpermVarcChunk(val finished: Boolean, val varcs: Map<Int, Any>) : LoginPacket()
}

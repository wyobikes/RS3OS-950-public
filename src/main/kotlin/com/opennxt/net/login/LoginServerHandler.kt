package com.opennxt.net.login

import com.opennxt.OpenNXT
import com.opennxt.login.LoginThread
import com.opennxt.model.account.AccountStore
import com.opennxt.model.account.PlayerSave
import com.opennxt.model.entity.PlayerEntity
import com.opennxt.model.lobby.LobbyPlayer
import com.opennxt.model.lobby.TODORefactorThisClass
import com.opennxt.model.world.TileLocation
import com.opennxt.model.world.WorldPlayer
import com.opennxt.net.GenericResponse
import com.opennxt.net.RSChannelAttributes
import com.opennxt.net.game.pipeline.DynamicPacketHandler
import com.opennxt.net.game.pipeline.GamePacketEncoder
import com.opennxt.net.game.pipeline.GamePacketFraming
import com.opennxt.util.ISAACCipher
import io.netty.buffer.Unpooled
import io.netty.channel.Channel
import io.netty.channel.ChannelFutureListener
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.SimpleChannelInboundHandler
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap
import mu.KotlinLogging

class LoginServerHandler : SimpleChannelInboundHandler<LoginPacket>() {
    private val logger = KotlinLogging.logger { }

    companion object {
        private val swapLogger = KotlinLogging.logger { }

        internal fun swapToGamePipeline(channel: Channel, username: String): Boolean {
            if (!channel.isActive) {
                swapLogger.warn { "Client $username disconnected before the game pipeline swap; not adding player" }
                return false
            }
            val loop = channel.eventLoop()
            if (loop.inEventLoop()) return doSwap(channel, username)

            return try {
                loop.submit<Boolean> { doSwap(channel, username) }
                    .get(3, java.util.concurrent.TimeUnit.SECONDS)
            } catch (e: Exception) {
                swapLogger.warn(e) { "Pipeline swap for $username did not complete on the event loop; not adding player" }
                false
            }
        }

        private fun doSwap(channel: Channel, username: String): Boolean {
            try {
                channel.pipeline().replace("login-decoder", "game-decoder", GamePacketFraming())
                channel.pipeline().replace("login-encoder", "game-encoder", GamePacketEncoder())
                channel.pipeline().replace("login-handler", "game-handler", DynamicPacketHandler())
            } catch (e: NoSuchElementException) {
                swapLogger.warn { "Login pipeline for $username was already torn down (client raced a disconnect); not adding player" }
                return false
            }
            return true
        }
    }

    override fun channelRead0(ctx: ChannelHandlerContext, msg: LoginPacket) {
        when {
            msg is LoginPacket.LobbyLoginRequest || msg is LoginPacket.GameLoginRequest -> handleLoginRequest(ctx, msg)
            msg is LoginPacket.GameLoginContinue -> handleGameLoginContinue(ctx)
            else -> throw IllegalStateException("idk how to handle $msg!")
        }
    }

    private fun handleLoginRequest(ctx: ChannelHandlerContext, msg: LoginPacket) {
        val header = if (msg is LoginPacket.LobbyLoginRequest) msg.header
        else (msg as LoginPacket.GameLoginRequest).header

        ctx.channel().attr(RSChannelAttributes.INCOMING_ISAAC).set(ISAACCipher(header.seeds))
        ctx.channel().attr(RSChannelAttributes.OUTGOING_ISAAC)
            .set(ISAACCipher(header.seeds.map { it + 50 }.toIntArray()))

        LoginThread.login(msg, ctx.channel()) {
            if (it.result.code == GenericResponse.SUCCESSFUL) {
                ctx.channel().attr(RSChannelAttributes.LOGIN_AUTHENTICATED_USERNAME).set(it.username)
                ctx.channel().attr(RSChannelAttributes.LOGIN_AUTHENTICATED).set(true)
            }
            val future = ctx.channel().writeAndFlush(Unpooled.buffer(1).writeByte(it.result.code.id))
            if (it.result.code != GenericResponse.SUCCESSFUL) {
                future.addListener(ChannelFutureListener.CLOSE)
                return@login
            }

            if (ctx.channel().attr(RSChannelAttributes.PASSTHROUGH_CHANNEL).get() != null) {
                logger.info { "Login was OK for proxy connection [client->open nxt], leaving channel management to proxy..." }
            } else {
                if (ctx.channel().attr(RSChannelAttributes.LOGIN_TYPE).get() == LoginType.GAME) {
                    val map = Int2IntOpenHashMap()
                    TODORefactorThisClass.populateServerpermVarcs(map)
                    val response = LoginPacket.ServerpermVarcChunk(true, map)
                    ctx.channel().writeAndFlush(response)

                    logger.info { "Sending ${map.size} serverperm varcs" }

                    return@login
                }

                val response = LoginPacket.LobbyLoginResponse.forAccount(it.username, OpenNXT.config)
                logger.debug { "Sending lobby login response: $response" }

                ctx.channel().writeAndFlush(response).addListener { future ->
                    if (!future.isSuccess) {
                        logger.error(future.cause()) { "Failed to write login response" }
                        ctx.channel().close()
                        return@addListener
                    }

                    if (!swapToGamePipeline(ctx.channel(), it.username)) return@addListener

                    val player = LobbyPlayer(ctx.channel().attr(RSChannelAttributes.CONNECTED_CLIENT).get(), it.username)

                    OpenNXT.lobby.addPlayer(player)
                }

                logger.info { "Login on [SERVER] completed" }
            }
        }
    }

    private fun handleGameLoginContinue(ctx: ChannelHandlerContext) {
        val username = ctx.channel().attr(RSChannelAttributes.LOGIN_USERNAME).get()
        val authedName = ctx.channel().attr(RSChannelAttributes.LOGIN_AUTHENTICATED_USERNAME).get()
        if (ctx.channel().attr(RSChannelAttributes.LOGIN_AUTHENTICATED).get() != true ||
            ctx.channel().attr(RSChannelAttributes.LOGIN_TYPE).get() != LoginType.GAME ||
            username == null || authedName == null || authedName != username
        ) {
            logger.error {
                "GAMELOGIN_CONTINUE for '$username' on a channel authenticated as '$authedName'; closing"
            }
            ctx.channel().close()
            return
        }

        val world = OpenNXT.world
        if (!world.reserveSession(username)) {
            logger.warn {
                "Refused GAMELOGIN_CONTINUE for '$username': account already logged in; " +
                    "sent ${GenericResponse.LOGGED_IN} (${GenericResponse.LOGGED_IN.id}) and closed"
            }
            ctx.channel().writeAndFlush(Unpooled.buffer(1).writeByte(GenericResponse.LOGGED_IN.id))
                .addListener(ChannelFutureListener.CLOSE)
            return
        }

        val player = try {
            val save = AccountStore.instance.loadSave(username) ?: PlayerSave.fromNew(username)
            WorldPlayer(
                ctx.channel().attr(RSChannelAttributes.CONNECTED_CLIENT).get(),
                username,
                PlayerEntity(TileLocation(save.x, save.y, save.plane)),
                save.xp
            )
        } catch (t: Throwable) {
            world.releaseSession(username)
            logger.error(t) { "Failed to build the world player for '$username'; released the session claim" }
            ctx.channel().close()
            return
        }

        if (!world.addPlayer(player)) {
            world.releaseSession(username)
            ctx.channel().close()
        }
    }
}

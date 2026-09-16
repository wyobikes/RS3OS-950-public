package com.opennxt.net.login

import com.opennxt.config.RsaConfig
import com.opennxt.ext.decipherXtea
import com.opennxt.ext.readBuild
import com.opennxt.ext.readString
import com.opennxt.net.DiagnosticLog
import com.opennxt.net.GenericResponse
import com.opennxt.net.RSChannelAttributes
import com.opennxt.net.login.LoginRSAHeader.Companion.readLoginHeader
import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import io.netty.channel.ChannelFutureListener
import io.netty.channel.ChannelHandlerContext
import io.netty.handler.codec.ByteToMessageDecoder
import mu.KotlinLogging
import kotlin.system.exitProcess

class LoginServerDecoder(val rsaPair: RsaConfig.RsaKeyPair) : ByteToMessageDecoder() {
    private val logger = KotlinLogging.logger { }

    override fun decode(ctx: ChannelHandlerContext, buf: ByteBuf, out: MutableList<Any>) {
        buf.markReaderIndex()

        val id = buf.readUnsignedByte().toInt()
        val type = LoginType.fromId(id)
        if (type == null) {
            logger.warn("Client from ${ctx.channel().remoteAddress()} attempted to login with unknown id: $id")
            if (DiagnosticLog.enabled) {
                DiagnosticLog.bytes(
                    ctx.channel(), DiagnosticLog.Stage.LOGIN,
                    "Unknown login type $id (0x${Integer.toHexString(id)}), closing; raw frame",
                    byteArrayOf(id.toByte()) + DiagnosticLog.snapshot(buf, DiagnosticLog.DUMP_LIMIT - 1),
                    buf.readableBytes() + 1
                )
                DiagnosticLog.reason(ctx.channel(), "unknown login type $id")
            }
            buf.skipBytes(buf.readableBytes())
            ctx.close()
            return
        }

        if (type == LoginType.GAMELOGIN_CONTINUE) {
            val loginType = ctx.channel().attr(RSChannelAttributes.LOGIN_TYPE).get()
            val authenticated = ctx.channel().attr(RSChannelAttributes.LOGIN_AUTHENTICATED).get() == true
            val authedName = ctx.channel().attr(RSChannelAttributes.LOGIN_AUTHENTICATED_USERNAME).get()
            val framedName = ctx.channel().attr(RSChannelAttributes.LOGIN_USERNAME).get()
            val sameName = authedName != null && authedName == framedName
            if (loginType != LoginType.GAME || !authenticated || !sameName) {
                logger.error {
                    "Rejected GAMELOGIN_CONTINUE from ${ctx.channel().remoteAddress()}: " +
                        (if (loginType == null) "no login frame seen"
                        else if (loginType != LoginType.GAME) "login type is $loginType, not GAME"
                        else if (!authenticated) "not authenticated"
                        else "username does not match the authenticated one") +
                        "; closing"
                }
                buf.skipBytes(buf.readableBytes())
                ctx.channel().close()
                return
            }

            if (ctx.channel().attr(RSChannelAttributes.PASSTHROUGH_CHANNEL).get() == null) {
                out.add(LoginPacket.GameLoginContinue)
            }
            return
        }

        if (buf.readableBytes() < 2) {
            buf.resetReaderIndex()
            return
        }

        val length = buf.readUnsignedShort()
        if (buf.readableBytes() < length) {
            buf.resetReaderIndex()
            return
        }

        if (ctx.channel().attr(RSChannelAttributes.LOGIN_AUTHENTICATED).get() == true) {
            logger.error { "Rejected a second login frame from ${ctx.channel().remoteAddress()} on an authenticated channel; closing" }
            ctx.channel().attr(RSChannelAttributes.LOGIN_AUTHENTICATED).set(false)
            ctx.channel().attr(RSChannelAttributes.LOGIN_AUTHENTICATED_USERNAME).set(null)
            buf.skipBytes(buf.readableBytes())
            ctx.channel().close()
            return
        }
        ctx.channel().attr(RSChannelAttributes.LOGIN_AUTHENTICATED).set(false)
        ctx.channel().attr(RSChannelAttributes.LOGIN_AUTHENTICATED_USERNAME).set(null)
        ctx.channel().attr(RSChannelAttributes.LOGIN_TYPE).set(type)

        val payload = buf.readBytes(length)

        val diagRaw: ByteArray? = if (DiagnosticLog.enabled) DiagnosticLog.snapshot(payload) else null
        if (diagRaw != null) {
            DiagnosticLog.bytes(
                ctx.channel(), DiagnosticLog.Stage.LOGIN,
                "LOGIN FRAME type=$type typeByte=$id declaredLength=$length rsaKeyBits=${rsaPair.modulus.bitLength()}",
                diagRaw, length
            )
        }

        try {
            val build = payload.readBuild()
            if (diagRaw != null) {
                DiagnosticLog.buildAnnounced(
                    ctx.channel(), DiagnosticLog.Stage.LOGIN, build.major,
                    "login frame type=$type, minor=${build.minor}"
                )
            }
            val header = payload.readLoginHeader(type, rsaPair.exponent, rsaPair.modulus)

            if (header !is LoginRSAHeader.Fresh && type == LoginType.LOBBY) {
                logger.error { "reconnecting block on a lobby login" }
                if (diagRaw != null) {
                    DiagnosticLog.bytes(
                        ctx.channel(), DiagnosticLog.Stage.LOGIN,
                        "Login rejected (MALFORMED_PACKET): reconnecting block on a lobby login; raw frame",
                        diagRaw, length
                    )
                    DiagnosticLog.reason(ctx.channel(), "login rejected: reconnecting block on a lobby login")
                }
                ctx.channel()
                    .writeAndFlush(LoginPacket.LoginResponse(GenericResponse.MALFORMED_PACKET))
                    .addListener(ChannelFutureListener.CLOSE)
                return
            }

            payload.decipherXtea(header.seeds)

            payload.markReaderIndex()
            val original = ByteArray(payload.readableBytes())
            payload.readBytes(original)
            payload.resetReaderIndex()

            payload.skipBytes(1)
            val name = payload.readString()

            ctx.channel().attr(RSChannelAttributes.LOGIN_USERNAME).set(name)
            if (header.uniqueId != ctx.channel().attr(RSChannelAttributes.LOGIN_UNIQUE_ID).get()) {
                logger.error { "Unique id mismatch - possible replay attack?" }
                if (diagRaw != null) {
                    DiagnosticLog.bytes(
                        ctx.channel(), DiagnosticLog.Stage.LOGIN,
                        "Login rejected (MALFORMED_PACKET): unique id mismatch " +
                            "(block=${header.uniqueId}, issued=${ctx.channel().attr(RSChannelAttributes.LOGIN_UNIQUE_ID).get()}); raw frame",
                        diagRaw, length
                    )
                    DiagnosticLog.reason(ctx.channel(), "login rejected: unique id mismatch")
                }
                ctx.channel()
                    .writeAndFlush(LoginPacket.LoginResponse(GenericResponse.MALFORMED_PACKET))
                    .addListener(ChannelFutureListener.CLOSE)
                return
            }

            when (type) {
                LoginType.LOBBY -> {
                    header as LoginRSAHeader.Fresh

                    logger.info { "Attempted lobby login: $name, *****" }
                    out.add(
                        LoginPacket.LobbyLoginRequest(
                            build,
                            header,
                            name,
                            header.password,
                            Unpooled.wrappedBuffer(original)
                        )
                    )
                }
                LoginType.GAME -> {
                    val password = if (header is LoginRSAHeader.Fresh) header.password else ""
                    if (header !is LoginRSAHeader.Fresh)
                        logger.warn {
                            "Game login for $name is a reconnect; treating it as a fresh login"
                        }
                    else
                        logger.info { "Attempted game login: $name, *****" }

                    out.add(
                        LoginPacket.GameLoginRequest(
                            build,
                            header,
                            name,
                            password,
                            Unpooled.wrappedBuffer(original)
                        )
                    )
                }
                else -> throw IllegalStateException("Unhandled login type $type")
            }
        } catch (e: Exception) {
            logger.error(e) {
                "Could not parse login frame from ${ctx.channel().remoteAddress()}; closing"
            }
            if (diagRaw != null) {
                DiagnosticLog.bytes(
                    ctx.channel(), DiagnosticLog.Stage.LOGIN,
                    "Login parse failure ${e.javaClass.name}: ${e.message}; raw frame " +
                        "(type=$type typeByte=$id declaredLength=$length)",
                    diagRaw, length
                )
                DiagnosticLog.reason(ctx.channel(), "login parse failure: ${e.javaClass.simpleName}")
            }
            ctx.channel()
                .writeAndFlush(LoginPacket.LoginResponse(GenericResponse.MALFORMED_PACKET))
                .addListener(ChannelFutureListener.CLOSE)
        } finally {
            payload.release()
        }
    }
}

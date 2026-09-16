package com.opennxt.net.http

import com.opennxt.net.DiagnosticLog
import com.opennxt.net.http.endpoints.JavConfigWsEndpoint
import com.opennxt.net.http.endpoints.ClientFileEndpoint
import com.opennxt.net.http.endpoints.Js5MsEndpoint
import io.netty.channel.ChannelHandler
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.SimpleChannelInboundHandler
import io.netty.handler.codec.http.FullHttpRequest
import io.netty.handler.codec.http.HttpMethod
import io.netty.handler.codec.http.HttpResponseStatus
import io.netty.handler.codec.http.QueryStringDecoder
import mu.KotlinLogging

@ChannelHandler.Sharable
class HttpRequestHandler : SimpleChannelInboundHandler<FullHttpRequest>() {
    private val logger = KotlinLogging.logger { }

    private fun note(ctx: ChannelHandlerContext, line: String) {
        val peer = ctx.channel().remoteAddress()?.toString() ?: "?"
        logger.info { "HTTP $peer $line" }
        DiagnosticLog.http(ctx.channel(), line)
    }

    override fun channelRead0(ctx: ChannelHandlerContext, msg: FullHttpRequest) {
        if (!msg.decoderResult().isSuccess) {
            note(ctx, "MALFORMED REQUEST -> 400 (${msg.decoderResult()})")
            ctx.sendHttpError(HttpResponseStatus.BAD_REQUEST)
            return
        }

        if (msg.method() != HttpMethod.GET) {
            note(ctx, "${msg.method()} ${msg.uri()} -> 405 (only GET is served)")
            ctx.sendHttpError(HttpResponseStatus.METHOD_NOT_ALLOWED)
            return
        }
        val uri = msg.uri()
        val query = QueryStringDecoder(uri)

        note(ctx, "GET $uri  [headers: ${msg.headers().names().joinToString(",")}]")

        when {
            query.path() == "/jav_config.ws" -> JavConfigWsEndpoint.handle(ctx, msg, query)
            query.path() == "/client" -> ClientFileEndpoint.handle(ctx, msg, query)
            query.path() == "/ms" -> Js5MsEndpoint.handle(ctx, msg, query)
            else -> {
                note(ctx, "  ... no endpoint for ${query.path()} -> 404")
                ctx.sendHttpError(HttpResponseStatus.NOT_FOUND)
            }
        }
    }

    override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
        note(ctx, "EXCEPTION on the http path: ${cause::class.java.name}: ${cause.message}")
        logger.error(cause) { "Exception handling an HTTP request" }
        if (ctx.channel().isActive) {
            ctx.sendHttpError(HttpResponseStatus.INTERNAL_SERVER_ERROR)
        }
    }
}

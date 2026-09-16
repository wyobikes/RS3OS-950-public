package com.opennxt.net.http.endpoints

import com.opennxt.OpenNXT
import com.opennxt.filesystem.Container
import com.opennxt.model.files.BinaryType
import com.opennxt.model.files.FileChecker
import com.opennxt.net.http.sendHttpError
import com.opennxt.net.http.sendHttpFile
import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import io.netty.channel.ChannelFutureListener
import io.netty.channel.ChannelHandlerContext
import io.netty.handler.codec.http.*
import mu.KotlinLogging

object Js5MsEndpoint {
    private val logger = KotlinLogging.logger { }

    fun handle(ctx: ChannelHandlerContext, msg: FullHttpRequest, query: QueryStringDecoder) {
        if (!query.parameters().containsKey("a") || !query.parameters().containsKey("g")) {
            ctx.sendHttpError(HttpResponseStatus.BAD_REQUEST)
            return
        }

        val index: Int = try {
            query.parameters().getValue("a").first().toInt()
        } catch (e: NumberFormatException) {
            ctx.sendHttpError(HttpResponseStatus.BAD_REQUEST)
            return
        }

        val archive: Int = try {
            query.parameters().getValue("g").first().toInt()
        } catch (e: NumberFormatException) {
            ctx.sendHttpError(HttpResponseStatus.BAD_REQUEST)
            return
        }

        val data: ByteBuf? = when {
            index == 255 && archive == 255 -> Unpooled.wrappedBuffer(OpenNXT.httpChecksumTable)
            index == 255 -> OpenNXT.filesystem.readReferenceTable(archive)
                ?.let { Unpooled.wrappedBuffer(Container.reframeForClient(it)) }
            else -> OpenNXT.filesystem.read(index, archive)
                ?.let { Unpooled.wrappedBuffer(Container.reframeForClient(it)) }
        }

        if (data == null) {
            logger.info { "js5 http: no data for [$index, $archive] - 404" }
            ctx.sendHttpError(HttpResponseStatus.NOT_FOUND)
            return
        }

        sendFile(msg, ctx, data)
    }

    private fun sendFile(request: FullHttpRequest, ctx: ChannelHandlerContext, buf: ByteBuf) {
        val size = buf.readableBytes()
        val response = DefaultFullHttpResponse(request.protocolVersion(), HttpResponseStatus.OK, buf)
        response.headers().set(HttpHeaderNames.SERVER, "JaGeX/3.1")
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/octet-stream")
        response.headers().set(HttpHeaderNames.CONTENT_LENGTH, size)

        ctx.channel().writeAndFlush(response).addListener(ChannelFutureListener.CLOSE)
    }
}

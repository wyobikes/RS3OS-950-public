package com.opennxt.net.http.endpoints

import com.opennxt.OpenNXT
import com.opennxt.model.files.BinaryType
import com.opennxt.model.files.ClientConfig
import com.opennxt.model.files.ClientParams
import com.opennxt.model.files.FileChecker
import com.opennxt.net.DiagnosticLog
import com.opennxt.net.http.sendHttpError
import com.opennxt.net.http.sendHttpText
import io.netty.channel.ChannelHandlerContext
import io.netty.handler.codec.http.FullHttpRequest
import io.netty.handler.codec.http.HttpResponseStatus
import io.netty.handler.codec.http.QueryStringDecoder

object JavConfigWsEndpoint {
    private val logger = mu.KotlinLogging.logger { }

    fun handle(ctx: ChannelHandlerContext, msg: FullHttpRequest, query: QueryStringDecoder) {
        val typeRaw = query.parameters()["binaryType"]?.firstOrNull() ?: "2"
        val typeIndex = typeRaw.toIntOrNull()
        if (typeIndex == null || typeIndex !in BinaryType.values().indices) {
            logger.warn {
                "jav_config request from ${ctx.channel().remoteAddress()} has binaryType='$typeRaw'; " +
                    "expected 0..${BinaryType.values().size - 1}"
            }
            ctx.sendHttpError(HttpResponseStatus.BAD_REQUEST)
            return
        }
        val type = BinaryType.values()[typeIndex]

        val config = FileChecker.getConfig("compressed", type)
        if (config == null) {
            logger.warn { "No staged jav_config for binary type $type - answering 404" }
            ctx.sendHttpError(HttpResponseStatus.NOT_FOUND)
            return
        }
        if (!OpenNXT.enableProxySupport) {
            val params = ClientParams.build(OpenNXT.config)
            for ((index, value) in params) config["param=$index"] = value
            DiagnosticLog.http(
                ctx.channel(),
                "serving jav_config for $type: ${config.entries.size} entries, " +
                    "${params.size} generated params (mandatory ${ClientParams.MANDATORY.sorted()})"
            )
            ctx.sendHttpText(config.toString().toByteArray(Charsets.ISO_8859_1))
            return
        }

        val liveConfig = ClientConfig.download("https://world5.runescape.com/jav_config.ws", type)

        var download = 0
        while (config.entries.containsKey("download_name_$download")) {
            liveConfig.entries["download_name_$download"] = config.entries.getValue("download_name_$download")
            liveConfig.entries["download_crc_$download"] = config.entries.getValue("download_crc_$download")
            liveConfig.entries["download_hash_$download"] = config.entries.getValue("download_hash_$download")
            download++
        }

        liveConfig["codebase"] = "http://${OpenNXT.config.hostname}/"

        for (i in 0..liveConfig.highestParam) {
            val value = liveConfig.getParam(i) ?: continue

            if (value.contains("runescape.com") || value.contains("jagex.com")) {
                liveConfig["param=$i"] = OpenNXT.config.hostname
            }
        }

        ctx.sendHttpText(liveConfig.toString().toByteArray(Charsets.ISO_8859_1))
    }
}

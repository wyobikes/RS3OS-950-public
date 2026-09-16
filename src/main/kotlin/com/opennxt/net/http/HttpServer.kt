package com.opennxt.net.http

import com.opennxt.config.ServerConfig
import com.opennxt.model.files.ClientParams
import com.opennxt.model.files.FileChecker
import io.netty.bootstrap.ServerBootstrap
import io.netty.channel.ChannelInitializer
import io.netty.channel.ChannelOption
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.NioServerSocketChannel
import io.netty.handler.codec.http.HttpObjectAggregator
import io.netty.handler.codec.http.HttpServerCodec
import mu.KotlinLogging
import kotlin.system.exitProcess

class HttpServer(val config: ServerConfig) : AutoCloseable {
    private val logger = KotlinLogging.logger {}
    private var initialized = false

    private val handler = HttpRequestHandler()
    private val httpBootstrap = ServerBootstrap()
        .group(NioEventLoopGroup())
        .channel(NioServerSocketChannel::class.java)
        .childHandler(HttpChannelInitializer(handler))
        .childOption(ChannelOption.SO_REUSEADDR, true)
        .childOption(ChannelOption.TCP_NODELAY, true)
        .childOption(ChannelOption.CONNECT_TIMEOUT_MILLIS, 30_000)

    fun init(skipFileChecks: Boolean) {
        if (skipFileChecks) {
            logger.info { "Skipping http file verification" }
        } else {
            FileChecker.checkFiles("compressed")
        }

        initialized = true
    }

    fun bind(httpPort: Int = config.ports.http) {
        check(initialized) { "Attempted to bind http server before initializing" }

        logger.info { "Binding http server to 0.0.0.0:$httpPort" }

        val result = httpBootstrap.bind("0.0.0.0", httpPort).await()
        if (!result.isSuccess) {
            logger.error(result.cause()) { "Failed to bind to 0.0.0.0:$httpPort" }
            exitProcess(1)
        }

        logger.info { "Http server bound to 0.0.0.0:$httpPort" }

        bindContentPort()
    }

    private fun bindContentPort() {
        val offset = System.getProperty("opennxt.http.contentPortOffset")?.toIntOrNull() ?: 7000
        val endpointId = ClientParams.build(config)["38"]?.toIntOrNull()
        if (endpointId == null) {
            logger.warn { "No param 38 in the generated client params - cannot derive the content port" }
            return
        }

        val contentPort = endpointId + offset
        if (contentPort == config.ports.http) {
            logger.info { "Client content port $contentPort is already bound" }
            return
        }
        if (contentPort !in 1..65535) {
            logger.warn { "Derived content port $contentPort (param 38 = $endpointId, + $offset) is out of range" }
            return
        }

        logger.info { "Binding http server to 0.0.0.0:$contentPort (client content port: param 38 = $endpointId, + $offset)" }
        val result = httpBootstrap.bind("0.0.0.0", contentPort).await()
        if (!result.isSuccess) {
            logger.warn(result.cause()) {
                "Could not bind content port $contentPort; the client cannot load the cache without it. " +
                    "Set -Dopennxt.http.contentPortOffset=<n> to change it."
            }
            return
        }
        logger.info { "Http server also bound to 0.0.0.0:$contentPort" }
    }

    override fun close() {
        logger.warn { "TODO - Close http server connections" }
    }

    private class HttpChannelInitializer(val handler: HttpRequestHandler) : ChannelInitializer<SocketChannel>() {
        override fun initChannel(ch: SocketChannel) {
            ch.pipeline().addLast("codec", HttpServerCodec())
            ch.pipeline().addLast("aggregator", HttpObjectAggregator(65536))
            ch.pipeline().addLast("handler", handler)
        }
    }
}

package com.opennxt.tools.impl.cachedownloader

import mu.KotlinLogging
import java.net.HttpURLConnection
import java.net.URL

class Js5HttpRequest(val url: URL, val request: Js5RequestHandler.ArchiveRequest) : Runnable {
    private val logger = KotlinLogging.logger { }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 30_000

        private val attempts: Int =
            System.getProperty("opennxt.http.attempts")?.toIntOrNull()?.coerceAtLeast(1) ?: 4
    }

    private fun fetch(): ByteArray {
        val connection = url.openConnection() as HttpURLConnection
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
        connection.requestMethod = "GET"
        connection.instanceFollowRedirects = true
        try {
            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                throw java.io.IOException("HTTP $code ${connection.responseMessage ?: ""}".trim())
            }
            return connection.inputStream.use { it.readBytes() }
        } finally {
            connection.disconnect()
        }
    }

    override fun run() {
        var lastError: Exception? = null

        for (attempt in 1..attempts) {
            try {
                val data = fetch()

                request.allocateBuffer(data.size + 2)
                request.buffer!!.put(data, 0, data.size)
                request.buffer!!.flip()
                request.crashed = false
                request.notifyCompleted()
                return
            } catch (e: Exception) {
                lastError = e
                if (attempt < attempts) {
                    try {
                        Thread.sleep(500L * attempt)
                    } catch (ie: InterruptedException) {
                        Thread.currentThread().interrupt()
                        break
                    }
                }
            }
        }

        logger.warn {
            "HTTP download failed after $attempts attempts for [${request.index}, ${request.archive}]: " +
                "${lastError?.javaClass?.simpleName}: ${lastError?.message} ($url)"
        }
        request.crashed = true
    }
}

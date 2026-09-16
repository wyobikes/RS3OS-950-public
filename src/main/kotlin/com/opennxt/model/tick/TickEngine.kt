package com.opennxt.model.tick

import com.google.common.util.concurrent.ThreadFactoryBuilder
import mu.KotlinLogging
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class TickEngine {
    private val logger = KotlinLogging.logger { }

    private val executor = Executors.newScheduledThreadPool(
        1,
        ThreadFactoryBuilder()
            .setNameFormat("tick-engine")
            .setUncaughtExceptionHandler { t, e -> logger.error("Error with thread $t", e) }
            .build())

    private val warnMs: Long = System.getProperty("opennxt.tick.warnMs")?.toLongOrNull() ?: 600L

    private val overrunCount = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicLong>()

    fun submitTickable(tickable: Tickable) {
        executor.scheduleAtFixedRate({
            val started = System.nanoTime()
            try {
                tickable.tick()
            } catch (t: Throwable) {
                logger.error(t) {
                    "Uncaught throwable in ${tickable.javaClass.name}.tick(); continuing"
                }
            } finally {
                val tookMs = (System.nanoTime() - started) / 1_000_000
                if (tookMs >= warnMs) {
                    val name = tickable.javaClass.simpleName
                    val n = overrunCount.computeIfAbsent(name) { java.util.concurrent.atomic.AtomicLong() }
                        .incrementAndGet()
                    if (java.lang.Long.bitCount(n) == 1) {
                        logger.warn {
                            "Tick overrun: $name.tick() took ${tookMs}ms (limit ${warnMs}ms, occurrence $n); " +
                                "-Dopennxt.tick.warnMs=<n> sets the limit"
                        }
                    }
                }
            }
        }, 0, 600, TimeUnit.MILLISECONDS)
    }

    fun executeAsync(delay: Long = 0L, runnable: () -> Unit) {
        if (delay <= 0L) executor.execute(runnable)
        else executor.schedule(runnable, delay, TimeUnit.MILLISECONDS)
    }

}

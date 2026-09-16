package com.opennxt

import com.opennxt.net.DiagnosticLog
import com.opennxt.net.js5.Js5Session
import mu.KotlinLogging
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

object Js5Thread: Thread("js5-thread") {
    private val running = AtomicBoolean(true)
    private val logger = KotlinLogging.logger {  }
    private val sessions = CopyOnWriteArrayList<Js5Session>()

    override fun run() {
        while (running.get()) {
            var served = 0L

            for (session in sessions) {
                try {
                    served += session.process(10_000_000)
                } catch (t: Throwable) {
                    failures++
                    logger.error(t) { "js5 session ${session.channel.remoteAddress()} threw while serving; dropping it and continuing" }
                    DiagnosticLog.reason(
                        session.channel,
                        "js5 serve threw ${t.javaClass.name}: ${t.message} - session dropped, js5 thread SURVIVES"
                    )
                    try { session.close() } catch (ignored: Throwable) { }
                    sessions.remove(session)
                }
            }

            if (served == 0L) sleep(5)
        }
        logger.error { "js5 thread has exited its loop - JS5 serving is now dead for every session" }
    }

    @Volatile
    var failures: Int = 0
        private set

    fun addSession(session: Js5Session) {
        sessions.add(session)
    }

    fun removeSession(session: Js5Session) {
        sessions.remove(session)
    }

}

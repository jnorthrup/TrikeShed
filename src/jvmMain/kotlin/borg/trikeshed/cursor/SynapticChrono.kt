package borg.trikeshed.cursor

import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * SynapticChrono — timer driver for SynapseRing speculative underrun flush.
 *
 * ScheduledExecutorService ticks at intervalMs.
 * On each tick: if ring has events (0 < count < capacity) → flush.
 * Fire (count == capacity) flushes immediately from publish() hot path.
 * Timer handles the speculative underrun path.
 *
 * Daemon thread — won't prevent JVM exit.
 */
class SynapticChrono(
    val ring: SynapseRing,
    val intervalMs: Long = 50L,
) {
    private var timer: ScheduledExecutorService? = null
    private var timerTask: ScheduledFuture<*>? = null

    fun start() {
        stop()
        timer = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "synapse-timer").apply { isDaemon = true }
        }
        timerTask = timer!!.scheduleAtFixedRate({
            try {
                ring.timeoutFlush()
            } catch (e: Exception) {
                // timer must not kill the synapse
            }
        }, intervalMs, intervalMs, TimeUnit.MILLISECONDS)
    }

    fun stop() {
        timerTask?.cancel(false)
        timerTask = null
        timer?.shutdownNow()
        timer = null
    }
}
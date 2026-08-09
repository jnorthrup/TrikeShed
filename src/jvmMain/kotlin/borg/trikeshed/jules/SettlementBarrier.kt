package borg.trikeshed.jules

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Per-session barrier between concurrent turn execution and forced eviction.
 *
 * A turn acquires [enter] and releases the returned idempotent handle in a
 * finally block. [awaitSettlement] closes admission, waits for every acquired
 * turn to yield, and reopens admission after settlement or timeout.
 */
class SettlementBarrier {
    private val monitor = Any()
    private var activeCount = 0
    private var settling = false
    private var drain: CompletableDeferred<Unit>? = null

    fun enter(): () -> Unit {
        synchronized(monitor) {
            check(!settling) { "Cannot acquire turn slot: settlement sequence in progress" }
            activeCount++
        }
        var released = false
        return release@{
            synchronized(monitor) {
                if (released) return@release
                released = true
                activeCount--
                check(activeCount >= 0) { "SettlementBarrier active count underflow" }
                if (activeCount == 0) drain?.complete(Unit)
            }
        }
    }

    suspend fun awaitSettlement(timeoutMs: Long): Boolean {
        val pending = synchronized(monitor) {
            if (activeCount == 0) return true
            settling = true
            drain ?: CompletableDeferred<Unit>().also { drain = it }
        }
        return try {
            withTimeoutOrNull(timeoutMs) {
                pending.await()
                true
            } ?: false
        } finally {
            synchronized(monitor) {
                if (drain === pending) {
                    drain = null
                    settling = false
                }
            }
        }
    }

    val pendingTurns: Int get() = synchronized(monitor) { activeCount }
}

/**
 * Wait for active turns, flush memory indices, then retire the session.
 * Timeout permits forced detachment but is surfaced to the caller.
 */
suspend fun evictStalledSession(
    sessionId: String,
    barrier: SettlementBarrier,
    flushIndex: suspend () -> Unit,
    evict: suspend () -> Unit,
): Boolean {
    val settled = barrier.awaitSettlement(5_000L)
    if (!settled) {
        System.err.println(
            "[OROBOROS] Session $sessionId failed to settle within timeout; " +
                "forcing eviction with ${barrier.pendingTurns} pending turns."
        )
    }
    flushIndex()
    evict()
    return settled
}

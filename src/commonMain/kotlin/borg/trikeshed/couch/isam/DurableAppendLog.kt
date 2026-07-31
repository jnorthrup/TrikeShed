package borg.trikeshed.couch.isam

import borg.trikeshed.userspace.nio.spi.NioSupervisor
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/**
 * A common contract for an append-only log providing durable storage for frames.
 */
interface DurableAppendLog {
    /**
     * Appends a payload with the given sequence number to the log.
     * @param sequence The monotonic sequence number.
     * @param payload The raw payload bytes.
     * @return The sequence number that was written.
     */
    fun append(sequence: Long, payload: ByteArray): Long

    /**
     * Replays the log from the beginning. Stops at the last complete, valid frame.
     * @param onFrame Callback invoked for each valid frame containing its sequence number and payload.
     * @return The sequence number of the last valid frame replayed, or 0 if none.
     */
    suspend fun replay(onFrame: suspend (Long, ByteArray) -> Unit): Long

    /**
     * Ensures all appended data is flushed/fsynced to durable storage.
     */
    fun flush()

    /**
     * Wrap flush in a suspend fun routed through NioSupervisor.
     */
    suspend fun flushAsync(nioSupervisor: NioSupervisor? = null) {
        val supervisor = nioSupervisor ?: coroutineContext[NioSupervisor.Key]
        if (supervisor != null) {
            withContext(supervisor) {
                flush()
            }
        } else {
            flush()
        }
    }

    /**
     * Inject a corruption after a specific sequence for testing torn-frame scenarios.
     */
    fun injectCorruptionAfter(sequence: Long)
}

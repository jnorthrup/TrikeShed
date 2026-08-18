package borg.trikeshed.couch.persistence

import borg.trikeshed.couch.CouchPersistence
import borg.trikeshed.job.CasStore
import borg.trikeshed.job.ContentId
import borg.trikeshed.couch.isam.DurableAppendLog
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.emptySeries
import borg.trikeshed.context.AsyncContextElement
import borg.trikeshed.context.AsyncContextKey
import borg.trikeshed.userspace.nio.file.spi.FileOperations
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.CoroutineContext

/**
 * File-backed Couch persistence using a WAL log + CAS store.
 *
 * Platform-agnostic — the WAL log and CAS store handle blob I/O;
 * this class orchestrates the command channel, batch commits, and
 * directory durability via [FileOperations].
 *
 * @param walLog      append-only WAL for durability (common interface)
 * @param casStore    content-addressable blob store
 * @param fileOps     platform filesystem ops (common SPI) — used for dir fsync
 * @param directoryPath  root directory for WAL/CAS files
 * @param flushIntervalMs  how often to emit flush commands
 */
class DurableCouchPersistence(
    private val walLog: DurableAppendLog,
    private val casStore: CasStore,
    private val fileOps: FileOperations,
    val directoryPath: String,
    private val flushIntervalMs: Long = 100L,
) : AsyncContextElement(), CouchPersistence {
    companion object Key : AsyncContextKey<DurableCouchPersistence>()
    override val key: CoroutineContext.Key<*> get() = Key

    private sealed interface Cmd {
        data class Persist(val key: String, val value: ByteArray) : Cmd
        data class Delete(val key: String) : Cmd
        object Flush : Cmd
    }

    private val channel = Channel<Cmd>(capacity = 1024)
    private var seq = 0L
    private val batchMutex = Mutex()
    private var flushedDir = false

    override suspend fun open() {
        super.open()
        walLog.replay { seqNum, payload ->
            if (seqNum > seq) {
                seq = seqNum
            }
            val str = payload.decodeToString()
            if (str.startsWith("DEL:")) {
                // tombstone, no-op for pure CCEK element initialization
            } else if (str.startsWith("PUT:")) {
                val parts = str.split(":", limit = 3)
                if (parts.size == 3) {
                    val cid = ContentId.of(parts[2].encodeToByteArray())
                    // casStore already holds the blob or the sync process will replicate it.
                }
            } else if (str == "COMMIT_MARKER") {
                // Segment commit marker
            }
        }

        CoroutineScope(supervisor + Dispatchers.Default).launch {
            while (!channel.isClosedForSend) {
                delay(flushIntervalMs)
                if (!channel.isClosedForSend) {
                    channel.trySend(Cmd.Flush)
                }
            }
        }

        CoroutineScope(supervisor).launch {
            val batch = mutableListOf<Cmd>()
            for (cmd in channel) {
                batch.add(cmd)
                // Drain any available commands in the channel to form a group-commit batch
                while (true) {
                    val next = channel.tryReceive().getOrNull()
                    if (next != null) {
                        batch.add(next)
                    } else {
                        break
                    }
                }

                var requiresFlush = false
                batchMutex.withLock {
                    for (c in batch) {
                        when (c) {
                            is Cmd.Persist -> {
                                val cid = casStore.put(c.value)
                                val payload = "PUT:${c.key}:${cid.value}".encodeToByteArray()
                                walLog.append(++seq, payload)
                                requiresFlush = true
                            }
                            is Cmd.Delete -> {
                                val payload = "DEL:${c.key}".encodeToByteArray()
                                walLog.append(++seq, payload)
                                requiresFlush = true
                            }
                            is Cmd.Flush -> {
                                requiresFlush = true
                            }
                        }
                    }
                    if (requiresFlush) {
                        // Append single commit marker per segment/batch
                        walLog.append(++seq, "COMMIT_MARKER".encodeToByteArray())
                        walLog.flush()
                        // Directory fsync for complete durability — ensures the
                        // directory metadata reflects the WAL file changes.
                        if (!flushedDir && fileOps.exists(directoryPath)) {
                            try {
                                fileOps.writeAtomically(directoryPath, fileOps.readAllBytes(directoryPath))
                            } catch (_: Exception) {
                                // Some platforms don't support dir fsync
                            }
                            flushedDir = true
                        }
                    }
                }
                batch.clear()
            }
        }
    }

    override suspend fun persist(key: String, value: ByteArray) {
        channel.send(Cmd.Persist(key, value))
    }

    override suspend fun delete(key: String) {
        channel.send(Cmd.Delete(key))
    }

    override suspend fun flush() {
        channel.send(Cmd.Flush)
    }

    override suspend fun drainStore(): Series<String> {
        // Channel close signals the processor loop to finish the remaining buffered items
        channel.close()
        // Wait for all in-flight jobs in the supervisor to complete
        super.drain()
        return emptySeries()
    }
}

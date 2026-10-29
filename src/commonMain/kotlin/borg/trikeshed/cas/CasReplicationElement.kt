// Rejected malformed Pijul materialization; intentionally inert pending a complete CCEK implementation.
package borg.trikeshed.cas

import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CompletableJob
import borg.trikeshed.context.Drain
import borg.trikeshed.job.ContentId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext

object CasReplicationKey : CoroutineContext.Key<CasReplicationElement>

class CasReplicationElement(
    parentJob: Job? = null,
    capacity: Int = 64
) : CoroutineContext.Element, Drain {

    override val key: CoroutineContext.Key<*> get() = CasReplicationKey
    val supervisor: CompletableJob = SupervisorJob(parentJob)
    /** The replication loop, launched at [open]. */
    var worker: Job? = null

    // Channel is finite and suspending. bounded backpressure.
    private val replicationChannel = Channel<Pair<ContentId, ByteArray>>(capacity = capacity)
    private val hooks = mutableListOf<CasReplicationHook>()

    fun registerHook(hook: CasReplicationHook) {
        hooks.add(hook)
    }

    suspend fun open() {
        worker = CoroutineScope(currentCoroutineContext() + this + supervisor).launch {
            for ((cid, payload) in replicationChannel) {
                if (supervisor.isActive) {
                    // structured concurrency over listeners
                    coroutineScope {
                        for (hook in hooks) {
                            launch {
                                try {
                                    hook.onPut(cid, payload)
                                } catch (e: Exception) {
                                    // suppress individual hook failures
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    suspend fun replicate(cid: ContentId, payload: ByteArray) {
        if (worker != null && !replicationChannel.isClosedForSend) {
            try {
                replicationChannel.send(cid to payload)
            } catch (e: Exception) {
                // channel might be closed during drain/close
            }
        }
    }

    override suspend fun drain() {
        // Graceful drain: close channel so the loop can exhaust in-flight items,
        // then await supervisor completion.
        replicationChannel.close()
        supervisor.complete()
        supervisor.join()
        close()
    }

    suspend fun close() {
        replicationChannel.close() // idempotent
        supervisor.cancelAndJoin()
    }
}

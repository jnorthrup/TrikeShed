<<<<<<< HEAD
// Rejected malformed Pijul materialization; intentionally inert pending a complete CCEK implementation.
=======
package borg.trikeshed.cas

import borg.trikeshed.context.ElementState
import borg.trikeshed.job.ContentId
import borg.trikeshed.userspace.context.AsyncContextElement
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext

object CasReplicationKey : CoroutineContext.Key<CasReplicationElement>

class CasReplicationElement(
    parentJob: Job? = null,
    capacity: Int = 64
) : AsyncContextElement(ElementState.CREATED, parentJob) {

    override val key: CoroutineContext.Key<*> get() = CasReplicationKey

    // Channel is finite and suspending. bounded backpressure.
    private val replicationChannel = Channel<Pair<ContentId, ByteArray>>(capacity = capacity)
    private val hooks = mutableListOf<CasReplicationHook>()

    fun registerHook(hook: CasReplicationHook) {
        hooks.add(hook)
    }

    override suspend fun open() {
        super.open()

        CoroutineScope(supervisor).launch {
            for ((cid, payload) in replicationChannel) {
                if (state.isLessThan(ElementState.CLOSED)) {
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
        if (state.isAtLeast(ElementState.OPEN) && state.isLessThan(ElementState.DRAINING)) {
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
        super.drain()
    }

    override suspend fun close() {
        replicationChannel.close() // idempotent
        super.close()
    }
}
>>>>>>> origin/cas-replication-ccek-2482633503551345018

package borg.trikeshed.kernel

import borg.trikeshed.lib.*
import borg.trikeshed.userspace.nio.process.KernelSeat
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * onegood1's cellular root rule: one removable lifecycle handle per seat, no seat state.
 * The common userspace seat owns dispatch and fan-in; parentage makes the same element ring1.
 */
class KernelElement(
    context: CoroutineContext,
    override val id: KernelId,
    val parentId: KernelId? = null,
) : AbstractCoroutineContextElement(Key), KernelLifecycle {
    companion object Key : CoroutineContext.Key<KernelElement>

    private val supervisor = SupervisorJob(requireNotNull(context[Job]) { "Kernel requires an owning Job" }.also { it.ensureActive() })
    private val context = context + supervisor + this
    private val admission = Mutex()
    private val handles = mutableMapOf<KernelId, KernelLifecycle>()

    suspend fun seat(
        id: KernelId,
        capability: KernelCapability = KernelCapability.COMPUTE,
        parallelism: Int = 1,
        capacity: Int = parallelism,
        pointcut: KernelPointcut? = null,
    ): KernelSeat = admission.withLock {
        check(supervisor.isActive) { "Kernel is draining" }
        check(id !in handles) { "Kernel lifecycle identity already seated: ${id.value}" }
        require(parentId == null || capability == KernelCapability.COMPUTE) {
            "A child kernel cannot seat $capability"
        }
        KernelSeat(context, id, this.id, parentId, capability, parallelism, capacity, pointcut)
            .also { handles[id] = it }
    }

    suspend fun child(id: KernelId): KernelElement = admission.withLock {
        check(supervisor.isActive) { "Kernel is draining" }
        check(id !in handles) { "Kernel lifecycle identity already seated: ${id.value}" }
        KernelElement(context, id, this.id).also { handles[id] = it }
    }

    suspend fun seats(): Series<KernelId> = admission.withLock { handles.keys.toTypedArray().let { ids -> ids.size j { ids[it] } } }

    /** Removal waits for admitted effects and pointcut closure before releasing the single handle. */
    suspend fun remove(id: KernelId): Unit = withContext(NonCancellable) {
        admission.withLock {
            val handle = handles[id] ?: return@withLock
            try { handle.drain() } finally { handles.remove(id) }
        }
    }

    override suspend fun drain(): Unit = withContext(NonCancellable) {
        var failure: Throwable? = null
        admission.withLock {
            for (handle in handles.values) {
                try { handle.drain() }
                catch (cause: Throwable) {
                    if (failure == null) failure = cause else if (failure !== cause) failure!!.addSuppressed(cause)
                }
            }
            handles.clear()
            // A completing supervisor is still active: join it before seat and child observe the refusal.
            supervisor.complete()
            supervisor.join()
        }
        failure?.let { throw it }
    }
}

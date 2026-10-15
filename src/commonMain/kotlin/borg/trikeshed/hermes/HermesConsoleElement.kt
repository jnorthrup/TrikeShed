package borg.trikeshed.hermes

import borg.trikeshed.lcnc.media.ManualMediaInput
import borg.trikeshed.userspace.concurrency.Channel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext

/** One bounded console lane: opening and turns cannot enter the guest concurrently. */
class HermesConsoleElement(
    scope: CoroutineScope,
    private val open: suspend () -> Unit,
    private val execute: suspend (ManualMediaInput) -> Unit,
    private val failed: (ManualMediaInput?, Throwable) -> Unit,
    capacity: Int = 64,
    private val reset: suspend (Boolean) -> Unit = {},
) : CoroutineContext.Element {
    companion object Key : CoroutineContext.Key<HermesConsoleElement>
    override val key: CoroutineContext.Key<*> get() = Key

    private sealed interface Work {
        data object Open : Work
        data class Reset(val resume: Boolean) : Work
        data class Command(val input: ManualMediaInput) : Work
    }

    private val input = Channel.buffered<Work>(capacity.also { require(it > 0) })
    private val supervisor = SupervisorJob(requireNotNull(scope.coroutineContext[Job]) {
        "Hermes console requires an owning Job"
    }.also { it.ensureActive() })
    private val worker = CoroutineScope(scope.coroutineContext + supervisor + this).launch(start = CoroutineStart.UNDISPATCHED) {
        try {
            for (work in input.kChannel) {
                try {
                    when (work) {
                        Work.Open -> open.invoke()
                        is Work.Reset -> reset.invoke(work.resume)
                        is Work.Command -> execute(work.input)
                    }
                } catch (failure: Exception) {
                    failed((work as? Work.Command)?.input, failure)
                    if (failure is CancellationException) throw failure
                }
            }
        } finally {
            input.close()
            while (true) {
                val work = input.kChannel.tryReceive().getOrNull() ?: break
                failed((work as? Work.Command)?.input, CancellationException("console worker stopped before execution"))
            }
        }
    }.also { job -> job.invokeOnCompletion { supervisor.complete() } }

    val accepting: Boolean get() = supervisor.isActive && !input.isClosed()

    fun open(): Boolean = accepting && input.kChannel.trySend(Work.Open).isSuccess
    fun reset(resume: Boolean = true): Boolean = accepting && input.kChannel.trySend(Work.Reset(resume)).isSuccess
    fun submit(command: ManualMediaInput): Boolean = accepting && input.kChannel.trySend(Work.Command(command)).isSuccess

    /** Stop admission, finish every accepted open/turn, and join the owner. No cancellation. */
    suspend fun drain(): Unit = withContext(NonCancellable) {
        input.close()
        worker.join()
        supervisor.join()
    }
}

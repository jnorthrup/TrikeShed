package borg.trikeshed.couch.userspace.nio

import borg.trikeshed.couch.htx.HtxBlock
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel as KChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * One concurrent branch of the Reactor: a named channel + the coroutine
 * that processes it. Lives as a child of the ReactorSupervisor SupervisorJob.
 *
 * This is the "channelized code" — the branch IS the IO routine. The
 * branch reads from its channel and dispatches, never blocking on raw IO.
 *
 * Uses KChannel (kotlinx.coroutines.channels.Channel) explicitly to avoid
 * shadowing with borg.trikeshed.userspace.concurrency.Channel.
 */
class BranchScope(
    val name: String,
    val channel: KChannel<HtxBlock>,
    parentJob: CompletableJob? = null,
) : CompletableJob by SupervisorJob(parentJob) {

    private val _channel = channel

    /**
     * Process loop — reads HtxBlocks from channel and dispatches.
     * Uses receiveCatching() to handle channel close gracefully.
     */
    suspend fun process(dispatch: BranchDispatch): Unit = coroutineScope {
        while (true) {
            val result = _channel.receiveCatching()
            if (!result.isSuccess) break
            dispatch.dispatch(result.getOrThrow())
        }
    }

    /**
     * Launch a processing coroutine that runs until the channel closes.
     */
    fun processAsync(dispatch: BranchDispatch): Job = CoroutineScope(this).launch {
        process(dispatch)
    }
}

/**
 * Tag-based dispatch — not procedural if/else chain.
 * Subclass and override dispatch(block) for custom routing.
 */
abstract class BranchDispatch {
    open fun dispatch(block: HtxBlock) {
        // Default: no-op
    }
}

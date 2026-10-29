package borg.trikeshed.userspace

import kotlinx.coroutines.CoroutineScope

interface ChannelQueue {
    val pendingOps: Int
    val pendingCount: Int
    val waiters: Int
    fun submit()
    suspend fun wait(minComplete: Int)
}

class SplatChannelRunner(
    private val channel: ChannelQueue,
    private val scope: CoroutineScope,
) {
    enum class ChannelOutcome { SUBMIT_MORE, WAIT_COMPLETE, DRAIN, ERROR }

    /** Submits while work is pending, waits while completions are outstanding, returns at DRAIN. */
    suspend fun start() {
        while (true) {
            val outcome = when {
                channel.pendingCount > 0 -> ChannelOutcome.SUBMIT_MORE
                channel.waiters > 0 -> ChannelOutcome.WAIT_COMPLETE
                else -> ChannelOutcome.DRAIN
            }
            when (outcome) {
                ChannelOutcome.SUBMIT_MORE -> channel.submit()
                ChannelOutcome.WAIT_COMPLETE -> channel.wait(minComplete = 1)
                ChannelOutcome.DRAIN -> return
                else -> {}
            }
        }
    }
}

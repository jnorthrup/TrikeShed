package borg.trikeshed.userspace.nio.spi

import kotlinx.coroutines.*

/**
 * Coroutine integration around the userspace Channel.
 */
class ChannelRunner(
    val channel: Channel,
    val scope: CoroutineScope,
) {
    private val pendingOps = mutableMapOf<Long, CompletableDeferred<SelectionResult>>()
    private var running = true

    fun start(): Job = scope.launch {
        while (running) {
            for (result in channel.wait(minComplete = 0)) {
                pendingOps.remove(result.userData)?.complete(result)
            }
            yield()
        }
    }

    fun stop() {
        running = false
    }

    suspend fun runOp(block: (Long) -> Unit): SelectionResult {
        val deferred = CompletableDeferred<SelectionResult>()
        val userData = deferred.hashCode().toLong()
        pendingOps[userData] = deferred
        block(userData)
        channel.submit()
        return deferred.await()
    }
}

@Deprecated("Use ChannelRunner.", ReplaceWith("ChannelRunner"))
typealias UserspaceIORunner = ChannelRunner

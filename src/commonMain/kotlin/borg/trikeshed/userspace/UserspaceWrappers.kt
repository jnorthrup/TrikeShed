package borg.trikeshed.userspace

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext

/**
 * Common high-level DSL and Coroutine integration for Userspace IO.
 */

val CoroutineContext.userspaceRing: UserspaceRing
    get() = this[UserspaceRing.Key] ?: throw IllegalStateException("UserspaceRing not found in context")

class UserspaceIORunner(
    val ring: UserspaceRing,
    val scope: CoroutineScope
) {
    private val pendingOps = mutableMapOf<Long, CompletableDeferred<UserspaceIOResult>>()
    private var running = true

    fun start(): Job = scope.launch {
        while (running) {
            val results = ring.wait(minComplete = 0)
            for (res in results) {
                pendingOps.remove(res.userData)?.complete(res)
            }
            kotlinx.coroutines.yield()
        }
    }

    fun stop() {
        running = false
    }

    suspend fun runOp(block: (Long) -> Unit): UserspaceIOResult {
        val deferred = CompletableDeferred<UserspaceIOResult>()
        val userData = deferred.hashCode().toLong()
        pendingOps[userData] = deferred
        block(userData)
        ring.submit()
        return deferred.await()
    }
}

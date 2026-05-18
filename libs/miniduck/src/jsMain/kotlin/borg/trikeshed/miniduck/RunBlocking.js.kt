package borg.trikeshed.miniduck

import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

actual fun <T> runBlockingCommon(block: suspend () -> T): T {
    var outcome: Result<T>? = null
    block.startCoroutine(object : Continuation<T> {
        override val context = EmptyCoroutineContext

        override fun resumeWith(result: Result<T>) {
            outcome = result
        }
    })
    return outcome?.getOrThrow()
        ?: throw UnsupportedOperationException("runBlockingCommon suspended on JS; this path expects synchronous completion")
}

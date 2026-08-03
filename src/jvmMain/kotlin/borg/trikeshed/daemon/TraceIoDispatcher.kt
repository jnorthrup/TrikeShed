package borg.trikeshed.daemon

import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

object TraceIoDispatcher {
    private val executor = ThreadPoolExecutor(
        1, 1, // single‑threaded to preserve write order
        0L, TimeUnit.MILLISECONDS,
        LinkedBlockingQueue(),
        ThreadFactory { r ->
            Thread(r, "trace-io-dispatcher").apply { isDaemon = true }
        }
    )
    val asCoroutineDispatcher = executor.asCoroutineDispatcher()
}

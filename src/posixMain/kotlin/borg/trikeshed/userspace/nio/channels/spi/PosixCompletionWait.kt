@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package borg.trikeshed.userspace.nio.channels.spi

import borg.trikeshed.lib.*
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.cinterop.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import platform.posix.*
import kotlin.coroutines.EmptyCoroutineContext

/** One readiness wait. The pipe closes only after poll has returned, including cancellation. */
internal class PosixCompletionWait(val interests: List<Join<Int, Int>>) {
    val lock = SynchronizedObject()
    var closed = false
    val descriptors = memScoped {
        val pipe = allocArray<IntVar>(2)
        check(platform.posix.pipe(pipe) == 0) { "completion wake pipe failed: $errno" }
        val read = pipe[0]
        val write = pipe[1]
        try {
            for (fd in listOf(read, write)) {
                check(fcntl(fd, F_SETFL, fcntl(fd, F_GETFL) or O_NONBLOCK) == 0) { "completion wake pipe nonblocking failed: $errno" }
                check(fcntl(fd, F_SETFD, FD_CLOEXEC) == 0) { "completion wake pipe close-on-exec failed: $errno" }
            }
            read j write
        } catch (failure: Throwable) {
            platform.posix.close(read); platform.posix.close(write)
            throw failure
        }
    }

    fun wake() = synchronized(lock) {
        if (!closed) memScoped {
            val byte = alloc<ByteVar>().apply { value = 1 }
            // EAGAIN means the pipe already carries a wakeup.
            var result: Long
            do { result = write(descriptors.b, byte.ptr, 1.convert()).toLong() } while (result < 0 && errno == EINTR)
            check(result >= 0 || errno == EAGAIN) { "completion wake failed: $errno" }
        }
    }

    /** Level-triggered readiness; consumes neither application data nor ring CQEs. */
    fun poll() = memScoped {
        val records = allocArray<pollfd>(interests.size + 1)
        records[0].fd = descriptors.a
        records[0].events = POLLIN.toShort()
        records[0].revents = 0
        for ((i, interest) in interests.withIndex()) {
            records[i + 1].fd = interest.a
            records[i + 1].events = interest.b.toShort()
            records[i + 1].revents = 0
        }
        var result: Int
        do { result = platform.posix.poll(records, (interests.size + 1).convert(), -1) } while (result < 0 && errno == EINTR)
        check(result >= 0) { "completion poll failed: $errno" }
    }

    /** The dispatcher hosts the blocking syscall only; commonMain owns coroutine composition. */
    suspend fun await() {
        val stopped = CompletableDeferred<Unit>()
        try {
            suspendCancellableCoroutine<Unit> { continuation ->
                continuation.invokeOnCancellation { wake() }
                try {
                    Dispatchers.IO.dispatch(EmptyCoroutineContext, Runnable {
                        val result = runCatching { poll() }
                        stopped.complete(Unit)
                        continuation.resumeWith(result)
                    })
                } catch (failure: Throwable) {
                    stopped.complete(Unit)
                    continuation.resumeWith(Result.failure(failure))
                }
            }
        } finally { withContext(NonCancellable) { stopped.await() } }
    }

    fun close() = synchronized(lock) {
        if (!closed) {
            closed = true
            platform.posix.close(descriptors.a)
            platform.posix.close(descriptors.b)
        }
    }
}

package borg.trikeshed.userspace.nio

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Unified NIO reactor ported from literbike.
 */

class Reactor(private val backend: PlatformBackend) {
    private val registrations = mutableMapOf<Int, Long>()
    private val pendingOps = mutableMapOf<Long, CompletableDeferred<Int>>()
    private val mutex = Mutex()
    private var nextUserData = 1L

    suspend fun register(fd: Int, interest: Interest): Long {
        mutex.withLock {
            val token = nextUserData++
            backend.register(fd, token, interest).getOrThrow()
            registrations[fd] = token
            return token
        }
    }

    suspend fun read(fd: Int, buf: ByteArray): Int {
        val userData = mutex.withLock { nextUserData++ }
        val deferred = CompletableDeferred<Int>()
        mutex.withLock { pendingOps[userData] = deferred }
        backend.submitRead(fd, buf, userData).getOrThrow()
        backend.submit().getOrThrow()
        return deferred.await()
    }

    suspend fun write(fd: Int, buf: ByteArray): Int {
        val userData = mutex.withLock { nextUserData++ }
        val deferred = CompletableDeferred<Int>()
        mutex.withLock { pendingOps[userData] = deferred }
        backend.submitWrite(fd, buf, userData).getOrThrow()
        backend.submit().getOrThrow()
        return deferred.await()
    }

    suspend fun processCompletions() {
        mutex.withLock {
            while (true) {
                val completion = backend.pollCompletion().getOrNull() ?: break
                val deferred = pendingOps.remove(completion.userData)
                if (deferred != null) {
                    completion.result.fold(
                        onSuccess = { deferred.complete(it) },
                        onFailure = { deferred.completeExceptionally(it) }
                    )
                }
            }
        }
    }
}

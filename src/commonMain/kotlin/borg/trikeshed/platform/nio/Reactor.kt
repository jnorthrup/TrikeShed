package borg.trikeshed.platform.nio


import kotlin.coroutines.*
import kotlinx.coroutines.*

/**
 * Unified NIO reactor using platform-specific backends
 *
 * This module provides a high-level reactor that abstracts over
 * different platform backends (io_uring, kqueue, epoll) and provides
 * a consistent interface for async I/O operations.
 *
 * Ported from Rust: /Users/jim/work/literbike/src/userspace/nio/reactor.rs
 */

/** Shared state for reactor operations */
class ReactorState(
    val backend: PlatformBackend,
    val registrations: MutableMap<Int, Registration> = mutableMapOf(),
    val pendingOps: MutableMap<Long, PendingOperation> = mutableMapOf(),
    val wakers: MutableMap<Long, CancellableContinuation<Result<Int>>> = mutableMapOf(),
    var nextUserData: Long = 1L,
)

class Registration(
    val token: Token,
    val interest: Interest,
)

class PendingOperation(
    val userData: Long,
    val opType: OpType,
    val fd: Int,
)

/** High-level reactor interface */
class Reactor private constructor(
    private val state: ReactorState,
) {
    companion object {
        
        fun create(): Reactor = withConfig(BackendConfig())

        
        fun withConfig(config: BackendConfig): Reactor {
            val backend = detectBackend(config)
            val state = ReactorState(
                backend = backend,
            )
            return Reactor(state)
        }
    }

    /** Register a file descriptor for monitoring */
    
    fun register(fd: Int, interest: Interest): RegistrationHandle {
        val token = allocateToken()
        state.backend.register(fd, token, interest)
        state.registrations[fd] = Registration(token, interest)
        return RegistrationHandle(fd, state)
    }

    /** Allocate a unique user_data token */
    @Synchronized
    internal fun allocateToken(): Long {
        val token = state.nextUserData
        state.nextUserData = state.nextUserData + 1L
        if (state.nextUserData == 0L) {
            state.nextUserData = 1L // Skip 0
        }
        return token
    }

    /** Submit a read operation — returns suspend function */
    
    fun read(fd: Int, buf: ByteArray): ReadFuture {
        val userData = allocateToken()
        state.backend.submitRead(fd, buf, userData)
        state.pendingOps[userData] = PendingOperation(userData, OpType.Read, fd)
        return ReadFuture(state, userData, buf)
    }

    /** Submit a write operation — returns suspend function */
    
    fun write(fd: Int, buf: ByteArray): WriteFuture {
        val userData = allocateToken()
        state.backend.submitWrite(fd, buf, userData)
        state.pendingOps[userData] = PendingOperation(userData, OpType.Write, fd)
        return WriteFuture(state, userData, buf)
    }

    /** Submit all pending operations */
    
    fun submit(): Long = state.backend.submit()

    /** Wait for completions */
    
    fun wait(min: Int): Long = state.backend.wait(min)

    /** Process completions */
    
    fun processCompletions(completions: Array<Completion?>): Int {
        val count = state.backend.pollCompletions(completions)

        for (i in 0 until count) {
            val completion = completions[i] ?: continue
            state.pendingOps.remove(completion.userData)
            state.wakers.remove(completion.userData)?.resume(completion.result)
        }

        return count
    }

    /** Run the reactor loop once */
    
    fun runOnce(): Int {
        val completions = arrayOfNulls<Completion>(64)
        return processCompletions(completions)
    }

    /** Run the reactor loop continuously */
    suspend fun run() = coroutineScope {
        while (isActive) {
            runOnce()
            delay(1) // Prevent tight loop
        }
    }
}

class RegistrationHandle(
    private val fd: Int,
    private val state: ReactorState,
) {
    fun close() {
        state.backend.unregister(fd)
        state.registrations.remove(fd)
    }
}

/** Suspend function for read operations */
class ReadFuture(
    private val state: ReactorState,
    private val userData: Long,
    private val buf: ByteArray,
) {
    suspend fun await(): Result<Int> = suspendCancellableCoroutine { cont ->
        state.wakers[userData] = cont
        cont.invokeOnCancellation {
            state.wakers.remove(userData)
        }
    }
}

/** Suspend function for write operations */
class WriteFuture(
    private val state: ReactorState,
    private val userData: Long,
    private val buf: ByteArray,
) {
    suspend fun await(): Result<Int> = suspendCancellableCoroutine { cont ->
        state.wakers[userData] = cont
        cont.invokeOnCancellation {
            state.wakers.remove(userData)
        }
    }
}

/** Suspend function that waits for a file descriptor to become readable */
class ReadableFuture(
    private val fd: Int,
    private val state: ReactorState,
) {
    companion object {
        
        fun create(fd: Int, reactor: Reactor): ReadableFuture =
            ReadableFuture(fd, reactor.state)
    }

    suspend fun await(): Result<Unit> = suspendCancellableCoroutine { cont ->
        val userData = state.allocateToken()
        runCatching {
            state.backend.submitPoll(fd, Interest.READABLE, userData)
            state.wakers[userData] = cont
        }.onFailure {
            cont.resume(Result.failure(it))
        }
        cont.invokeOnCancellation {
            state.wakers.remove(userData)
        }
    }
}

/** Suspend function that waits for a file descriptor to become writable */
class WritableFuture(
    private val fd: Int,
    private val state: ReactorState,
) {
    companion object {
        
        fun create(fd: Int, reactor: Reactor): WritableFuture =
            WritableFuture(fd, reactor.state)
    }

    suspend fun await(): Result<Unit> = suspendCancellableCoroutine { cont ->
        val userData = state.allocateToken()
        runCatching {
            state.backend.submitPoll(fd, Interest.WRITABLE, userData)
            state.wakers[userData] = cont
        }.onFailure {
            cont.resume(Result.failure(it))
        }
        cont.invokeOnCancellation {
            state.wakers.remove(userData)
        }
    }
}

// ============================================================================
// Tests (mirroring Rust tests)
// ============================================================================

fun testReactorCreation(): Boolean = runCatching {
    val reactor = Reactor.create()
    true
}.getOrDefault(false)

fun testReactorWithConfig(): Boolean = runCatching {
    val config = BackendConfig(
        entries = 128,
        sqpoll = false,
        iopoll = false,
    )
    val reactor = Reactor.withConfig(config)
    true
}.getOrDefault(false)

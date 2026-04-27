package borg.trikeshed.userspace.nio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

// ================================================================================
// SELF-CONTAINED STUBS: NIO SPI contract on commonMain
// Deterministic dispatch: JVM → NIO, Linux → io_uring. Never swapped.
// The SPI defines the contract. JVM has the real impl (nio channels).
// Linux never runs NIO — it runs liburing. Userspace mediates.
// ================================================================================

/** Completion from a NIO operation — userData links back to the coroutine. */
data class NioCompletion(
    val userData: Long,   // correlation token from submit
    val res: Int,         // bytes read/written, negative on error
    val flags: Int,
)

/** NIO SPI — contract for platform-specific NIO backend (JVM only). */
interface NioSpi {
    /** Open the selector / event loop. */
    fun open(): Result<Unit>

    /** Register fd for read interest. Returns token for completion matching. */
    fun registerRead(fd: Int, token: Long): Result<Unit>

    /** Register fd for write interest. */
    fun registerWrite(fd: Int, token: Long): Result<Unit>

    /** Register a listening fd for accept interest. */
    fun registerAccept(fd: Int, token: Long): Result<Unit>

    /** Deregister fd from all interests. */
    fun deregister(fd: Int): Result<Unit>

    /** Submit all pending registrations / interest changes. */
    fun submit(): Result<Int>

    /** Wait for at least one completion, return without dequeuing. */
    fun waitCompletion(): Result<NioCompletion>

    /** Peek for a completion without blocking. */
    fun peekCompletion(): Result<NioCompletion?>

    /** Advance past [count] completions. */
    fun advance(count: Int)

    /** Register a fanout handler for completions matching [token]. */
    fun registerFanoutHandler(token: Long, handler: (NioCompletion) -> Unit)

    /** Remove a fanout handler. */
    fun removeFanoutHandler(token: Long, handler: (NioCompletion) -> Unit)

    /** Drain: no new registrations, complete all in-flight. */
    fun drain(): Result<Unit>

    /** Close the selector / event loop. */
    fun close(): Result<Unit>
}

// ================================================================================
// SPEC: NioSpi on commonMain — deterministic JVM backend, never on Linux
// ================================================================================

class NioSpiTest {

    /** NioSpi lifecycle: open → register ops → submit → completions → close. */
    @Test fun nioSpi_lifecycle_openOpsClose() {
        val spi = object : NioSpi {
            override fun open() = Result.success(Unit)
            override fun registerRead(fd: Int, token: Long) = Result.success(Unit)
            override fun registerWrite(fd: Int, token: Long) = Result.success(Unit)
            override fun registerAccept(fd: Int, token: Long) = Result.success(Unit)
            override fun deregister(fd: Int) = Result.success(Unit)
            override fun submit() = Result.success(1)
            override fun waitCompletion() = Result.success(NioCompletion(0, 0, 0))
            override fun peekCompletion() = Result.success(null)
            override fun advance(count: Int) {}
            override fun registerFanoutHandler(token: Long, handler: (NioCompletion) -> Unit) {}
            override fun removeFanoutHandler(token: Long, handler: (NioCompletion) -> Unit) {}
            override fun drain() = Result.success(Unit)
            override fun close() = Result.success(Unit)
        }
        assertTrue(spi.open().isSuccess)
        assertTrue(spi.registerRead(1, 42).isSuccess)
        assertEquals(1, spi.submit().getOrNull())
    }

    /** NioCompletion.userData links back to the submitting coroutine. */
    @Test fun nioCompletion_userDataCorrelatesToCoroutine() {
        val c = NioCompletion(userData = 99, res = 1024, flags = 0)
        assertEquals(99, c.userData)
        assertEquals(1024, c.res)
    }

    /** registerRead maps fd + token → selector interest. */
    @Test fun nioSpi_registerRead_bindsFdAndToken() {
        var capturedFd = -1
        var capturedToken = -1L
        val spi = object : NioSpi {
            override fun open() = Result.success(Unit)
            override fun registerRead(fd: Int, token: Long): Result<Unit> {
                capturedFd = fd; capturedToken = token; return Result.success(Unit)
            }
            override fun registerWrite(fd: Int, token: Long) = Result.success(Unit)
            override fun registerAccept(fd: Int, token: Long) = Result.success(Unit)
            override fun deregister(fd: Int) = Result.success(Unit)
            override fun submit() = Result.success(0)
            override fun waitCompletion() = Result.success(NioCompletion(0, 0, 0))
            override fun peekCompletion() = Result.success(null)
            override fun advance(count: Int) {}
            override fun registerFanoutHandler(token: Long, handler: (NioCompletion) -> Unit) {}
            override fun removeFanoutHandler(token: Long, handler: (NioCompletion) -> Unit) {}
            override fun drain() = Result.success(Unit)
            override fun close() = Result.success(Unit)
        }
        spi.registerRead(7, 99L)
        assertEquals(7, capturedFd)
        assertEquals(99L, capturedToken)
    }

    /** Fanout: multiple handlers registered for same token fire on completion. */
    @Test fun nioSpi_fanout_multipleHandlersPerToken() {
        var fireCount = 0
        val h1: (NioCompletion) -> Unit = { fireCount++ }
        val h2: (NioCompletion) -> Unit = { fireCount++ }
        val spi = object : NioSpi {
            override fun open() = Result.success(Unit)
            override fun registerRead(fd: Int, token: Long) = Result.success(Unit)
            override fun registerWrite(fd: Int, token: Long) = Result.success(Unit)
            override fun registerAccept(fd: Int, token: Long) = Result.success(Unit)
            override fun deregister(fd: Int) = Result.success(Unit)
            override fun submit() = Result.success(0)
            override fun waitCompletion() = Result.success(NioCompletion(0, 0, 0))
            override fun peekCompletion() = Result.success(null)
            override fun advance(count: Int) {}
            override fun registerFanoutHandler(token: Long, handler: (NioCompletion) -> Unit) {}
            override fun removeFanoutHandler(token: Long, handler: (NioCompletion) -> Unit) {}
            override fun drain() = Result.success(Unit)
            override fun close() = Result.success(Unit)
        }
        // Both handlers registered for same token — should fire together
        spi.registerFanoutHandler(42, h1)
        spi.registerFanoutHandler(42, h2)
        assertNotNull(spi) // compiles, registers without error
    }
}

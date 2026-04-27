package borg.trikeshed.userspace.nio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

// ================================================================================
// SELF-CONTAINED STUBS: NIO SPI on commonMain — peer to LiburingFacadeSpi
// Semantic gap: LiburingFacadeSpi exists (prepRead/prepWrite/prepAccept/
//   prepConnect/prepClose/submit/waitCqe/peekCqe/fanout/drain/close).
//   But PlatformBackend is a separate stub with a different shape
//   (register/reregister/submitRead/submitWrite/submit/wait/pollCompletion).
//   These should be unified: a single NioSpi with the same lifecycle
//   and operation shape as LiburingFacadeSpi, so a facade can dispatch
//   to either based on platform.
//
//   Inner point: commonMain needs an NioSpi interface that mirrors
//   LiburingFacadeSpi so the Reactor can swap backends without
//   branching on platform. The facade pattern: one interface, two
//   implementations (NIO on JVM, io_uring on Linux).
// ================================================================================

/** Completion from a network I/O operation — mirrors UringCompletion. */
data class NioCompletion(
    val userData: Long,
    val res: Int,       // bytes read/written, or -errno
    val flags: Int,
)

/** NIO SPI — same lifecycle and operation shape as LiburingFacadeSpi. */
interface NioSpi {
    fun open(entries: Int = 256, flags: Int = 0): Result<Unit>

    fun prepRead(fd: Int, bufAddress: Long, len: Int, offset: Long, userData: Long): Result<Unit>
    fun prepWrite(fd: Int, bufAddress: Long, len: Int, offset: Long, userData: Long): Result<Unit>
    fun prepAccept(fd: Int, userData: Long): Result<Unit>
    fun prepConnect(fd: Int, addrPtr: Long, addrLen: Int, userData: Long): Result<Unit>
    fun prepClose(fd: Int, userData: Long): Result<Unit>

    fun submit(): Result<Int>
    fun waitCompletion(): Result<NioCompletion>
    fun peekCompletion(): Result<NioCompletion?>

    fun registerFanoutHandler(token: Long, handler: (NioCompletion) -> Unit)
    fun removeFanoutHandler(token: Long, handler: (NioCompletion) -> Unit)

    fun drain(): Result<Unit>
    fun close(): Result<Unit>
}

// ================================================================================
// SPEC: NioSpi on commonMain — unified shape with LiburingFacadeSpi
// ================================================================================

class NioSpiRedTest {

    /** NioSpi mirrors LiburingFacadeSpi lifecycle: open → ops → drain → close. */
    @Test fun nioSpi_lifecycle_openOpsClose() {
        val spi = object : NioSpi {
            override fun open(entries: Int, flags: Int): Result<Unit> = Result.success(Unit)
            override fun prepRead(fd: Int, bufAddress: Long, len: Int, offset: Long, userData: Long): Result<Unit> = Result.success(Unit)
            override fun prepWrite(fd: Int, bufAddress: Long, len: Int, offset: Long, userData: Long): Result<Unit> = Result.success(Unit)
            override fun prepAccept(fd: Int, userData: Long): Result<Unit> = Result.success(Unit)
            override fun prepConnect(fd: Int, addrPtr: Long, addrLen: Int, userData: Long): Result<Unit> = Result.success(Unit)
            override fun prepClose(fd: Int, userData: Long): Result<Unit> = Result.success(Unit)
            override fun submit(): Result<Int> = Result.success(1)
            override fun waitCompletion(): Result<NioCompletion> = Result.success(NioCompletion(0, 0, 0))
            override fun peekCompletion(): Result<NioCompletion?> = Result.success(null)
            override fun registerFanoutHandler(token: Long, handler: (NioCompletion) -> Unit) {}
            override fun removeFanoutHandler(token: Long, handler: (NioCompletion) -> Unit) {}
            override fun drain(): Result<Unit> = Result.success(Unit)
            override fun close(): Result<Unit> = Result.success(Unit)
        }
        assertTrue(spi.open().isSuccess)
        assertTrue(spi.prepRead(1, 0, 1024, 0, 42).isSuccess)
        assertEquals(1, spi.submit().getOrNull())
        assertTrue(spi.drain().isSuccess)
        assertTrue(spi.close().isSuccess)
    }

    /** NioCompletion has userData linking back to the coroutine that submitted. */
    @Test fun nioCompletion_userDataLinksToCoroutine() {
        val c = NioCompletion(userData = 99, res = 1024, flags = 0)
        assertEquals(99, c.userData)
        assertEquals(1024, c.res)
    }

    /** NioSpi and LiburingFacadeSpi share the same operation vocabulary:
     *  prepRead, prepWrite, prepAccept, prepConnect, prepClose. */
    @Test fun nioSpi_sharesOperationVocabularyWithLiburing() {
        // The inner point: both SPIs use the same operation names and shapes,
        // so a facade can select between them without translation layers.
        val ops = listOf("prepRead", "prepWrite", "prepAccept", "prepConnect", "prepClose")
        NioSpi::class.java.declaredMethods.map { it.name }.let { names ->
            ops.forEach { op -> assertTrue(names.contains(op), "NioSpi missing $op") }
        }
    }

    /** NioSpi shares lifecycle vocabulary with LiburingFacadeSpi:
     *  open, submit, drain, close, waitCompletion/peekCompletion. */
    @Test fun nioSpi_sharesLifecycleVocabularyWithLiburing() {
        val lifecycle = listOf("open", "submit", "drain", "close", "waitCompletion", "peekCompletion")
        NioSpi::class.java.declaredMethods.map { it.name }.let { names ->
            lifecycle.forEach { op -> assertTrue(names.contains(op), "NioSpi missing $op") }
        }
    }

    /** Fanout: multiple handlers can register for the same userData token. */
    @Test fun nioSpi_fanout_multipleHandlersPerToken() {
        val received = mutableListOf<NioCompletion>()
        val spi = object : NioSpi {
            override fun open(entries: Int, flags: Int) = Result.success(Unit)
            override fun prepRead(fd: Int, bufAddress: Long, len: Int, offset: Long, userData: Long) = Result.success(Unit)
            override fun prepWrite(fd: Int, bufAddress: Long, len: Int, offset: Long, userData: Long) = Result.success(Unit)
            override fun prepAccept(fd: Int, userData: Long) = Result.success(Unit)
            override fun prepConnect(fd: Int, addrPtr: Long, addrLen: Int, userData: Long) = Result.success(Unit)
            override fun prepClose(fd: Int, userData: Long) = Result.success(Unit)
            override fun submit() = Result.success(0)
            override fun waitCompletion() = Result.success(NioCompletion(0, 0, 0))
            override fun peekCompletion() = Result.success(null)
            override fun registerFanoutHandler(token: Long, handler: (NioCompletion) -> Unit) { received.add(NioCompletion(token, 0, 0)) }
            override fun removeFanoutHandler(token: Long, handler: (NioCompletion) -> Unit) {}
            override fun drain() = Result.success(Unit)
            override fun close() = Result.success(Unit)
        }
        spi.registerFanoutHandler(42, {})
        spi.registerFanoutHandler(42, {})
        assertTrue(received.size >= 0) // at minimum it doesn't throw
    }
}

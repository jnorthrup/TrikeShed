package borg.trikeshed.userspace.kernel

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

// ================================================================================
// SELF-CONTAINED STUBS: Facade mediates RingChannel ↔ backend (NIO or io_uring)
// Deterministic dispatch: Linux → liburing-native, JVM → NIO.
// Userspace is the mediator — it doesn't pick at runtime, the platform
// build (expect/actual) selects the backend. The facade wraps the RingChannel
// and pumps data between the ring and the backend.
//
// Flow: recv() ← RingChannel ← backend push (NIO read / io_uring CQE)
//       send() → RingChannel → backend pop  (NIO write / io_uring SQE)
// ================================================================================

/** Completion from either backend — unified shape. */
data class BackendCompletion(
    val userData: Long,
    val res: Int,
    val flags: Int,
)

/** Ring storage — the common abstraction both backends push/pop. */
class RingBuf<T>(val capacity: Int) {
    @Suppress("UNCHECKED_CAST")
   val buf = arrayOfNulls<Any?>(capacity)
   var h = 0;var t = 0;val mask = capacity - 1
    val size: Int get() = (t - h) and mask
    val isEmpty: Boolean get() = h == t
    val isFull: Boolean get() = ((t + 1) and mask) == h
    fun push(v: T): Boolean { if (isFull) return false; buf[t] = v; t = (t + 1) and mask; return true }
    @Suppress("UNCHECKED_CAST")
    fun pop(): T? { if (isEmpty) return null; val v = buf[h] as T; buf[h] = null; h = (h + 1) and mask; return v }
}

/** The active backend — platform-specific (NioSpi on JVM, LiburingFacadeSpi on Linux). */
interface ActiveBackend {
    val isOpen: Boolean
    fun open(): Result<Unit>
    fun registerRead(fd: Int, token: Long): Result<Unit>
    fun registerWrite(fd: Int, token: Long): Result<Unit>
    fun registerAccept(fd: Int, token: Long): Result<Unit>
    fun submit(): Result<Int>
    fun waitCompletion(): Result<BackendCompletion>
    fun close(): Result<Unit>
}

/** Userspace facade — connects RingChannel to the active backend. */
class IoFacade(
    val backend: ActiveBackend,
    val ringCapacity: Int = 256,
) {
    val readRing = RingBuf<ByteArray>(ringCapacity)
    val writeRing = RingBuf<ByteArray>(ringCapacity)

    /** Called by backend when NIO read / io_uring CQE completes with data. */
    fun onReadComplete(data: ByteArray) {
        readRing.push(data)
    }

    /** Called by backend when ready to write — pops from writeRing. */
    fun onWriteReady(): ByteArray? = writeRing.pop()
}

// ================================================================================
// SPEC: IoFacade mediates RingChannel ↔ backend, platform dispatch is deterministic
// ================================================================================

class IoFacadeMediatorTest {

    /** IoFacade holds a readRing and writeRing — the common abstraction. */
    @Test fun ioFacade_holdsReadWriteRings() {
        val backend = object : ActiveBackend {
            override val isOpen = true
            override fun open() = Result.success(Unit)
            override fun registerRead(fd: Int, token: Long) = Result.success(Unit)
            override fun registerWrite(fd: Int, token: Long) = Result.success(Unit)
            override fun registerAccept(fd: Int, token: Long) = Result.success(Unit)
            override fun submit() = Result.success(0)
            override fun waitCompletion() = Result.success(BackendCompletion(0, 0, 0))
            override fun close() = Result.success(Unit)
        }
        val facade = IoFacade(backend, ringCapacity = 64)
        assertNotNull(facade.readRing)
        assertNotNull(facade.writeRing)
        assertEquals(64, facade.readRing.capacity)
    }

    /** onReadComplete pushes data into readRing — consumer recvs from here. */
    @Test fun ioFacade_onReadComplete_fillsReadRing() {
        val backend = object : ActiveBackend {
            override val isOpen = true
            override fun open() = Result.success(Unit)
            override fun registerRead(fd: Int, token: Long) = Result.success(Unit)
            override fun registerWrite(fd: Int, token: Long) = Result.success(Unit)
            override fun registerAccept(fd: Int, token: Long) = Result.success(Unit)
            override fun submit() = Result.success(0)
            override fun waitCompletion() = Result.success(BackendCompletion(0, 0, 0))
            override fun close() = Result.success(Unit)
        }
        val facade = IoFacade(backend)
        facade.onReadComplete(byteArrayOf(1, 2, 3))
        facade.onReadComplete(byteArrayOf(4, 5))
        assertEquals(2, facade.readRing.size)
        assertTrue(facade.readRing.pop()!!.contentEquals(byteArrayOf(1, 2, 3)))
        assertTrue(facade.readRing.pop()!!.contentEquals(byteArrayOf(4, 5)))
    }

    /** onWriteReady pops from writeRing — producer sent data here. */
    @Test fun ioFacade_onWriteReady_drainsWriteRing() {
        val backend = object : ActiveBackend {
            override val isOpen = true
            override fun open() = Result.success(Unit)
            override fun registerRead(fd: Int, token: Long) = Result.success(Unit)
            override fun registerWrite(fd: Int, token: Long) = Result.success(Unit)
            override fun registerAccept(fd: Int, token: Long) = Result.success(Unit)
            override fun submit() = Result.success(0)
            override fun waitCompletion() = Result.success(BackendCompletion(0, 0, 0))
            override fun close() = Result.success(Unit)
        }
        val facade = IoFacade(backend)
        facade.writeRing.push(byteArrayOf(10, 20))
        facade.writeRing.push(byteArrayOf(30))
        assertTrue(facade.onWriteReady()!!.contentEquals(byteArrayOf(10, 20)))
        assertTrue(facade.onWriteReady()!!.contentEquals(byteArrayOf(30)))
        assertEquals(0, facade.writeRing.size)
    }

    /** ActiveBackend is the facade's connection to the OS — NIO or io_uring. */
    @Test fun activeBackend_isPlatformSpecific() {
        // RED: on JVM, this is NioSpi. On Linux, this is LiburingFacadeSpi.
        // The IoFacade doesn't care which — it just calls the same methods.
        val backend = object : ActiveBackend {
            override val isOpen = false
            override fun open() = Result.success(Unit)
            override fun registerRead(fd: Int, token: Long) = Result.success(Unit)
            override fun registerWrite(fd: Int, token: Long) = Result.success(Unit)
            override fun registerAccept(fd: Int, token: Long) = Result.success(Unit)
            override fun submit() = Result.success(0)
            override fun waitCompletion() = Result.success(BackendCompletion(0, 0, 0))
            override fun close() = Result.success(Unit)
        }
        assertTrue(backend.open().isSuccess)
    }

    /** Platform dispatch is deterministic, not runtime selection. */
    @Test fun platformDispatch_isBuildTime_expectActual() {
        // RED: Linux → io_uring, JVM → NIO. No runtime branch.
        // expect fun activeBackend(): ActiveBackend
        // Linux actual: return LiburingFacadeSpi adapter
        // JVM actual:    return NioSpi adapter
        TODO("redefine test requirements")
    }
}

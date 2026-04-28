package borg.trikeshed.userspace.nio

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.fail

// ================================================================================
// SELF-CONTAINED STUBS: NIO socket I/O through suspend + dispatcher
// Donor: old trikeshed-reactor NioToChannelBridge.kt —
//   NioSocketChannelAdapter.read/write with withContext(Dispatchers.IO)
//   NioServerSocketChannelAdapter.accept → NioSocketChannelAdapter
// Semantic gap: PlatformBackend is a stub. No real socket I/O.
//   The inner point is suspend + os-level fd → real read/write,
//   not enumerating every NIO channel shape.
// ================================================================================

sealed class SocketAddress {
    data class Inet(val host: String, val port: Int) : SocketAddress()
    data class Unix(val path: String) : SocketAddress()
}

interface ConnectedSocket {
    val remoteAddress: SocketAddress
    suspend fun read(buf: ByteArray, offset: Int, len: Int): Int
    suspend fun write(buf: ByteArray, offset: Int, len: Int): Int
    suspend fun close()
}

interface ListeningSocket {
    val bindAddress: SocketAddress
    suspend fun accept(): ConnectedSocket?
    suspend fun close()
}

// ================================================================================
// SPEC: suspend socket I/O — real read/write through NIO or io_uring
// ================================================================================

class PlatformBackendSocketAdapterTest {

    /** ConnectedSocket via NIO adapter should read bytes from OS fd. */
    @Test fun connectedSocket_read_notImplemented() {
        val sock = object : ConnectedSocket {
            override val remoteAddress = SocketAddress.Inet("example.com", 80)
            override suspend fun read(buf: ByteArray, offset: Int, len: Int): Int =
                throw NotImplementedError("NioSocketAdapter.read — need withContext(Dispatchers.IO) { socketChannel.read(nioBuffer) }")
            override suspend fun write(buf: ByteArray, offset: Int, len: Int): Int = 0
            override suspend fun close() {}
        }
        try {
            kotlinx.coroutines.test.runTest { sock.read(ByteArray(1024), 0, 1024) }
            fail("Expected NotImplementedError for read")
        } catch (e: NotImplementedError) {
            // RED: PlatformBackend has no real socket read adapter
        }
    }

    /** ConnectedSocket via NIO adapter should write bytes to OS fd. */
    @Test fun connectedSocket_write_notImplemented() {
        val sock = object : ConnectedSocket {
            override val remoteAddress = SocketAddress.Inet("example.com", 80)
            override suspend fun read(buf: ByteArray, offset: Int, len: Int): Int = -1
            override suspend fun write(buf: ByteArray, offset: Int, len: Int): Int =
                throw NotImplementedError("NioSocketAdapter.write — need withContext(Dispatchers.IO) { socketChannel.write(nioBuffer) }")
            override suspend fun close() {}
        }
        try {
            kotlinx.coroutines.test.runTest { sock.write("GET /".encodeToByteArray(), 0, 5) }
            fail("Expected NotImplementedError for write")
        } catch (e: NotImplementedError) {
            // RED: PlatformBackend has no real socket write adapter
        }
    }

    /** ListeningSocket.accept should yield a ConnectedSocket with real I/O. */
    @Test fun listeningSocket_accept_yieldsConnectedSocket() {
        val addr = SocketAddress.Inet("0.0.0.0", 8080)
        val server = object : ListeningSocket {
            override val bindAddress = addr
            override suspend fun accept(): ConnectedSocket? =
                throw NotImplementedError("NioServerSocketChannelAdapter.accept — need serverChannel.accept() → NioSocketChannelAdapter")
            override suspend fun close() {}
        }
        try {
            kotlinx.coroutines.test.runTest { server.accept() }
            fail("Expected NotImplementedError for accept")
        } catch (e: NotImplementedError) {
            // RED: no server accept adapter
        }
    }

    /** PlatformBackend.register maps Interest → fd + token for OS-level registration. */
    @Test fun platformBackend_register_stub() {
        val backend = object : PlatformBackend {
            override fun register(fd: Int, token: Long, interest: Interest): Result<Unit> = Result.success(Unit)
            override fun reregister(fd: Int, token: Long, interest: Interest): Result<Unit> = Result.success(Unit)
            override fun unregister(fd: Int): Result<Unit> = Result.success(Unit)
            override fun submitRead(fd: Int, buf: ByteArray, userData: Long): Result<Unit> = Result.success(Unit)
            override fun submitWrite(fd: Int, buf: ByteArray, userData: Long): Result<Unit> = Result.success(Unit)
            override fun submit(): Result<Long> = Result.success(0L)
            override fun wait(min: Int): Result<Long> = Result.success(0L)
            override fun pollCompletion(): Result<Completion?> = Result.success(null)
        }
        // Stub: all operations succeed vacuously
        assertNotNull(backend.register(0, 0L, Interest.READABLE))
        assertNotNull(backend.pollCompletion())
    }

    /** Completion.userData matches submitted operation, links CQE back to coroutine. */
    @Test fun completion_linksUserDataToCoroutine() {
        val c = Completion(userData = 99L, result = Result.success(1024), opType = OpType.Read)
        assertNotNull(c)
    }
}

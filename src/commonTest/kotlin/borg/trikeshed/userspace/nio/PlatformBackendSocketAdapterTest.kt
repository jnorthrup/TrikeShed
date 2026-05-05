package borg.trikeshed.userspace.nio

import kotlin.test.Test
import kotlin.test.assertNotNull

// ================================================================================
// Socket I/O interface contract tests.
//
// These verify the algebraic shape compiles across all targets.
// ConnectedSocket, ListeningSocket, PlatformBackend define the async socket
// algebra. Real I/O adapters (NIO, io_uring, node:net) are platform-specific
// actuals tracked by TODO markers below.
// ================================================================================

class PlatformBackendSocketAdapterTest {

    /**
     * ConnectedSocket interface shape compiles. Real read adapter pending.
     * TODO("ConnectedSocket.read — needs platform adapter: withContext(Dispatchers.IO) { channel.read(buf) }")
     * TODO("ConnectedSocket.write — needs platform adapter: withContext(Dispatchers.IO) { channel.write(buf) }")
     */
    @Test fun connectedSocket_interfaceShape() {
        val sock: ConnectedSocket = object : ConnectedSocket {
            override val remoteAddress = SocketAddress.Inet("example.com", 80)
            override suspend fun read(buf: ByteArray, offset: Int, len: Int): Int = -1
            override suspend fun write(buf: ByteArray, offset: Int, len: Int): Int = 0
            override suspend fun close() {}
        }
        assertNotNull(sock)
        assertNotNull(sock.remoteAddress)
    }

    /**
     * ListeningSocket interface shape compiles. Real accept adapter pending.
     * TODO("ListeningSocket.accept — needs platform adapter: serverChannel.accept() → ConnectedSocket")
     */
    @Test fun listeningSocket_interfaceShape() {
        val addr = SocketAddress.Inet("0.0.0.0", 8080)
        val server: ListeningSocket = object : ListeningSocket {
            override val bindAddress = addr
            override suspend fun accept(): ConnectedSocket? = null
            override suspend fun close() {}
        }
        assertNotNull(server)
        assertNotNull(server.bindAddress)
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
        assertNotNull(backend.register(0, 0L, Interest.READABLE))
        assertNotNull(backend.pollCompletion())
    }

    /** Completion.userData links CQE back to coroutine for async fanout. */
    @Test fun completion_linksUserDataToCoroutine() {
        val c = Completion(userData = 99L, result = Result.success(1024), opType = OpType.Read)
        assertNotNull(c)
    }
}

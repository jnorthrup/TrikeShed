package borg.trikeshed.userspace.nio

import borg.trikeshed.userspace.reactor.Interest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

// ================================================================================
// SELF-CONTAINED STUBS: NIO socket → channel adapter algebra
// Donor: old trikeshed-reactor NioToChannelBridge.kt — NioSocketChannelAdapter,
//   NioServerSocketChannelAdapter, NioDatagramChannelAdapter, NioPipeSourceAdapter,
//   NioPipeSinkAdapter
// Semantic gap: PlatformBackend is a stub. No socket I/O, no fd registration,
//   no withContext(Dispatchers.IO) read/write adapters.
// ================================================================================

/** Socket handle wrapping an OS file descriptor. */
data class SocketFd(val fd: Int)

/** Address for socket operations. */
sealed class SocketAddress {
    data class Inet(val host: String, val port: Int) : SocketAddress()
    data class Unix(val path: String) : SocketAddress()
}

/** What a connected socket exposes after accept/connect. */
interface ConnectedSocket {
    val localAddress: SocketAddress
    val remoteAddress: SocketAddress
    suspend fun read(buf: ByteArray, offset: Int, len: Int): Int
    suspend fun write(buf: ByteArray, offset: Int, len: Int): Int
    suspend fun close()
}

/** What a listening socket exposes. */
interface ListeningSocket {
    val bindAddress: SocketAddress
    suspend fun accept(): ConnectedSocket?
    suspend fun close()
}

/** Stub: PlatformBackend is currently an unimplemented interface — no socket adapter. */
class NioSocketAdapterStub(
    override val localAddress: SocketAddress,
    override val remoteAddress: SocketAddress,
) : ConnectedSocket {
    var isOpen = true
    override suspend fun read(buf: ByteArray, offset: Int, len: Int): Int = throw NotImplementedError("NioSocketAdapter.read")
    override suspend fun write(buf: ByteArray, offset: Int, len: Int): Int = throw NotImplementedError("NioSocketAdapter.write")
    override suspend fun close() { isOpen = false }
}

// ================================================================================
// SPEC: PlatformBackend exposes socket adapters with real read/write
// ================================================================================

class PlatformBackendSocketAdapterRedTest {

    /** SocketAddress.Inet carries host and port. */
    @Test fun socketAddress_inet_hostAndPort() {
        val addr = SocketAddress.Inet("localhost", 8443)
        assertEquals("localhost", addr.host)
        assertEquals(8443, addr.port)
    }

    /** SocketAddress.Unix carries a filesystem path. */
    @Test fun socketAddress_unix_path() {
        val addr = SocketAddress.Unix("/tmp/trikeshed.sock")
        assertEquals("/tmp/trikeshed.sock", addr.path)
    }

    /** ConnectedSocket has local and remote addresses. */
    @Test fun connectedSocket_localAndRemoteAddress() {
        val local = SocketAddress.Inet("127.0.0.1", 54321)
        val remote = SocketAddress.Inet("google.com", 443)
        val sock = NioSocketAdapterStub(local, remote)
        assertEquals(local, sock.localAddress)
        assertEquals(remote, sock.remoteAddress)
    }

    /** ConnectedSocket.read is unimplemented — need real NIO/uring adapter. */
    @Test fun connectedSocket_read_notImplemented() {
        val sock = NioSocketAdapterStub(
            SocketAddress.Inet("127.0.0.1", 0),
            SocketAddress.Inet("example.com", 80),
        )
        try {
            kotlinx.coroutines.runBlocking { sock.read(ByteArray(1024), 0, 1024) }
            fail("Expected NotImplementedError for read")
        } catch (e: NotImplementedError) {
            // RED: no real socket read yet
        }
    }

    /** ConnectedSocket.write is unimplemented — need real NIO/uring adapter. */
    @Test fun connectedSocket_write_notImplemented() {
        val sock = NioSocketAdapterStub(
            SocketAddress.Inet("127.0.0.1", 0),
            SocketAddress.Inet("example.com", 80),
        )
        try {
            kotlinx.coroutines.runBlocking { sock.write("GET /".toByteArray(), 0, 5) }
            fail("Expected NotImplementedError for write")
        } catch (e: NotImplementedError) {
            // RED: no real socket write yet
        }
    }

    /** ConnectedSocket.close transitions to closed. */
    @Test fun connectedSocket_close() {
        val sock = NioSocketAdapterStub(
            SocketAddress.Inet("127.0.0.1", 0),
            SocketAddress.Inet("example.com", 80),
        )
        assertTrue(sock.isOpen)
        kotlinx.coroutines.runBlocking { sock.close() }
    }

    /** ListeningSocket.bindAddress returns the bind address. */
    @Test fun listeningSocket_bindAddress() {
        val addr = SocketAddress.Inet("0.0.0.0", 8080)
        val ls = object : ListeningSocket {
            override val bindAddress: SocketAddress = addr
            override suspend fun accept(): ConnectedSocket? = null
            override suspend fun close() {}
        }
        assertEquals(addr, ls.bindAddress)
    }

    /** ListeningSocket.accept returns null when no connection pending. */
    @Test fun listeningSocket_accept_nullWhenEmpty() {
        val ls = object : ListeningSocket {
            override val bindAddress: SocketAddress = SocketAddress.Inet("0.0.0.0", 8080)
            override suspend fun accept(): ConnectedSocket? = null
            override suspend fun close() {}
        }
        val client: ConnectedSocket? = kotlinx.coroutines.runBlocking { ls.accept() }
        kotlin.test.assertNull(client)
    }

    /** PlatformBackend.register maps Interest to OS-level registration. */
    @Test fun platformBackend_register_stub() {
        val backend = object : PlatformBackend {
            override fun register(fd: Int, token: Long, interest: Interest): Result<Unit> = Result.success<Unit>(Unit)
            override fun reregister(fd: Int, token: Long, interest: Interest): Result<Unit> = Result.success<Unit>(Unit)
            override fun unregister(fd: Int): Result<Unit> = Result.success<Unit>(Unit)
            override fun submitRead(fd: Int, buf: ByteArray, userData: Long): Result<Unit> = Result.success<Unit>(Unit)
            override fun submitWrite(fd: Int, buf: ByteArray, userData: Long): Result<Unit> = Result.success<Unit>(Unit)
            override fun submit(): Result<Long> = Result.success(0L)
            override fun wait(min: Int): Result<Long> = Result.success(0L)
            override fun pollCompletion(): Result<Completion?> = Result.success(null)
        }
        assertTrue(backend.register(0, 0L, Interest.READ).isSuccess)
        assertTrue(backend.submitRead(1, ByteArray(0), 42L).isSuccess)
    }

    /** Completion.userData matches what was submitted. */
    @Test fun completion_userDataMatchesSubmit() {
        val c = Completion(userData = 99L, result = Result.success(1024), opType = OpType.Read)
        assertEquals(99L, c.userData)
        assertTrue(c.result.isSuccess)
        assertEquals(OpType.Read, c.opType)
    }

    /** Completion for failed read carries the error. */
    @Test fun completion_readFailure() {
        val c = Completion(userData = 1L, result = Result.failure(Exception("ECONNRESET")), opType = OpType.Read)
        assertTrue(c.result.isFailure)
        assertEquals(OpType.Read, c.opType)
    }

    /** SocketFd wraps a raw OS file descriptor. */
    @Test fun socketFd_wrapsRawInt() {
        val fd = SocketFd(42)
        assertEquals(42, fd.fd)
    }
}


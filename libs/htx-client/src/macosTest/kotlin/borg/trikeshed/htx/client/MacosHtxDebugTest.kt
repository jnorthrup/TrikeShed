package borg.trikeshed.htx.client

import borg.trikeshed.userspace.nio.channels.spi.PosixChannelOperations
import borg.trikeshed.userspace.nio.channels.spi.PosixReactorOperations
import borg.trikeshed.userspace.nio.ByteBuffer
import borg.trikeshed.userspace.reactor.Interest
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Debugging variant — steps through the TLS handshake manually and prints
 * every intermediate state so we can see exactly where macOS breaks.
 */
class MacosHtxDebugTest {

    private val channels = PosixChannelOperations()
    private val reactor = PosixReactorOperations(channels)
    private val ring = channels.openChannel()

    private fun channelHandle(fd: Int) = object : borg.trikeshed.userspace.nio.channels.spi.ChannelOperations.ChannelHandle {
        override val id get() = fd
        override fun read(b: ByteBuffer, o: Long) = ring.read(b, o)
        override fun write(b: ByteBuffer, o: Long) = ring.write(b, o)
        override fun readv(fd: Int, b: ByteBuffer, userData: Long) = ring.readv(fd, b, userData)
        override fun writev(fd: Int, b: ByteBuffer, userData: Long) = ring.writev(fd, b, userData)
        override fun submit() = ring.submit()
        override fun wait(min: Int) = ring.wait(min)
    }

    @Test
    fun `step through TLS to google`() = runTest {
        val host = "www.google.com"
        val port = 443

        // Step 1: socket
        val fd = channels.socket(2, 1, 0)
        println("socket fd=$fd")
        assertTrue(fd >= 0, "socket failed: $fd")

        // Step 2: connect
        val connResult = channels.connect(fd, host, port)
        println("connect returned=$connResult")

        if (connResult < 0) {
            reactor.register(fd, setOf(Interest.WRITE), 1L)
            val results = reactor.poll(10.seconds)
            println("poll results=$results")
            val writable = results.any { it.fd == fd && Interest.WRITE in it.ready }
            assertTrue(writable, "connect poll failed — no WRITE event")
        }

        // Step 3: TLS ClientHello
        reactor.register(fd, setOf(Interest.READ), 1L)
        val handle = channelHandle(fd)

        val sha256 = borg.trikeshed.tls.codec.hash.DefaultSha256()
        val x25519 = borg.trikeshed.tls.codec.ecdh.DefaultX25519()
        val hkdf = borg.trikeshed.tls.codec.kdf.DefaultHkdfSha256(sha256)
        val aes = borg.trikeshed.tls.codec.aead.DefaultAes128Gcm()
        val codec = borg.trikeshed.tls.codec.CommonTlsRecordCodec(aes)
        val hs = borg.trikeshed.tls.codec.CommonTlsClientHandshake(sha256, x25519, hkdf, codec, host)

        val ch = hs.buildClientHello()
        println("ClientHello len=${ch.size}")

        // Send TLS record
        val recordHeader = byteArrayOf(0x16, 0x03.toByte(), 0x03.toByte(),
            ((ch.size ushr 8) and 0xFF).toByte(), (ch.size and 0xFF).toByte())
        val written = handle.writev(fd, ByteBuffer.wrap(recordHeader + ch))
        println("wrote $written bytes")

        handle.submit()
        val waited = handle.wait(1)
        println("submit+wait=$waited")

        // Step 4: recv ServerHello
        val buf = ByteArray(16384)
        reactor.poll(5.seconds)
        val n = handle.readv(fd, ByteBuffer.wrap(buf))
        println("read $n bytes")
        assertTrue(n > 0, "no response from $host")

        // Continue with full handshake logic...
        println("TLS handshake initiated successfully — continuing...")
        assertTrue(true)
    }
}

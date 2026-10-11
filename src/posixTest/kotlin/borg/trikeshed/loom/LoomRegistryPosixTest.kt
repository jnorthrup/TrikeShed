package borg.trikeshed.loom

import borg.trikeshed.htx.*
import borg.trikeshed.litebike.*
import borg.trikeshed.userspace.nio.ByteBuffer
import borg.trikeshed.userspace.nio.channels.*
import kotlinx.coroutines.*
import kotlin.test.*

/** [RegistryParity] on POSIX targets: PosixLitebikeBindAdapter, the userspace facade's sockets for raw requests, the platform's HTX reactor. */
class LoomRegistryPosixTest : RegistryParity() {
    override suspend fun bind(listener: LitebikeListenerElement, host: String, port: Int) = PosixLitebikeBindAdapter.bindAndServe(listener, port, host)

    override fun port(host: String): Int {
        val ipv6 = ':' in host
        val server = ServerSocketChannel.open(if (ipv6) "INET6" else "INET")
        try {
            server.bind(if (ipv6) "[$host]:0" else "$host:0", 1)
            return server.getLocalAddress().substringAfterLast(':').toInt()
        } finally {
            server.close()
        }
    }

    override fun exchange(port: Int, request: ByteArray): ByteArray {
        val channel = SocketChannel.openWithRemote("127.0.0.1:$port")
        try {
            val source = ByteBuffer(request)
            while (source.hasRemaining()) channel.write(source)
            var reply = ByteArray(0)
            val chunk = ByteBuffer(65536)
            while (true) {
                chunk.clear()
                val read = channel.read(chunk)
                if (read < 0) return reply
                reply += chunk.array().copyOf(read)
            }
        } finally {
            channel.close()
        }
    }

    override suspend fun reactor(): HtxReactorElement = openHtxReactorElement()

    @Test fun load() = runBlocking { load_cases() }

    @Test fun load6() = runBlocking { load6_cases() }

    @Test fun serve() = runBlocking { serve_cases() }
}

package borg.trikeshed.loom

import borg.trikeshed.htx.*
import borg.trikeshed.litebike.*
import borg.trikeshed.userspace.nio.channels.spi.JvmChannelOperations
import kotlinx.coroutines.*
import java.net.ServerSocket
import java.net.Socket
import kotlin.test.*

/** [RegistryParity] on the JVM: JvmLitebikeBindAdapter, the JDK's sockets for raw requests, JvmChannelOperations under HTX. */
class LoomRegistryJvmTest : RegistryParity() {
    override suspend fun bind(listener: LitebikeListenerElement, port: Int) {
        val connections = ConnectionRegistry()
        try {
            JvmLitebikeBindAdapter.bindAndServe(listener, port, "127.0.0.1", connections)
        } finally {
            connections.closeAll()
        }
    }

    override fun port(): Int = ServerSocket(0).use { it.localPort }

    override fun exchange(port: Int, request: ByteArray): ByteArray = Socket("127.0.0.1", port).use { socket ->
        socket.soTimeout = 10_000
        socket.getOutputStream().write(request)
        socket.getInputStream().readBytes()
    }

    override suspend fun reactor(): HtxReactorElement = HtxReactorElement(JvmChannelOperations()).also { it.open() }

    @Test fun load() = runBlocking { load_cases() }

    @Test fun serve() = runBlocking { serve_cases() }
}

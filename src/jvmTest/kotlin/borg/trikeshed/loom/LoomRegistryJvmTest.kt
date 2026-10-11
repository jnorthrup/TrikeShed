package borg.trikeshed.loom

import borg.trikeshed.htx.*
import borg.trikeshed.litebike.*
import borg.trikeshed.userspace.nio.channels.spi.JvmChannelOperations
import kotlinx.coroutines.*
import java.net.ServerSocket
import kotlin.test.*

/** [RegistryParity] on the JVM: JvmLitebikeBindAdapter, JvmChannelOperations under HTX. */
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

    override suspend fun reactor(): HtxReactorElement = HtxReactorElement(JvmChannelOperations()).also { it.open() }

    @Test fun load() = runBlocking { load_cases() }
}

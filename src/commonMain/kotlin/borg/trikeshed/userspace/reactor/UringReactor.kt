package borg.trikeshed.userspace.reactor

import borg.trikeshed.context.AsyncContextElement
import borg.trikeshed.context.AsyncContextKey
import borg.trikeshed.context.ElementState
import borg.trikeshed.userspace.nio.channels.spi.ChannelOperations
import borg.trikeshed.userspace.nio.channels.spi.ReactorOperations
import borg.trikeshed.userspace.nio.tls.TlsSettings
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * SupervisorJob reactor — wires nio-uring-ebpf to coroutine lifecycle.
 *
 * Receives child Elements via constructor parameters so commonMain stays
 * platform-agnostic. The JVM factory creates concrete instances and wires them.
 *
 *   SupervisorJob
 *     └─ UringReactor (this)
 *          ├─ ChannelOperations (injected)
 *          ├─ ReactorOperations  (injected)
 *          └─ TlsSettings (created at open time)
 */
class UringReactor(
    private val channelOps: ChannelOperations,
    private val reactorOps: ReactorOperations? = null,
    parentJob: Job? = null,
) : AsyncContextElement(parentJob = parentJob) {

    companion object Key : AsyncContextKey<UringReactor>()

    override val key: AsyncContextKey<UringReactor> get() = Key

    // eBPF program fd (attached to listen socket for protocol detection)
    private var bpfProgramFd: Int = -1

    // Ring entry count
    private val ringEntries = 256

    // Throttle concurrent ring submissions
    private val submitSemaphore = Semaphore(32)

    // Open event handlers
    private val acceptHandlers = mutableListOf<(Int) -> Unit>()
    private val readHandlers = linkedMapOf<Int, (ByteArray) -> Unit>()
    private val errorHandlers = mutableMapOf<Int, (Throwable) -> Unit>()

    // TLS settings — created at open() time, not at construction
    lateinit var tlsSettings: TlsSettings
        private set

    override suspend fun open() {
        if (state.isAtLeast(ElementState.OPEN)) return
        super.open()

        // Open child elements if they implement AsyncContextElement
        (channelOps as? AsyncContextElement)?.let {
            if (it.state.isLessThan(ElementState.OPEN)) it.open()
        }
        (reactorOps as? AsyncContextElement)?.let {
            if (it.state.isLessThan(ElementState.OPEN)) it.open()
        }

        // TLS settings created here — not at construction time
        tlsSettings = TlsSettings()

        state = ElementState.ACTIVE
    }

    override suspend fun close() {
        if (state.isAtLeast(ElementState.OPEN) && state.isLessThan(ElementState.CLOSED)) {
            state = ElementState.DRAINING
            acceptHandlers.clear()
            readHandlers.clear()
            errorHandlers.clear()
            if (bpfProgramFd >= 0) {
                bpfProgramFd = -1
            }

            // Close child elements
            (channelOps as? AsyncContextElement)?.let {
                if (it.state.isLessThan(ElementState.CLOSED)) it.close()
            }
            (reactorOps as? AsyncContextElement)?.let {
                if (it.state.isLessThan(ElementState.CLOSED)) it.close()
            }

            supervisor.cancel()
            super.close()
        }
    }

    /**
     * Create a new TLS engine for a connection using the reactor's settings.
     */
    fun createTlsEngine(serverName: CharSequence? = null): borg.trikeshed.userspace.nio.tls.TlsEngine {
        val settings = tlsSettings.copy(serverName = serverName)
        return borg.trikeshed.userspace.nio.tls.createTlsEngine(settings)
    }

    // ── eBPF Protocol Detection ──────────────────────────────────────

    fun attachBpfFilter(listenFd: Int): Int = -1

    // ── Event Loop ───────────────────────────────────────────────────

    fun onAccept(handler: (Int) -> Unit) {
        acceptHandlers.add(handler)
    }

    fun onRead(fd: Int, handler: (ByteArray) -> Unit) {
        readHandlers[fd] = handler
    }

    fun onError(fd: Int, handler: (Throwable) -> Unit) {
        errorHandlers[fd] = handler
    }

    suspend fun <T> submitWithThrottle(op: suspend () -> T): T {
        return submitSemaphore.withPermit {
            withContext(supervisor) { op() }
        }
    }

    fun detectProtocol(firstPacket: ByteArray): ProtocolType {
        if (firstPacket.size < 20) return ProtocolType.Unknown

        if (firstPacket[0] == 19.toByte() &&
            firstPacket.copyOfRange(1, 20).contentEquals("BitTorrent protocol".encodeToByteArray())
        ) {
            return ProtocolType.BitTorrent
        }

        val header = firstPacket.decodeToString()
        if (header.startsWith("GET ") || header.startsWith("POST ") || header.startsWith("PUT ")) {
            if (header.contains("Upgrade: websocket", ignoreCase = true)) {
                return ProtocolType.WebSocket
            }
            return ProtocolType.HTTP
        }

        if (header.startsWith("/ipfs/") || header.contains("content-type: application/x-protobuf")) {
            return ProtocolType.IPFS
        }

        return ProtocolType.Unknown
    }

    enum class ProtocolType {
        BitTorrent, IPFS, HTTP, WebSocket, Unknown
    }
}
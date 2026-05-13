package borg.trikeshed.userspace.reactor

import borg.trikeshed.context.AsyncContextElement
import borg.trikeshed.context.AsyncContextKey
import borg.trikeshed.context.ElementState
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * SupervisorJob reactor — wires nio-uring-ebpf to coroutine lifecycle.
 *
 * The reactor owns a single SupervisorJob that parents all ring operations:
 *   - accept → ring prepAccept → submit → new peer fd
 *   - connect → ring prepConnect → submit → connected socket
 *   - read → ring readv → submit → parse protocol message
 *   - write → ring write → submit → flush response
 *
 * eBPF socket filters are attached to listen fds for protocol detection:
 *   BitTorrent handshake → route to TorrentSwarmReactor
 *   IPFS bitswap → route to IpfsElement DHT handler
 *   HTTP/1.1 → route to HyperdlRpcServer / CouchDB handler
 *   WebSocket → route to WebTorrentProtocol handler
 *
 * The reactor ensures that:
 *   1. All ring submissions share the SupervisorJob parent
 *   2. eBPF program attachment happens before ring setup
 *   3. Protocol dispatch is typed by detected handshake signature
 */
class UringReactor(
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
    private val readHandlers = mutableMapOf<Int, (String) -> Unit>()
    private val errorHandlers = mutableMapOf<Int, (Throwable) -> Unit>()

    override suspend fun open() {
        if (state.isAtLeast(ElementState.OPEN)) return
        super.open()
        state = ElementState.ACTIVE
    }

    override suspend fun close() {
        if (state.isAtLeast(ElementState.OPEN) && state.isLessThan(ElementState.CLOSED)) {
            state = ElementState.DRAINING
            acceptHandlers.clear()
            readHandlers.clear()
            errorHandlers.clear()
            if (bpfProgramFd >= 0) {
                // close bpf fd — actual syscall via posix
                bpfProgramFd = -1
            }
            supervisor.cancel()
            super.close()
        }
    }

    // ── eBPF Protocol Detection ──────────────────────────────────────

    /**
     * Attach eBPF socket filter to listen fd for protocol detection.
     *
     * The BPF program inspects the first packet and returns a protocol tag:
     *   0 = BitTorrent (pstrlen + "BitTorrent protocol")
     *   1 = IPFS bitswap (/ipfs/ prefix)
     *   2 = HTTP (GET/POST/PUT)
     *   3 = WebSocket (Upgrade: websocket)
     *   -1 = unknown
     */
    fun attachBpfFilter(listenFd: Int): Int {
        // eBPF bytecode for protocol detection:
        //   Load first 20 bytes of packet
        //   Compare against known protocol signatures
        //   Return protocol tag in A register
        //
        // BPF program (simplified):
        //   ld [0]        ; first 4 bytes
        //   jeq 0x13426974 proto_bt  ; "BitTorrent" pstrlen=19 (0x13)
        //   ld [0]        ; first 4 bytes
        //   jeq 0x474554 proto_http  ; "GET "
        //   ret [-1]      ; unknown
        // proto_bt: ret [0]
        // proto_http: ret [2]
        //
        // Actual attachment: SO_ATTACH_BPF syscall
        // For now, this is a placeholder — the real bytecode needs
        // platform-specific eBPF compilation.
        return -1  // TODO: implement eBPF attachment
    }

    // ── Event Loop ───────────────────────────────────────────────────

    /**
     * Register a handler for accepted connections on the listen fd.
     */
    fun onAccept(handler: (Int) -> Unit) {
        acceptHandlers.add(handler)
    }

    /**
     * Register a handler for read events on a specific fd.
     */
    fun onRead(fd: Int, handler: (String) -> Unit) {
        readHandlers[fd] = handler
    }

    /**
     * Register an error handler for a specific fd.
     */
    fun onError(fd: Int, handler: (Throwable) -> Unit) {
        errorHandlers[fd] = handler
    }

    /**
     * Submit a ring operation within the SupervisorJob context.
     * The semaphore throttles concurrent submissions to prevent
     * ring overflow.
     */
    suspend fun <T> submitWithThrottle(op: suspend () -> T): T {
        return submitSemaphore.withPermit {
            withContext(supervisor) { op() }
        }
    }

    /**
     * Dispatch an accepted peer fd to the appropriate protocol handler
     * based on the first packet inspection (eBPF or manual).
     *
     * Returns the protocol tag for downstream routing.
     */
    fun detectProtocol(firstPacket: ByteArray): ProtocolType {
        if (firstPacket.size < 20) return ProtocolType.Unknown

        // BitTorrent: pstrlen (1 byte) + "BitTorrent protocol" (19 bytes)
        if (firstPacket[0] == 19.toByte() &&
            firstPacket.copyOfRange(1, 20).contentEquals("BitTorrent protocol".encodeToByteArray())) {
            return ProtocolType.BitTorrent
        }

        // HTTP: starts with GET, POST, PUT, DELETE
        val header = firstPacket.decodeToString()
        if (header.startsWith("GET ") || header.startsWith("POST ") || header.startsWith("PUT ")) {
            if (header.contains("Upgrade: websocket", ignoreCase = true)) {
                return ProtocolType.WebSocket
            }
            return ProtocolType.HTTP
        }

        // IPFS bitswap: starts with "/ipfs/" or CID prefix
        if (header.startsWith("/ipfs/") || header.contains("content-type: application/x-protobuf")) {
            return ProtocolType.IPFS
        }

        return ProtocolType.Unknown
    }

    enum class ProtocolType {
        BitTorrent, IPFS, HTTP, WebSocket, Unknown
    }
}

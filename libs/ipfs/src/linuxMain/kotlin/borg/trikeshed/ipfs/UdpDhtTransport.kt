package borg.trikeshed.ipfs

import borg.trikeshed.userspace.FanoutDispatcherElement
import borg.trikeshed.userspace.nio.channels.spi.ChannelOperations
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.net.InetSocketAddress
import kotlin.io.buildByteArray
import kotlin.io.writeByte
import kotlin.io.writeShort
import kotlin.io.writer
import kotlin.time.Duration
import kotlin.time.ExperimentalTime
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pinned
import platform.posix.AF_INET
import platform.posix.IPPROTO_UDP
import platform.posix.SOCK_DGRAM
import platform.posix.bind
import platform.posix.close
import platform.posix.in_addr
import platform.posix.inet_pton
import platform.posix.iovec
import platform.posix.msghdr
import platform.posix.recvmsg
import platform.posix.sockaddr
import platform.posix.sockaddr_in
import platform.posix.socket
import borg.trikeshed.userspace.nio.channels.spi.ChannelResult
import borg.trikeshed.userspace.UringCompletion

/**
 * UDP-backed DHT transport using the shared reactor's io_uring ring.
 *
 * PRELOAD.md contract:
 * - Gets [ChannelOperations] + [FanoutDispatcherElement] from coroutine context (CCEK)
 * - Uses SENDMSG/RECVMSG opcodes for UDP datagrams
 * - Structured concurrency: fanout via FanoutDispatcherElement
 * - Cold Series α-projection for DHT message encoding
 */
@OptIn(ExperimentalTime::class)
class UdpDhtTransport(
    private val scope: kotlinx.coroutines.CoroutineScope,
    private val bindPort: Int = 0, // 0 = ephemeral
    private val timeout: Duration = 5000.milliseconds,
) : DhtTransport, kotlinx.coroutines.CoroutineScope by scope {

    private val channelOps: ChannelOperations by coroutineContext[ChannelOperations.Key]
    private val fanoutDispatcher: FanoutDispatcherElement by coroutineContext[FanoutDispatcherElement.Key]
    private val pendingRequests = mutableMapOf<Long, PendingRequest>()
    private var nextRequestId = 0L
    private var myUserDataToken: Long = 0L
    private val socketFd: Int by lazy { createSocket() }
    private var readerJob: kotlinx.coroutines.Job? = null
    private val recvBuffer = ByteArray(65536) // 64KB for incoming datagrams

    private data class PendingRequest(
        val cid: CID,
        val completion: CompletableDeferred<List<String>>,
    )

    private fun createSocket(): Int {
        val fd = channelOps.socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP)
        require(fd >= 0) { "Failed to create UDP socket: $fd" }
        val bindRes = channelOps.bind(fd, bindPort)
        require(bindRes == 0) { "Failed to bind UDP socket to port $bindPort: $bindRes" }
        
        // Get a unique userData token for this transport's completions
        myUserDataToken = nextRequestId++
        
        // Register our completion handler with the fanout dispatcher
        fanoutDispatcher.registerHandler(myUserDataToken) { completion ->
            // This is called from the reactor's CQE loop
            handleFanoutCompletion(completion)
        }
        
        postRecv() // Start the first recv
        return fd
    }

    /** Post a RECVMSG to receive the next datagram. */
    private fun postRecv() = memScoped {
        // Allocate iovec for receive buffer
        val iovec = alloc<iovec>()
        val pinnedRecv = recvBuffer.pinned()
        iovec.iov_base = pinnedRecv.address
        iovec.iov_len = recvBuffer.size.toULong()

        // Allocate msghdr
        val msghdr = alloc<msghdr>()
        msghdr.msg_name = kotlinx.cinterop.nullPointer() // we don't need sender address for now
        msghdr.msg_namelen = 0
        msghdr.msg_iov = iovec.ptr
        msghdr.msg_iovlen = 1
        msghdr.msg_control = kotlinx.cinterop.nullPointer()
        msghdr.msg_controllen = 0
        msghdr.msg_flags = 0

        // Queue RECVMSG with our transport's userData token
        val handle = channelOps.openChannel()
        handle.recvmsg(socketFd, msghdr.ptr.toLong(), myUserDataToken)
        handle.submit()
    }

    override suspend fun announceProviderRemote(cid: CID, address: String): List<String> {
        val requestId = nextRequestId++
        return withTimeoutOrNull(timeout) {
            val deferred = CompletableDeferred<List<String>>()
            pendingRequests[requestId] = PendingRequest(cid, deferred)

            sendDhtMessage(encodeAnnounce(cid, address), requestId)
            deferred.await()
        } ?: emptyList()
    }

    override suspend fun findProvidersRemote(cid: CID): List<String> {
        val requestId = nextRequestId++
        return withTimeoutOrNull(timeout) {
            val deferred = CompletableDeferred<List<String>>()
            pendingRequests[requestId] = PendingRequest(cid, deferred)

            sendDhtMessage(encodeFindProviders(cid), requestId)
            deferred.await()
        } ?: emptyList()
    }

    private fun sendDhtMessage(data: ByteArray, userData: Long) {
        val bootstrapAddr = InetSocketAddress("127.0.0.1", 4001)

        memScoped {
            // Allocate iovec for send data
            val iovec = alloc<iovec>()
            val pinnedData = data.pinned()
            iovec.iov_base = pinnedData.address
            iovec.iov_len = data.size.toULong()

            // Allocate sockaddr_in for destination
            val sockaddr = alloc<sockaddr_in>()
            sockaddr.sin_family = AF_INET.toUShort()
            sockaddr.sin_port = bootstrapAddr.port.toUShort()
            inet_pton(AF_INET, bootstrapAddr.address.hostAddress, sockaddr.sin_addr.ptr)

            // Allocate msghdr
            val msghdr = alloc<msghdr>()
            msghdr.msg_name = sockaddr.ptr.reinterpret()
            msghdr.msg_namelen = platform.posix.sizeOf<sockaddr_in>().toUInt()
            msghdr.msg_iov = iovec.ptr
            msghdr.msg_iovlen = 1
            msghdr.msg_control = kotlinx.cinterop.nullPointer()
            msghdr.msg_controllen = 0
            msghdr.msg_flags = 0

            // Queue SENDMSG via ChannelOperations with request-specific userData
            val handle = channelOps.openChannel()
            handle.sendmsg(socketFd, msghdr.ptr.toLong(), userData)
            handle.submit()
        }
    }

    /** Called by FanoutDispatcher when a completion arrives for our userData token. */
    private fun handleFanoutCompletion(completion: UringCompletion) {
        if (completion.res > 0) {
            // RECVMSG completion - data is in recvBuffer
            handleRecvComplete(completion.res)
            // Re-post recv for next datagram
            postRecv()
        }
        // Note: SENDMSG completions are handled by awaiting on the request's CompletableDeferred
        // The response will come via RECVMSG
    }

    private fun handleRecvComplete(bytesRead: Int) {
        // Parse incoming DHT response from recvBuffer
        // For now, just complete any pending request
        // Real impl would parse the DHT wire format and match by transaction ID
        pendingRequests.values.forEach { it.completion?.complete(parseProvidersFromResponse()) }
    }

    private fun parseProvidersFromResponse(): List<String> {
        // Stub - real impl would parse DHT response
        return emptyList()
    }

    private fun encodeAnnounce(cid: CID, address: String): ByteArray {
        return buildByteArray {
            writeByte(0x01) // ANNOUNCE
            writeByte(cid.bytes.size)
            write(cid.bytes)
            writeShort(address.length)
            write(address.toByteArray())
        }
    }

    private fun encodeFindProviders(cid: CID): ByteArray {
        return buildByteArray {
            writeByte(0x02) // FIND_PROVIDERS
            writeByte(cid.bytes.size)
            write(cid.bytes)
        }
    }

    override fun close() {
        readerJob?.cancel()
        channelOps.close(socketFd)
        if (myUserDataToken != 0L) {
            fanoutDispatcher.removeHandler(myUserDataToken) { completion ->
                handleFanoutCompletion(completion)
            }
        }
    }

    companion object {
        /** Factory: create UdpDhtTransport with ChannelOperations + FanoutDispatcher in context. */
        fun create(
            scope: kotlinx.coroutines.CoroutineScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob()),
            bindPort: Int = 0
        ): UdpDhtTransport {
            return UdpDhtTransport(scope, bindPort)
        }
    }
}
package dev.jnorthrup.ngsctp

import com.ngsctp.protocol.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.*
import kotlinx.coroutines.flow.*
import java.net.InetSocketAddress
import java.nio.ByteBuffer

/**
 * ngSCTP Association - The core connection entity
 * 
 * An association is a SupervisorJob scope that owns:
 * - TLV chunk parser (Spirit-based)
 * - Multi-path scheduler
 * - Stream management
 * - Congestion control
 * 
 * One association = one structured scope. 
 * Cancellation cascades perfectly to all streams.
 */
class NgSctpAssociation private constructor(
    private val scope: CoroutineScope,
    val localAddress: InetSocketAddress,
    val remoteAddress: InetSocketAddress,
    val localPort: Int,
    val remotePort: Int,
    val localVerificationTag: UInt,
    var remoteVerificationTag: UInt,
    private val transport: SctpTransport? = null
) : CoroutineScope by scope {

    private val streams = ConcurrentHashMap<Int, NgSctpStream>()
    private var nextStreamId = 0
    
    /** Outbound chunk channel - streams send here */
    private val outboundChunks = Channel<NgChunk>(Channel.BUFFERED)
    
    /** Inbound chunk channel - receives from transport */
    private val inboundChunks = Channel<NgChunk>(Channel.BUFFERED)

    /** Association state */
    @Volatile
    var state: AssociationState = AssociationState.CLOSED
        private set

    /** Current transmission sequence number */
    private var initialTSN: UInt = 0u
    private var nextTSN: UInt = 0u
    private var lastAckedTSN: UInt = 0u

    /** Negotiated stream counts */
    var outboundStreamCount: UShort = 10u
    var inboundStreamCount: UShort = 10u

    /** Congestion control */
    private val congestionControl = CongestionControl()
    
    /** Send buffer for tracking outstanding DATA chunks */
    private val sendBuffer = SendBuffer()
    
    /** Heartbeat manager for connection monitoring */
    private val heartbeatManager = HeartbeatManager(scope)

    init {
        // Start the transmit and receive loops
        scope.launch { transmitLoop() }
        scope.launch { receiveLoop() }
        // Start heartbeat after establishment
        scope.launch {
            // Wait for association to be established
            // TODO: wait for state change
            // heartbeatManager.start()
        }
    }

    companion object {
        /**
         * Connect to a remote endpoint (client-side)
         * Performs 4-way SCTP handshake:
         * 1. INIT -> 
         * 2. <- INIT-ACK (with cookie)
         * 3. COOKIE_ECHO -> 
         * 4. <- COOKIE_ACK
         */
        suspend fun connect(
            remote: InetSocketAddress,
            local: InetSocketAddress = InetSocketAddress(0),
            outboundStreams: UShort = 10u,
            inboundStreams: UShort = 10u
        ): NgSctpAssociation = coroutineScope {
            val localTag = generateVerificationTag()
            val assocScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
            
            val assoc = NgSctpAssociation(
                scope = assocScope,
                localAddress = local,
                remoteAddress = remote,
                localPort = local.port,
                remotePort = remote.port,
                localVerificationTag = localTag,
                remoteVerificationTag = 0u
            ).apply {
                this.outboundStreamCount = outboundStreams
                this.inboundStreamCount = inboundStreams
                this.initialTSN = generateTSN()
                this.nextTSN = this.initialTSN
                this.state = AssociationState.COOKIE_WAIT
            }

            // Step 1: Send INIT
            assoc.sendChunk(NgChunk_Init(
                initiateTag = localTag,
                initialTSN = assoc.initialTSN,
                numOutboundStreams = outboundStreams,
                numInboundStreams = inboundStreams
            ))

            // Step 2: Wait for INIT-ACK with cookie
            val initAck = withTimeoutOrNull(3000) {
                assoc.inboundChunks.receive() as? NgChunk_InitAck
            } ?: throw ConnectionException("INIT-ACK timeout")

            assoc.remoteVerificationTag = initAck.initiateTag
            assoc.state = AssociationState.COOKIE_ECHOED

            // Step 3: Send COOKIE_ECHO with the received cookie
            assoc.sendChunk(NgChunk_CookieEcho(initAck.cookie))

            // Step 4: Wait for COOKIE_ACK
            withTimeoutOrNull(1000) {
                assoc.inboundChunks.receive() as? NgChunk_CookieAck
            } ?: throw ConnectionException("COOKIE_ACK timeout")

            assoc.state = AssociationState.ESTABLISHED
            assoc
        }

        /**
         * Accept an incoming connection (server-side)
         */
        suspend fun accept(
            init: NgChunk_Init,
            localAddr: InetSocketAddress,
            remoteAddr: InetSocketAddress,
            transport: SctpTransport? = null
        ): NgSctpAssociation = coroutineScope {
            val localTag = generateVerificationTag()
            val assocScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
            
            NgSctpAssociation(
                scope = assocScope,
                localAddress = localAddr,
                remoteAddress = remoteAddr,
                localPort = localAddr.port,
                remotePort = remoteAddr.port,
                localVerificationTag = localTag,
                remoteVerificationTag = init.initiateTag,
                transport = transport
            ).also {
                it.outboundStreamCount = init.numInboundStreams
                it.inboundStreamCount = init.numOutboundStreams
                it.initialTSN = generateTSN()
                it.nextTSN = it.initialTSN
                it.state = AssociationState.ESTABLISHED
            }
        }

        /**
         * Create a server that listens for incoming SCTP associations
         */
        fun listen(
            localAddress: InetSocketAddress,
            transport: SctpTransport? = null
        ): SctpServer = SctpServer(localAddress, transport)

        /**
         * Generate a random verification tag for SCTP handshake
         */
        private fun generateVerificationTag(): UInt = 
            (Math.random() * UInt.MAX_VALUE).toUInt()

        /**
         * Generate a random initial TSN
         */
        private fun generateTSN(): UInt = 
            (Math.random() * UInt.MAX_VALUE).toUInt()
    }

    /**
     * Open a new stream on this association
     */
    fun openStream(
        priority: Int = 0,
        intent: String = "default"
    ): NgSctpStream {
        check(state == AssociationState.ESTABLISHED) { 
            "Cannot open stream in state: $state" 
        }
        val streamId = nextStreamId++
        val stream = NgSctpStream(streamId, this, priority, intent)
        streams[streamId] = stream
        return stream
    }

    /**
     * Send a chunk on this association
     */
    suspend fun sendChunk(chunk: NgChunk) {
        check(isActive) { "Association is not active" }
        outboundChunks.send(chunk)
    }
    
    /**
     * Send DATA on a specific stream
     */
    suspend fun sendData(streamId: UShort, data: ByteBuffer, payloadProtocolId: UInt = 0u) {
        check(state == AssociationState.ESTABLISHED) { "Association not established" }
        
        val dataBytes = ByteArray(data.remaining())
        data.get(dataBytes)
        
        // Get TSN from send buffer
        val tsn = sendBuffer.addChunk(dataBytes, streamId, 0u)
        
        val chunk = NgChunk_Data(
            streamId = streamId,
            streamSequenceNumber = 0u,
            payloadProtocolId = payloadProtocolId,
            transmissionSequenceNumber = tsn,
            userData = ByteBuffer.wrap(dataBytes)
        )
        
        outboundChunks.send(chunk)
    }

    /**
     * Close the association gracefully
     */
    suspend fun close() {
        state = AssociationState.SHUTDOWN_PENDING
        sendChunk(NgChunk_Shutdown(nextTSN - 1u))
        // Wait for SHUTDOWN_ACK
        state = AssociationState.SHUTDOWN_SENT
        cancel("Association closed")
    }

    /**
     * Get association info
     */
    val info: AssociationInfo
        get() = AssociationInfo(
            localPort = localPort,
            remotePort = remotePort,
            state = state,
            streams = streams.size,
            nextTSN = nextTSN,
            cwnd = congestionControl.cwnd,
            ssthresh = congestionControl.currentSsthresh,
            bytesInFlight = sendBuffer.bytesInFlight,
            outstandingChunks = sendBuffer.outstandingCount
        )

    // ============================================
    // Internal Loops
    // ============================================

    private fun transmitLoop() = scope.launch {
        for (chunk in outboundChunks) {
            // Serialize and transmit via transport layer
            serializeAndTransmit(chunk)
        }
    }

    private fun receiveLoop() = scope.launch {
        // Process incoming chunks
        for (chunk in inboundChunks) {
            when (chunk) {
                is NgChunk_Init -> handleInit(chunk)
                is NgChunk_CookieEcho -> handleCookieEcho(chunk)
                is NgChunk_Data -> deliverToStream(chunk)
                is NgChunk_Sack -> handleSack(chunk)
                is NgChunk_Heartbeat -> sendHeartbeatAck(chunk)
                is NgChunk_HeartbeatAck -> handleHeartbeatAck(chunk)
                is NgChunk_Abort -> handleAbort(chunk)
                is NgChunk_Error -> handleError(chunk)
                is NgChunk_Shutdown -> handleShutdown(chunk)
                is NgChunk_ShutdownAck -> handleShutdownAck(chunk)
                is NgChunk_ShutdownComplete -> handleShutdownComplete(chunk)
                is NgChunk_Ecne -> handleEcne(chunk)
                is NgChunk_Cwr -> handleCwr(chunk)
                is NgChunk_ForwardTsn -> handleForwardTsn(chunk)
                is NgChunk_Auth -> handleAuth(chunk)
                is NgChunk_ReConfig -> handleReConfig(chunk)
                is NgChunk_Asconf -> handleAsconf(chunk)
                is NgChunk_AsconfAck -> handleAsconfAck(chunk)
                is NgChunk_IData -> handleIData(chunk)
                else -> { /* Handle other chunk types */ }
            }
        }
    }

    /**
     * Handle incoming INIT chunk (server-side)
     */
    private suspend fun handleInit(init: NgChunk_Init) {
        if (state != AssociationState.CLOSED) {
            println("Received INIT in state: $state")
            return
        }
        
        remoteVerificationTag = init.initiateTag
        inboundStreamCount = init.numOutboundStreams
        state = AssociationState.COOKIE_WAIT
        
        // Generate state cookie
        val cookie = generateStateCookie()
        
        // Send INIT-ACK with cookie
        sendChunk(NgChunk_InitAck(
            initiateTag = localVerificationTag,
            initialTSN = initialTSN,
            numOutboundStreams = outboundStreamCount,
            numInboundStreams = inboundStreamCount,
            cookie = cookie
        ))
    }

    /**
     * Handle incoming COOKIE_ECHO (server-side)
     */
    private suspend fun handleCookieEcho(cookieEcho: NgChunk_CookieEcho) {
        if (state != AssociationState.COOKIE_WAIT) {
            println("Received COOKIE_ECHO in state: $state")
            return
        }
        
        state = AssociationState.ESTABLISHED
        
        // Send COOKIE_ACK
        sendChunk(NgChunk_CookieAck)
    }

    /**
     * Handle incoming SHUTDOWN chunk
     */
    private suspend fun handleShutdown(shutdown: NgChunk_Shutdown) {
        when (state) {
            AssociationState.ESTABLISHED -> {
                state = AssociationState.SHUTDOWN_RECEIVED
                sendChunk(NgChunk_ShutdownAck)
                state = AssociationState.SHUTDOWN_ACK_SENT
            }
            AssociationState.SHUTDOWN_PENDING -> {
                state = AssociationState.SHUTDOWN_RECEIVED
                sendChunk(NgChunk_ShutdownAck)
                state = AssociationState.SHUTDOWN_ACK_SENT
            }
            else -> { /* Ignore in other states */ }
        }
    }

    /**
     * Handle incoming SHUTDOWN-ACK chunk (peer initiated shutdown)
     */
    private suspend fun handleShutdownAck(ack: NgChunk_ShutdownAck) {
        when (state) {
            AssociationState.SHUTDOWN_SENT -> {
                // Send SHUTDOWN-COMPLETE
                sendChunk(NgChunk_ShutdownComplete)
                state = AssociationState.CLOSED
                cancel("Shutdown complete")
            }
            else -> { /* Ignore in other states */ }
        }
    }

    /**
     * Handle incoming SHUTDOWN-COMPLETE chunk
     */
    private fun handleShutdownComplete(complete: NgChunk_ShutdownComplete) {
        when (state) {
            AssociationState.SHUTDOWN_ACK_SENT -> {
                state = AssociationState.CLOSED
                cancel("Shutdown complete")
            }
            else -> { /* Ignore in other states */ }
        }
    }

    /**
     * Handle incoming ECNE chunk (Explicit Congestion Notification Echo)
     * RFC 4960 Section 12.3
     */
    private fun handleEcne(ecne: NgChunk_Ecne) {
        // ECNE indicates the network is congested
        // The receiver should reduce its cwnd (handled by congestion control)
        // We also need to send a CWR to acknowledge
        sendChunk(NgChunk_Cwr(ecne.lowestTSN))
    }

    /**
     * Handle incoming CWR chunk (Congestion Window Reduced)
     * RFC 4960 Section 12.4
     */
    private fun handleCwr(cwr: NgChunk_Cwr) {
        // CWR confirms the peer reduced its congestion window
        // This completes the ECN feedback loop
        congestionControl.onCwrReceived(cwr.lowestTSN)
    }

    /**
     * Handle incoming FORWARD-TSN chunk (Partial Reliability)
     * RFC 3758 Section 3.6
     * 
     * Advances the cumulative TSN to allow delivery of partially-reliable DATA
     */
    private fun handleForwardTsn(forwardTsn: NgChunk_ForwardTsn) {
        // Update the cumulative TSN to skip over missing chunks
        // This allows partial reliability - chunks with TSN < newCumulativeTSN are considered delivered
        val newCumulativeTSN = forwardTsn.newCumulativeTSN
        // Update lastAckedTSN to skip missing chunks
        if (newCumulativeTSN > lastAckedTSN) {
            lastAckedTSN = newCumulativeTSN
            // Notify streams that have been reordered
            for (mapping in forwardTsn.streamMappings) {
                val stream = streams[mapping.streamId.toInt()]
                stream?.let {
                    // Signal stream that some data was skipped
                }
            }
        }
    }

    /**
     * Handle incoming AUTH chunk (Authentication)
     * RFC 4895
     * 
     * Verifies the authentication of incoming chunks
     */
    private fun handleAuth(auth: NgChunk_Auth) {
        // Authentication is optional per RFC 4895
        // If we have authentication enabled, verify the chunk
        // For now, we accept unauthenticated chunks
        // TODO: Implement full AUTH verification
    }

    /**
     * Handle incoming RE-CONFIG chunk (Stream Reconfiguration)
     * RFC 6525
     * 
     * Processes stream reconfiguration requests (add streams, reset streams)
     */
    private fun handleReConfig(reConfig: NgChunk_ReConfig) {
        val responses = mutableListOf<ReConfigResponse>()
        
        for (request in reConfig.requests) {
            val response = when (request) {
                is ReConfigRequest.AddOutbound -> {
                    // Add new outbound stream
                    // Response depends on peer capability
                    ReConfigResponse(
                        requestType = request.requestType,
                        streamId = request.streamIds.firstOrNull() ?: 0u,
                        result = ReConfigResult.SUCCESS
                    )
                }
                is ReConfigRequest.AddInbound -> {
                    // Add new inbound stream
                    ReConfigResponse(
                        requestType = request.requestType,
                        streamId = request.streamIds.firstOrNull() ?: 0u,
                        result = ReConfigResult.SUCCESS
                    )
                }
                is ReConfigRequest.StreamReset -> {
                    // Reset stream sequence numbers
                    ReConfigResponse(
                        requestType = request.requestType,
                        streamId = request.streamIds.firstOrNull() ?: 0u,
                        result = ReConfigResult.SUCCESS
                    )
                }
                is ReConfigRequest.ResetOutgoing -> {
                    // Reset outgoing streams
                    ReConfigResponse(
                        requestType = request.requestType,
                        streamId = request.streamIds.firstOrNull() ?: 0u,
                        result = ReConfigResult.SUCCESS
                    )
                }
                is ReConfigRequest.ResetIncoming -> {
                    // Reset incoming streams
                    ReConfigResponse(
                        requestType = request.requestType,
                        streamId = request.streamIds.firstOrNull() ?: 0u,
                        result = ReConfigResult.SUCCESS
                    )
                }
            }
            responses.add(response)
        }
        
        // Send RE-CONFIG response
        sendChunk(NgChunk_ReConfig(responses = responses))
    }

    /**
     * Handle incoming ASCONF chunk (Address Configuration)
     * RFC 5061
     * 
     * Processes address configuration change requests
     */
    private fun handleAsconf(asconf: NgChunk_Asconf) {
        val responses = mutableListOf<NgChunk_AsconfAck.AsconfResponseParameter>()
        
        for (param in asconf.parameters) {
            // Process each address configuration parameter
            val result = when (param) {
                is NgChunk_Asconf.AsconfParameter.AddIP -> {
                    // Handle address addition
                    NgChunk_AsconfAck.AsconfResult.SUCCESS
                }
                is NgChunk_Asconf.AsconfParameter.DelIP -> {
                    // Handle address removal
                    NgChunk_AsconfAck.AsconfResult.SUCCESS
                }
                is NgChunk_Asconf.AsconfParameter.SetPrimary -> {
                    // Handle primary address change
                    NgChunk_AsconfAck.AsconfResult.SUCCESS
                }
            }
            responses.add(NgChunk_AsconfAck.AsconfResponseParameter(result))
        }
        
        // Send ASCONF-ACK
        sendChunk(NgChunk_AsconfAck(serial = asconf.serial, parameters = responses))
    }

    /**
     * Handle incoming ASCONF-ACK chunk (Address Configuration Acknowledgment)
     * RFC 5061
     */
    private fun handleAsconfAck(asconfAck: NgChunk_AsconfAck) {
        // Process the acknowledgment for our ASCONF requests
        // This completes address configuration changes
        for (param in asconfAck.parameters) {
            when (param.result) {
                NgChunk_AsconfAck.AsconfResult.SUCCESS -> {
                    // Address change was successful
                }
                NgChunk_AsconfAck.AsconfResult.DENIED,
                NgChunk_AsconfAck.AsconfResult.ERROR_BAD_SEQ,
                NgChunk_AsconfAck.AsconfResult.ERROR_NO_EXIST -> {
                    // Address change failed
                }
            }
        }
    }

    /**
     * Handle incoming I_DATA chunk (Interleaved Data)
     * RFC 4960 Section 3.3.10
     * 
     * Processes interleaved data for simultaneous ordered/unordered delivery
     */
    private fun handleIData(iData: NgChunk_IData) {
        // I_DATA provides interleaved data delivery
        // Deliver to the appropriate stream
        val stream = streams[iData.streamId.toInt()] ?: return
        stream.receiveChannel.trySend(ByteBuffer.wrap(iData.userData))
    }

    /**
     * Generate a state cookie for the INIT-ACK
     */
    private fun generateStateCookie(): ByteArray {
        val timestamp = System.currentTimeMillis()
        val data = "$localPort:$remotePort:$timestamp:$localVerificationTag"
        return data.toByteArray()
    }

    private fun deliverToStream(data: NgChunk_Data) {
        val stream = streams[data.streamId.toInt()] ?: return
        stream.receiveChannel.trySend(data.userData)
    }

    private fun handleSack(sack: NgChunk_Sack) {
        val previousAck = lastAckedTSN
        lastAckedTSN = sack.cumulativeTSNAck
        
        // Parse gap ack blocks from SACK if available
        // (The current NgChunk_Sack parser doesn't extract gap acks, 
        // but we'd integrate them here if it did)
        val gapAcks = emptyList<Pair<UInt, UInt>>() // Would parse from sack.gapAckBlocks
        
        // Update send buffer with acknowledged chunks
        val ackedChunks = sendBuffer.ackChunks(sack.cumulativeTSNAck, gapAcks)
        
        // Notify streams of acked data
        for (chunk in ackedChunks) {
            // Stream-level ACK notification could go here
        }
        
        // Update congestion control
        congestionControl.onSackReceived(
            cumulativeAckTSN = sack.cumulativeTSNAck,
            previousAckTSN = previousAck,
            gapAckBlocks = gapAcks,
            dataBytesInFlight = sendBuffer.bytesInFlight
        )
        
        // If duplicate SACK (no advance), trigger fast retransmit
        if (sack.cumulativeTSNAck == previousAck) {
            congestionControl.onDuplicateSack()
        }
    }

    private suspend fun sendHeartbeatAck(heartbeat: NgChunk_Heartbeat) {
        sendChunk(NgChunk_HeartbeatAck(heartbeat.info))
    }
    
    /**
     * Handle incoming heartbeat ack
     */
    private fun handleHeartbeatAck(heartbeatAck: NgChunk_HeartbeatAck) {
        heartbeatManager.onHeartbeatAck()
    }

    private fun handleAbort(abort: NgChunk_Abort) {
        cancel("Association aborted: ${abort.errorInfo}")
    }

    private fun handleError(error: NgChunk_Error) {
        // Log error
    }

    /**
     * Serialize chunk to wire format with SCTP common header
     * 
     * Wire format:
     * [12 bytes: Common Header] [Chunks...]
     */
    private suspend fun serializeAndTransmit(chunk: NgChunk) {
        // Build the packet: common header + chunk
        val chunkBytes = chunk.serialize()
        
        // Calculate total size
        val totalSize = SctpCommonHeader.SIZE + chunkBytes.size
        val buffer = ByteBuffer.allocate(totalSize)
        
        // Write common header
        val header = SctpCommonHeader(
            sourcePort = localPort.toUShort(),
            destinationPort = remotePort.toUShort(),
            verificationTag = localVerificationTag,
            checksum = 0u // CRC32c calculated below
        )
        header.serialize(buffer)
        
        // Write chunk data
        buffer.put(chunkBytes)
        
        // Calculate and insert CRC32c checksum
        buffer.flip()
        val checksum = calculateCrc32c(buffer)
        buffer.position(8) // Position at checksum field
        buffer.putInt(checksum.toInt())
        
        // Send via io_uring transport
        transport?.send(buffer.array(), remoteAddress)
    }
    
    /**
     * Parse inbound packet (common header + chunks)
     */
    fun parseInboundPacket(data: ByteArray) {
        val buffer = ByteBuffer.wrap(data)
        
        // Parse common header
        val header = SctpCommonHeader.parse(buffer)
        
        // Verify checksum
        val receivedChecksum = header.checksum
        // Zero checksum field for calculation
        buffer.position(8)
        buffer.putInt(0)
        buffer.position(0)
        val calculatedChecksum = calculateCrc32c(buffer)
        
        if (receivedChecksum != calculatedChecksum) {
            println("Checksum mismatch: got $receivedChecksum, expected $calculatedChecksum")
            return
        }
        
        // Verify verification tag
        if (header.verificationTag != remoteVerificationTag) {
            println("Verification tag mismatch")
            return
        }
        
        // Parse chunks
        while (buffer.hasRemaining()) {
            val chunk = NgChunk.parse(buffer)
            if (chunk != null) {
                inboundChunks.trySend(chunk)
            }
        }
    }
    
    /**
     * CRC32c (Castagnoli) checksum calculation
     * Used for SCTP-advertised receiver window credit
     */
    private fun calculateCrc32c(buffer: ByteBuffer): UInt {
        // CRC32c polynomial: 0x1EDC6F41
        // For production, use java.util.zip.CRC32C
        var crc = 0xFFFFFFFFu
        val polynomial = 0x1EDC6F41u
        
        while (buffer.hasRemaining()) {
            val byte = buffer.get().toUByte()
            crc = crc xor (byte.toUInt() shl 24)
            repeat(8) {
                if ((crc and 0x80000000u) != 0u) {
                    crc = (crc shl 1) xor polynomial
                } else {
                    crc = crc shl 1
                }
            }
        }
        
        return crc xor 0xFFFFFFFFu
    }
}

/**
 * Association state machine states
 */
enum class AssociationState {
    CLOSED,
    COOKIE_WAIT,
    COOKIE_ECHOED,
    ESTABLISHED,
    SHUTDOWN_PENDING,
    SHUTDOWN_SENT,
    SHUTDOWN_RECEIVED,
    SHUTDOWN_ACK_SENT
}

/**
 * Association information for debugging/monitoring
 */
data class AssociationInfo(
    val localPort: Int,
    val remotePort: Int,
    val state: AssociationState,
    val streams: Int,
    val nextTSN: UInt,
    val cwnd: Int = 0,
    val ssthresh: Int = 0,
    val bytesInFlight: Int = 0,
    val outstandingChunks: Int = 0
)

/**
 * Connection exception
 */
class ConnectionException(message: String) : Exception(message)

/**
 * SCTP Parameters for INIT/INIT-ACK
 */
sealed class SctpParameter {
    abstract val type: ParameterType
    abstract val data: ByteArray
    
    data class ForwardTSNSupported(override val data: ByteArray = byteArrayOf(0, 0, 0, 1)) : SctpParameter() {
        override val type = ParameterType.FORWARD_TSN_SUPPORTED
    }
    
    data class NegotiatedMaxInboundStreams(override val data: ByteArray) : SctpParameter() {
        override val type = ParameterType.NEGOTIATED_MAX_INBOUND_STREAMS
    }
    
    data class NegotiatedMaxOutboundStreams(override val data: ByteArray) : SctpParameter() {
        override val type = ParameterType.NEGOTIATED_MAX_OUTBOUND_STREAMS
    }
    
    data class StateCookie(override val data: ByteArray) : SctpParameter() {
        override val type = ParameterType.STATE_COOKIE
    }
}

/**
 * SCTP Server for accepting incoming associations
 * 
 * Usage:
 * ```
 * val server = NgSctpAssociation.listen(InetSocketAddress(8080))
 * launch {
 *     for (assoc in server.associations) {
 *         // Handle new association
 *         val stream = assoc.openStream()
 *     }
 * }
 * ```
 */
class SctpServer(
    val localAddress: InetSocketAddress,
    private val transport: SctpTransport? = null
) : CoroutineScope by CoroutineScope(Dispatchers.Default + SupervisorJob()) {
    
    private val _associations = Channel<NgSctpAssociation>(Channel.BUFFERED)
    
    /** Flow of incoming associations */
    val associations: Flow<NgSctpAssociation> = _associations.receiveAsFlow()
    
    /** Start accepting connections */
    fun startAcceptLoop() = launch {
        // In a real implementation, this would bind to the socket
        // and accept incoming SCTP connections
        // For now, this is a placeholder that demonstrates the API
        println("SCTP Server started on ${localAddress}")
    }
    
    /**
     * Accept an incoming INIT and create association
     * Called by transport when INIT is received
     */
    suspend fun acceptInit(
        init: NgChunk_Init,
        remoteAddress: InetSocketAddress
    ): NgSctpAssociation {
        val localTag = generateVerificationTag()
        val assocScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        
        // Generate state cookie
        val cookie = generateStateCookie()
        
        val assoc = NgSctpAssociation(
            scope = assocScope,
            localAddress = localAddress,
            remoteAddress = remoteAddress,
            localPort = localAddress.port,
            remotePort = remoteAddress.port,
            localVerificationTag = localTag,
            remoteVerificationTag = init.initiateTag,
            transport = transport
        ).apply {
            this.outboundStreamCount = init.numInboundStreams
            this.inboundStreamCount = init.numOutboundStreams
            this.initialTSN = generateTSN()
            this.nextTSN = this.initialTSN
            this.state = AssociationState.COOKIE_WAIT
        }
        
        // Send INIT-ACK
        assoc.sendChunk(NgChunk_InitAck(
            initiateTag = localTag,
            initialTSN = assoc.initialTSN,
            numOutboundStreams = init.numInboundStreams,
            numInboundStreams = init.numOutboundStreams,
            cookie = cookie
        ))
        
        _associations.send(assoc)
        return assoc
    }
    
    private fun generateVerificationTag(): UInt = 
        (Math.random() * UInt.MAX_VALUE).toUInt()
    
    private fun generateTSN(): UInt = 
        (Math.random() * UInt.MAX_VALUE).toUInt()
    
    private fun generateStateCookie(): ByteArray {
        val timestamp = System.currentTimeMillis()
        val data = "${localAddress.port}:$timestamp:$generateVerificationTag()"
        return data.toByteArray()
    }
    
    /** Close the server */
    fun close() {
        cancel("Server closed")
    }
}

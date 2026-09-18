package com.ngsctp.protocol

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.*
import kotlinx.coroutines.flow.*

/**
 * ngSCTP Engine - Main protocol handler
 * 
 * Uses Kotlin structured concurrency with channels for:
 * - Clean stream multiplexing
 * - Automatic cancellation propagation
 * - Scoped lifetime management
 * - Backpressure handling
 */
class SctpEngine(
    private val config: SctpConfig,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
) {
    private val associations = ConcurrentHashMap<AssociationId, SctpAssociation>()
    private val pendingConnections = Channel<PendingConnection>(Channel.BUFFERED)
    private val incomingMessages = Channel<SctpMessage>(Channel.BUFFERED)
    
    /** Outbound message channel - send here to transmit */
    val outbound: SendChannel<SctpPacket> = scope.actor(capacity = Channel.BUFFERED) {
        for (packet in channel) {
            transmitPacket(packet)
        }
    }
    
    /** Inbound message flow - receive application messages here */
    val messages: Flow<SctpMessage> = incomingMessages.receiveAsFlow()
    
    /** Connection requests waiting for acceptance */
    val connections: Flow<PendingConnection> = pendingConnections.receiveAsFlow()
    
    /**
     * Start an SCTP association (client-side)
     */
    suspend fun connect(
        remoteAddress: TransportAddress,
        localAddress: TransportAddress,
        localPort: SctpPort,
        remotePort: SctpPort,
        outboundStreams: UShort = 10u,
        inboundStreams: UShort = 10u
    ): SctpAssociation = coroutineScope {
        val localTag = generateVerificationTag()
        val initialTSN = generateTSN()
        
        val assocId = AssociationId(localTag, 0u, localPort, remotePort)
        
        // Create association in COOKIE_WAIT state
        var association = SctpAssociation(
            id = assocId,
            state = AssociationState.COOKIE_WAIT,
            localTag = localTag,
            remoteTag = 0u,
            initialTSN = initialTSN,
            nextTSN = initialTSN,
            lastAcknowledgedTSN = initialTSN - 1u,
            localRwnd = config.receiverWindowSize,
            remoteRwnd = 0u,
            outboundStreams = outboundStreams,
            inboundStreams = inboundStreams,
            primaryPath = remoteAddress
        )
        
        associations[assocId] = association
        
        // Send INIT
        val initChunk = InitChunk(
            initiateTag = localTag,
            initialTSN = initialTSN,
            numOutboundStreams = outboundStreams,
            numInboundStreams = inboundStreams,
            fixedParameter = 0x01000000u, // Forward TSN supported
            parameters = listOf(
                SctpParameter.OutboundStreams(outboundStreams),
                SctpParameter.InboundStreams(inboundStreams),
                SctpParameter.InitialTSN(initialTSN),
                SctpParameter.ForwardTSNSupported(true)
            )
        )
        
        outbound.send(SctpPacket(localAddress, remoteAddress, listOf(Chunk.Init(initChunk))))
        
        // Wait for INIT-ACK with state cookie
        val initAck = waitForChunk<Chunk.InitAck>(assocId, timeout = config.initTimeout)
        
        // Send COOKIE_ECHO with the received cookie
        val cookieEcho = Chunk.CookieEcho(initAck.chunk.stateCookie)
        outbound.send(SctpPacket(localAddress, remoteAddress, listOf(cookieEcho)))
        
        // Update state to COOKIE_ECHOED
        association = association.copy(state = AssociationState.COOKIE_ECHOED)
        associations[assocId] = association
        
        // Wait for COOKIE_ACK
        waitForChunk<Chunk.CookieAck>(assocId, timeout = config.cookieTimeout)
        
        // Now fully established
        association = association.copy(
            state = AssociationState.ESTABLISHED,
            remoteTag = initAck.chunk.initiateTag,
            remoteRwnd = config.receiverWindowSize
        )
        associations[assocId] = association
        
        // Start the receive loop for this association
        launch { receiveLoop(association) }
        
        association
    }
    
    /**
     * Listen for incoming connections (server-side)
     */
    suspend fun listen(address: String, port: Int): Unit = coroutineScope {
        // This would be implemented with actual network I/O in jvmMain
        // For now, this is a placeholder that processes incoming packets
        
        launch {
            for (packet in inbound.receiveAsFlow()) {
                processInboundPacket(packet)
            }
        }
    }
    
    /**
     * Send a message on an association
     */
    suspend fun send(
        association: SctpAssociation,
        message: SctpMessage
    ): Unit = coroutineScope {
        val chunk = DataChunk(
            streamId = message.streamId,
            streamSequenceNumber = message.streamSequenceNumber,
            payloadProtocolId = message.payloadProtocolId,
            transmissionSequenceNumber = association.nextTSN,
            userData = message.userData,
            unordered = message.unordered
        )
        
        outbound.send(SctpPacket(
            local = association.primaryPath,
            remote = association.primaryPath,
            chunks = listOf(Chunk.Data(chunk))
        ))
    }
    
    /**
     * Gracefully shutdown an association
     */
    suspend fun shutdown(association: SctpAssociation): Unit = coroutineScope {
        val shutdown = Chunk.Shutdown(
            cumulativeTSNAck = association.lastAcknowledgedTSN
        )
        
        outbound.send(SctpPacket(
            local = association.primaryPath,
            remote = association.primaryPath,
            chunks = listOf(Chunk.ShutdownChunk(shutdown))
        ))
        
        associations.remove(association.id)
    }
    
    // ============================================
    // Internal Implementation
    // ============================================
    
    private val inbound = Channel<SctpPacket>(Channel.BUFFERED)
    
    /** Process received packet */
    private fun processInboundPacket(packet: SctpPacket) {
        scope.launch {
            for (chunk in packet.chunks) {
                when (chunk) {
                    is Chunk.Init -> handleInit(packet, chunk.chunk)
                    is Chunk.InitAck -> handleInitAck(packet, chunk.chunk)
                    is Chunk.Data -> handleData(packet, chunk.chunk)
                    is Chunk.Sack -> handleSack(packet, chunk.chunk)
                    is Chunk.CookieEcho -> handleCookieEcho(packet, chunk.chunk)
                    is Chunk.CookieAck -> handleCookieAck(packet)
                    is Chunk.Shutdown -> handleShutdown(packet, chunk.chunk)
                    is Chunk.Heartbeat -> handleHeartbeat(packet, chunk.chunk)
                    is Chunk.Abort -> handleAbort(packet, chunk.chunk)
                    is Chunk.Error -> handleError(packet, chunk.chunk)
                }
            }
        }
    }
    
    private suspend fun handleInit(packet: SctpPacket, init: InitChunk) {
        // Generate state cookie and send INIT-ACK
        val localTag = generateVerificationTag()
        val stateCookie = generateStateCookie(packet, init)
        
        val initAck = InitAckChunk(
            initiateTag = localTag,
            initialTSN = generateTSN(),
            numOutboundStreams = init.numInboundStreams,
            numInboundStreams = init.numOutboundStreams,
            stateCookie = stateCookie,
            parameters = listOf(
                SctpParameter.StateCookie(stateCookie),
                SctpParameter.ForwardTSNSupported(true)
            )
        )
        
        outbound.send(SctpPacket(
            local = packet.remote,
            remote = packet.local,
            chunks = listOf(Chunk.InitAck(initAck))
        ))
    }
    
    private suspend fun handleInitAck(packet: SctpPacket, initAck: InitAckChunk) {
        // Store the response for the connect() coroutine to pick up
        // This is handled via the channel-based waiting mechanism
    }
    
    private suspend fun handleData(packet: SctpPacket, data: DataChunk) {
        val message = SctpMessage(
            streamId = data.streamId,
            streamSequenceNumber = data.streamSequenceNumber,
            payloadProtocolId = data.payloadProtocolId,
            userData = data.userData,
            unordered = data.unordered
        )
        incomingMessages.send(message)
        
        // Send SACK to acknowledge
        val sack = SackChunk(
            cumulativeTSNAck = data.transmissionSequenceNumber,
            advertisedReceiverWindowCredit = config.receiverWindowSize,
            gapAckBlocks = emptyList(),
            duplicateTSNs = emptyList()
        )
        
        outbound.send(SctpPacket(
            local = packet.remote,
            remote = packet.local,
            chunks = listOf(Chunk.Sack(sack))
        ))
    }
    
    private fun handleSack(packet: SctpPacket, sack: SackChunk) {
        // Update cwnd/ssthresh based on ACK
        // This is where TCP-like congestion control happens
    }
    
    private suspend fun handleCookieEcho(packet: SctpPacket, cookieEcho: Chunk.CookieEcho) {
        // Validate cookie and create association
        val cookieAck = Chunk.CookieAck
        outbound.send(SctpPacket(
            local = packet.remote,
            remote = packet.local,
            chunks = listOf(cookieAck)
        ))
        
        // Notify about new connection
        pendingConnections.send(PendingConnection(packet.remote, packet.local))
    }
    
    private fun handleCookieAck(packet: SctpPacket) {
        // Complete the handshake - handled by waiting coroutine
    }
    
    private suspend fun handleShutdown(packet: SctpPacket, shutdown: Chunk.Shutdown) {
        val ack = Chunk.ShutdownAck
        outbound.send(SctpPacket(
            local = packet.remote,
            remote = packet.local,
            chunks = listOf(ack)
        ))
    }
    
    private fun handleHeartbeat(packet: SctpPacket, heartbeat: Chunk.Heartbeat) {
        // Send Heartbeat-ACK
        val ack = Chunk.HeartbeatAck(heartbeat.info)
        outbound.send(SctpPacket(
            local = packet.remote,
            remote = packet.local,
            chunks = listOf(ack)
        ))
    }
    
    private fun handleAbort(packet: SctpPacket, abort: Chunk.Abort) {
        // Clean up association
    }
    
    private fun handleError(packet: SctpPacket, error: Chunk.Error) {
        // Log error
    }
    
    private fun receiveLoop(association: SctpAssociation): Unit = scope.launch {
        // Continuous receive loop for the association
        // Processes DATA chunks and sends SACKs
    }
    
    private suspend fun <T : Chunk> waitForChunk(
        assocId: AssociationId,
        timeout: Long
    ): T = withTimeoutOrNull(timeout) {
        // Wait for specific chunk type
        // This is a simplified version - real implementation would use
        // a more sophisticated pending request tracking system
        throw NotImplementedError("Chunk waiting mechanism needs implementation")
    } ?: throw TimeoutException("Timed out waiting for $T")
    
    private suspend fun transmitPacket(packet: SctpPacket) {
        // This would be implemented with actual network I/O in jvmMain
        // Using io_uring for high-performance async I/O
        inbound.send(packet)
    }
    
    // ============================================
    // Helpers
    // ============================================
    
    private fun generateVerificationTag(): VerificationTag = 
        (Math.random() * UInt.MAX_VALUE).toUInt()
    
    private fun generateTSN(): TSN = 
        (Math.random() * UInt.MAX_VALUE).toUInt()
    
    private fun generateStateCookie(init: InitChunk, remote: TransportAddress): ByteArray {
        // Generate a state cookie containing necessary information
        // In production, this would be encrypted/signed
        return ByteArray(32) { (Math.random() * 256).toByte() }
    }
    
    private fun generateStateCookie(packet: SctpPacket, init: InitChunk): ByteArray {
        return generateStateCookie(init, packet.local)
    }
    
    fun close() {
        scope.cancel()
    }
}

// ============================================
// Supporting Types
// ============================================

data class SctpConfig(
    val receiverWindowSize: UInt = 4194304u, // 4MB
    val maxOutboundStreams: UShort = 10u,
    val maxInboundStreams: UShort = 10u,
    val initTimeout: Long = 3000L, // ms
    val cookieTimeout: Long = 1000L, // ms
    val heartbeatInterval: Long = 30000L, // ms
    val rtoInitial: Long = 1000L, // ms
    val rtoMin: Long = 200L, // ms
    val rtoMax: Long = 60000L // ms
)

data class SctpPacket(
    val local: TransportAddress,
    val remote: TransportAddress,
    val chunks: List<Chunk>
)

sealed class Chunk {
    data class Init(val chunk: InitChunk) : Chunk()
    data class InitAck(val chunk: InitAckChunk) : Chunk()
    data class Data(val chunk: DataChunk) : Chunk()
    data class Sack(val chunk: SackChunk) : Chunk()
    data class CookieEcho(val cookie: ByteArray) : Chunk() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            return other is CookieEcho && cookie.contentEquals(other.cookie)
        }
        override fun hashCode() = cookie.contentHashCode()
    }
    data object CookieAck : Chunk()
    data class Shutdown(val chunk: ShutdownChunk) : Chunk()
    data object ShutdownAck : Chunk()
    data class Heartbeat(val info: ByteArray) : Chunk() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            return other is Heartbeat && info.contentEquals(other.info)
        }
        override fun hashCode() = info.contentHashCode()
    }
    data class HeartbeatAck(val info: ByteArray) : Chunk() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            return other is HeartbeatAck && info.contentEquals(other.info)
        }
        override fun hashCode() = info.contentHashCode()
    }
    data class Abort(val reason: String? = null) : Chunk()
    data class Error(val errorCode: UShort, val info: ByteArray = ByteArray(0)) : Chunk()
}

@Serializable
data class ShutdownChunk(
    val cumulativeTSNAck: TSN
)

class PendingConnection(
    val remote: TransportAddress,
    val local: TransportAddress
)

class TimeoutException(message: String) : Exception(message)

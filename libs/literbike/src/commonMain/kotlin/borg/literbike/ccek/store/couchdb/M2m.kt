package borg.literbike.ccek.store.couchdb

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BroadcastChannel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * M2M communication manager for inter-node messaging
 */
class M2mManager(
    val nodeId: String,
    private val config: M2mConfig
) {
    private val peers = mutableMapOf<String, PeerInfo>()
    private val peersMutex = Mutex()
    private val messageHandlers = mutableMapOf<M2mMessageType, MessageHandler>()
    private val handlersMutex = Mutex()
    private val messageQueue = mutableListOf<M2mMessage>()
    private val queueMutex = Mutex()
    private val broadcastChannel = BroadcastChannel<M2mMessage>(Channel.BUFFERED)
    private val metrics = M2mMetrics()
    private val metricsMutex = Mutex()
    private var startTime: Instant = Clock.System.INSTANT

    companion object {
        fun new(nodeId: String? = null, config: M2mConfig = M2mConfig.default()): M2mManager {
            val id = nodeId ?: generateUuid()
            return M2mManager(id, config)
        }
    }

    /**
     * Register a message handler
     */
    fun registerHandler(handler: MessageHandler) {
        messageHandlers[handler.messageType] = handler
    }

    /**
     * Send message to a specific peer
     */
    suspend fun sendMessage(recipient: String, messageType: M2mMessageType, payload: String): CouchResult<Unit> {
        val message = M2mMessage(
            id = generateUuid(),
            sender = nodeId,
            recipient = recipient,
            messageType = messageType,
            payload = payload,
            timestamp = Clock.System.INSTANT,
            ttl = config.messageTtlSecs
        )

        queueMessage(message)
        return Result.success(Unit)
    }

    /**
     * Broadcast message to all peers
     */
    suspend fun broadcastMessage(messageType: M2mMessageType, payload: String): CouchResult<Unit> {
        val message = M2mMessage(
            id = generateUuid(),
            sender = nodeId,
            recipient = null, // None indicates broadcast
            messageType = messageType,
            payload = payload,
            timestamp = Clock.System.INSTANT,
            ttl = config.messageTtlSecs
        )

        queueMessage(message)
        return Result.success(Unit)
    }

    /**
     * Queue message for sending
     */
    private suspend fun queueMessage(message: M2mMessage) {
        queueMutex.withLock {
            if (messageQueue.size >= config.maxQueueSize) {
                // Remove oldest message
                messageQueue.removeAt(0)

                metricsMutex.withLock {
                    metrics.messagesDropped++
                }
            }

            messageQueue.add(message)

            metricsMutex.withLock {
                metrics.messagesSent++
                metrics.queueSize = messageQueue.size
            }
        }

        // Broadcast to local subscribers
        try {
            broadcastChannel.send(message)
        } catch (e: Exception) {
            // No local subscribers
        }
    }

    /**
     * Process incoming message
     */
    suspend fun processMessage(message: M2mMessage): CouchResult<Unit> {
        // Check if message has expired
        message.ttl?.let { ttl ->
            val age = Clock.System.INSTANT - message.timestamp
            if (age >= ttl.seconds) {
                return Result.success(Unit)
            }
        }

        metricsMutex.withLock {
            metrics.messagesReceived++
        }

        // Find and execute handler
        handlersMutex.withLock {
            messageHandlers[message.messageType]?.let { handler ->
                try {
                    val response = handler.handle(message)
                    response?.let {
                        queueMessage(it)
                    }
                } catch (e: Exception) {
                    // Handler error
                }
            }
        }

        return Result.success(Unit)
    }

    /**
     * Add or update peer
     */
    suspend fun addPeer(peer: PeerInfo): CouchResult<Unit> {
        peersMutex.withLock {
            if (peers.size >= config.maxPeers) {
                return Result.failure(CouchError.badRequest("Maximum peers exceeded"))
            }

            peers[peer.id] = peer

            metricsMutex.withLock {
                metrics.activePeers = peers.size
            }
        }

        return Result.success(Unit)
    }

    /**
     * Remove peer
     */
    suspend fun removePeer(peerId: String): Boolean {
        return peersMutex.withLock {
            val removed = peers.remove(peerId) != null

            if (removed) {
                metricsMutex.withLock {
                    metrics.activePeers = peers.size
                }
            }

            removed
        }
    }

    /**
     * Get peer information
     */
    suspend fun getPeer(peerId: String): PeerInfo? {
        return peersMutex.withLock { peers[peerId] }
    }

    /**
     * List all peers
     */
    suspend fun listPeers(): List<PeerInfo> {
        return peersMutex.withLock { peers.values.toList() }
    }

    /**
     * Update peer status
     */
    suspend fun updatePeerStatus(peerId: String, status: PeerStatus) {
        peersMutex.withLock {
            peers[peerId]?.let { peer ->
                peers[peerId] = peer.copy(
                    status = status,
                    lastSeen = Clock.System.INSTANT
                )
            }
        }
    }

    /**
     * Get M2M statistics
     */
    suspend fun getMetrics(): M2mMetrics {
        return metricsMutex.withLock {
            metrics.copy(
                uptimeSeconds = (Clock.System.INSTANT - startTime).inWholeSeconds.toULong()
            )
        }
    }

    /**
     * Subscribe to messages
     */
    fun subscribe() = broadcastChannel.openSubscription()

    /**
     * Get the node ID
     */
    fun getNodeId(): String = nodeId

    /**
     * Start background services
     */
    fun startServices(scope: CoroutineScope): List<Job> {
        val handles = mutableListOf<Job>()

        // Start heartbeat service
        handles.add(startHeartbeatService(scope))

        // Start message processing service
        handles.add(startMessageProcessor(scope))

        // Start peer discovery if enabled
        if (config.discoveryEnabled) {
            handles.add(startPeerDiscovery(scope))
        }

        // Start cleanup service
        handles.add(startCleanupService(scope))

        return handles
    }

    /**
     * Start heartbeat service
     */
    private fun startHeartbeatService(scope: CoroutineScope): Job {
        return scope.launch {
            while (isActive) {
                delay(config.heartbeatIntervalSecs.seconds.inWholeMilliseconds)

                val heartbeat = M2mMessage(
                    id = generateUuid(),
                    sender = nodeId,
                    recipient = null, // Broadcast
                    messageType = M2mMessageType.HeartBeat,
                    payload = """{"timestamp":"${Clock.System.INSTANT}","capabilities":["couchdb","ipfs","tensor"]}""",
                    timestamp = Clock.System.INSTANT,
                    ttl = config.heartbeatIntervalSecs * 3
                )

                try {
                    broadcastChannel.send(heartbeat)
                } catch (e: Exception) {
                    // No subscribers
                }
            }
        }
    }

    /**
     * Start message processor
     */
    private fun startMessageProcessor(scope: CoroutineScope): Job {
        return scope.launch {
            while (isActive) {
                delay(100) // Process every 100ms

                val messagesToSend = queueMutex.withLock {
                    val messages = messageQueue.toList()
                    messageQueue.clear()
                    messages
                }

                for (message in messagesToSend) {
                    // In a real implementation, this would send messages over the network
                    metricsMutex.withLock {
                        metrics.bytesSent += message.payload.length.toULong()
                    }
                }
            }
        }
    }

    /**
     * Start peer discovery service
     */
    private fun startPeerDiscovery(scope: CoroutineScope): Job {
        return scope.launch {
            while (isActive) {
                delay(60_000) // Discover every minute

                // In a real implementation, this would do UDP multicast discovery
                peersMutex.withLock {
                    if (peers.size < 10) {
                        // Simulate discovering a peer
                        val discoveredPeer = PeerInfo(
                            id = "peer-${generateUuid()}",
                            address = "127.0.0.1:${config.discoveryPort}",
                            lastSeen = Clock.System.INSTANT,
                            capabilities = listOf("couchdb"),
                            status = PeerStatus.Connected,
                            latencyMs = 10u,
                            messageCount = 0u
                        )
                        peers[discoveredPeer.id] = discoveredPeer
                    }
                }
            }
        }
    }

    /**
     * Start cleanup service
     */
    private fun startCleanupService(scope: CoroutineScope): Job {
        return scope.launch {
            while (isActive) {
                delay(300_000) // Cleanup every 5 minutes

                val cutoff = Clock.System.INSTANT - 10.minutes
                peersMutex.withLock {
                    val beforeCount = peers.size
                    peers.entries.removeIf { (_, peer) -> peer.lastSeen < cutoff }
                    val afterCount = peers.size

                    if (beforeCount != afterCount) {
                        // Cleaned up stale peers
                    }

                    metricsMutex.withLock {
                        metrics.activePeers = afterCount
                    }
                }
            }
        }
    }
}

/**
 * Peer information
 */
data class PeerInfo(
    val id: String,
    val address: String,
    val lastSeen: Instant,
    val capabilities: List<String> = emptyList(),
    val status: PeerStatus,
    val latencyMs: ULong? = null,
    val messageCount: ULong = 0u
)

/**
 * Peer status
 */
sealed class PeerStatus {
    object Connected : PeerStatus()
    object Disconnected : PeerStatus()
    object Connecting : PeerStatus()
    data class Error(val reason: String) : PeerStatus()
}

/**
 * M2M configuration
 */
data class M2mConfig(
    val heartbeatIntervalSecs: ULong = 30u,
    val messageTtlSecs: ULong = 300u, // 5 minutes
    val maxQueueSize: Int = 1000,
    val maxPeers: Int = 100,
    val discoveryEnabled: Boolean = true,
    val discoveryPort: UInt = 8889u,
    val encryptionEnabled: Boolean = false
) {
    companion object {
        fun default(): M2mConfig = M2mConfig()
    }
}

/**
 * Message handler interface
 */
interface MessageHandler {
    suspend fun handle(message: M2mMessage): M2mMessage?
    fun messageType(): M2mMessageType
}

/**
 * M2M metrics
 */
data class M2mMetrics(
    var messagesSent: ULong = 0u,
    var messagesReceived: ULong = 0u,
    var messagesDropped: ULong = 0u,
    var bytesSent: ULong = 0u,
    var bytesReceived: ULong = 0u,
    var activePeers: Int = 0,
    var queueSize: Int = 0,
    var uptimeSeconds: ULong = 0u
)

/**
 * Default handlers for common message types
 */
class HeartbeatHandler(
    private val nodeId: String
) : MessageHandler {

    override suspend fun handle(message: M2mMessage): M2mMessage? {
        // Respond with our own heartbeat if requested
        val respond = message.payload.contains("\"respond\":true")
        if (respond) {
            return M2mMessage(
                id = generateUuid(),
                sender = nodeId,
                recipient = message.sender,
                messageType = M2mMessageType.HeartBeat,
                payload = """{"timestamp":"${Clock.System.INSTANT}","in_response_to":"${message.id}"}""",
                timestamp = Clock.System.INSTANT,
                ttl = 60u
            )
        }
        return null
    }

    override fun messageType(): M2mMessageType = M2mMessageType.HeartBeat
}

/**
 * Replication message handler
 */
class ReplicationHandler(
    private val nodeId: String
) : MessageHandler {

    override suspend fun handle(message: M2mMessage): M2mMessage? {
        // Extract replication request details
        val dbName = extractJsonString(message.payload, "database")
            ?: return null

        return M2mMessage(
            id = generateUuid(),
            sender = nodeId,
            recipient = message.sender,
            messageType = M2mMessageType.Replication,
            payload = """{"status":"accepted","database":"$dbName","in_response_to":"${message.id}"}""",
            timestamp = Clock.System.INSTANT,
            ttl = 300u
        )
    }

    override fun messageType(): M2mMessageType = M2mMessageType.Replication

    private fun extractJsonString(json: String, key: String): String? {
        val pattern = """"$key"\s*:\s*"([^"]*)"""".toRegex()
        return pattern.find(json)?.groupValues?.get(1)
    }
}

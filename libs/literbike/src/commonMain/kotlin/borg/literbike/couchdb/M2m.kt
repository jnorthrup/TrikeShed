package borg.literbike.couchdb

import kotlinx.coroutines.channels.*
import kotlinx.datetime.Clock

/**
 * M2M (Machine-to-Machine) communication manager
 */
class M2mManager(
    private val nodeId: String?,
    private val config: M2mConfig = M2mConfig.default()
) {
    private val messageChannels: MutableMap<String, Channel<M2mMessage>> = mutableMapOf()
    private val broadcastChannel = Channel<M2mMessage>(config.channelBufferSize)

    companion object {
        fun new(nodeId: String? = null, config: M2mConfig = M2mConfig.default()) = M2mManager(nodeId, config)
    }

    /**
     * Send a message to a specific recipient
     */
    suspend fun sendMessage(message: M2mMessage): CouchResult<Unit> {
        val updatedMessage = message.copy(
            sender = nodeId ?: "unknown",
            timestamp = Clock.System.now()
        )

        message.recipient?.let { recipient ->
            val channel = messageChannels.getOrPut(recipient) { Channel(config.channelBufferSize) }
            channel.send(updatedMessage)
        } ?: run {
            // Broadcast
            broadcastChannel.send(updatedMessage)
        }

        return Result.success(Unit)
    }

    /**
     * Receive messages for a specific recipient
     */
    suspend fun receiveMessages(recipient: String): M2mMessage? {
        val channel = messageChannels[recipient] ?: return null
        return channel.tryReceive().getOrNull()
    }

    /**
     * Receive broadcast messages
     */
    suspend fun receiveBroadcast(): M2mMessage? {
        return broadcastChannel.tryReceive().getOrNull()
    }

    /**
     * Subscribe to a message channel
     */
    fun subscribe(recipient: String): ReceiveChannel<M2mMessage> {
        return messageChannels.getOrPut(recipient) { Channel(config.channelBufferSize) }
    }

    /**
     * Get M2M statistics
     */
    fun getStats(): M2mStats {
        return M2mStats(
            nodeId = nodeId,
            activeChannels = messageChannels.size,
            broadcastBufferSize = broadcastChannel.capacity,
            config = config
        )
    }
}

/**
 * M2M configuration
 */
data class M2mConfig(
    val channelBufferSize: Int = 100,
    val messageTtlMs: ULong = 60000uL,
    val enableBroadcast: Boolean = true,
    val maxMessageSize: ULong = 1024 * 1024uL // 1MB
) {
    companion object {
        fun default() = M2mConfig()
    }
}

/**
 * M2M statistics
 */
data class M2mStats(
    val nodeId: String?,
    val activeChannels: Int,
    val broadcastBufferSize: Int,
    val config: M2mConfig
)

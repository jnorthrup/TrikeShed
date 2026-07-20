package borg.trikeshed.jules.sync

import kotlinx.serialization.Serializable

@Serializable
enum class SyncState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    SYNCING,
    ERROR
}

@Serializable
data class SyncSessionState(
    val status: SyncState = SyncState.DISCONNECTED,
    val localSequenceNumber: Long = 0L,
    val remoteSequenceNumber: Long = 0L,
    val offlineQueue: List<SyncMessage> = emptyList(),
    val unacknowledgedMessages: Map<String, SyncMessage> = emptyMap(),
    val errorDetails: String? = null
)

@Serializable
sealed class SyncEvent {
    @Serializable
    data object Connect : SyncEvent()

    @Serializable
    data object Connected : SyncEvent()

    @Serializable
    data class Disconnect(val reason: String? = null) : SyncEvent()

    @Serializable
    data class EnqueueMessage(val message: SyncMessage) : SyncEvent()

    @Serializable
    data class MessageSent(val message: SyncMessage) : SyncEvent()

    @Serializable
    data class ReceiveAck(val ack: Ack) : SyncEvent()

    @Serializable
    data class ReceiveNack(val nack: Nack) : SyncEvent()

    @Serializable
    data class ReceiveRemoteMessage(val message: SyncMessage) : SyncEvent()

    @Serializable
    data class Error(val details: String) : SyncEvent()
}

object JulesSyncFSM {
    fun reduce(event: SyncEvent, state: SyncSessionState): SyncSessionState {
        return when (event) {
            is SyncEvent.Connect -> state.copy(status = SyncState.CONNECTING, errorDetails = null)
            is SyncEvent.Connected -> state.copy(status = SyncState.CONNECTED)
            is SyncEvent.Disconnect -> state.copy(status = SyncState.DISCONNECTED, errorDetails = event.reason)
            is SyncEvent.EnqueueMessage -> {
                val newMessage = event.message.copy(sequenceNumber = state.localSequenceNumber + 1)
                state.copy(
                    localSequenceNumber = newMessage.sequenceNumber,
                    offlineQueue = state.offlineQueue + newMessage
                )
            }
            is SyncEvent.MessageSent -> {
                state.copy(
                    offlineQueue = state.offlineQueue.filter { it.id != event.message.id },
                    unacknowledgedMessages = state.unacknowledgedMessages + (event.message.id to event.message)
                )
            }
            is SyncEvent.ReceiveAck -> {
                state.copy(
                    unacknowledgedMessages = state.unacknowledgedMessages.filterKeys { it != event.ack.messageId }
                )
            }
            is SyncEvent.ReceiveNack -> {
                val messageToRequeue = state.unacknowledgedMessages[event.nack.messageId]
                if (messageToRequeue != null) {
                    state.copy(
                        unacknowledgedMessages = state.unacknowledgedMessages.filterKeys { it != event.nack.messageId },
                        offlineQueue = listOf(messageToRequeue) + state.offlineQueue // Prioritize requeued message
                    )
                } else {
                    state
                }
            }
            is SyncEvent.ReceiveRemoteMessage -> {
                // Conflict resolution logic (Last-Writer-Wins based on timestamp could be applied at consumer level)
                // For the FSM, we just track the sequence number to avoid replays
                if (event.message.sequenceNumber > state.remoteSequenceNumber) {
                    state.copy(remoteSequenceNumber = event.message.sequenceNumber)
                } else {
                    state // Duplicate or out of order
                }
            }
            is SyncEvent.Error -> state.copy(status = SyncState.ERROR, errorDetails = event.details)
        }
    }
}

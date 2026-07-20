package borg.trikeshed.jules.conductor

import borg.trikeshed.jules.sync.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonElement

class JulesSyncConductor(
    val clientId: String,
    private val sendOutbound: suspend (SyncMessage) -> Unit
) {
    private val _state = MutableStateFlow(SyncSessionState())
    val state: StateFlow<SyncSessionState> = _state.asStateFlow()

    fun connect() {
        dispatch(SyncEvent.Connect)
    }

    fun markConnected() {
        dispatch(SyncEvent.Connected)
    }

    fun disconnect(reason: String? = null) {
        dispatch(SyncEvent.Disconnect(reason))
    }

    suspend fun enqueuePayload(id: String, payload: JsonElement, timestamp: Long) {
        val msg = SyncMessage(
            id = id,
            sequenceNumber = 0, // Assigned by FSM
            clientId = clientId,
            payload = payload,
            timestamp = timestamp
        )
        dispatch(SyncEvent.EnqueueMessage(msg))
        drainQueue()
    }

    suspend fun receiveRemoteMessage(message: SyncMessage, strategy: ResolutionStrategy = ResolutionStrategy.LAST_WRITER_WINS): SyncMessage? {
        // Resolve conflicts if there's a matching message in our unacknowledged or offline queue
        val conflictingOffline = _state.value.offlineQueue.find { it.id == message.id }
        val conflictingUnacked = _state.value.unacknowledgedMessages[message.id]

        val conflicting = conflictingOffline ?: conflictingUnacked

        val finalMessage = if (conflicting != null) {
            ConflictResolver.resolve(conflicting, message, strategy)
        } else {
            message
        }

        dispatch(SyncEvent.ReceiveRemoteMessage(finalMessage))
        return if (finalMessage == message) finalMessage else null // Returns remote message if it won, else null
    }

    suspend fun receiveAck(ack: Ack) {
        dispatch(SyncEvent.ReceiveAck(ack))
    }

    suspend fun receiveNack(nack: Nack) {
        dispatch(SyncEvent.ReceiveNack(nack))
        drainQueue() // Try resending
    }

    private fun dispatch(event: SyncEvent) {
        _state.value = JulesSyncFSM.reduce(event, _state.value)
    }

    suspend fun drainQueue() {
        if (_state.value.status != SyncState.CONNECTED) return

        // Take a snapshot of the queue to send
        val toSend = _state.value.offlineQueue.toList()
        for (message in toSend) {
            try {
                sendOutbound(message)
                dispatch(SyncEvent.MessageSent(message))
            } catch (e: Exception) {
                dispatch(SyncEvent.Error(e.message ?: "Unknown error sending message"))
                break // Stop draining on error
            }
        }
    }
}

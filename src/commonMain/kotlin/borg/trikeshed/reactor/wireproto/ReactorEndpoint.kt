package borg.trikeshed.reactor.wireproto

import borg.trikeshed.lcnc.reactor.ReactorAction
import borg.trikeshed.isam.FieldSynapse
import borg.trikeshed.cursor.Cursor
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.j

/**
 * Interface representing an endpoint that can transport a ReactorAction.
 * The implementation must carry the action over wireproto, possibly transporting
 * associated data (like path/cursor).
 */
interface ReactorEndpoint {
    /**
     * Send an action. If the action is a PublishEntity, the cursor is
     * associated with the path payload.
     */
    suspend fun sendAction(action: ReactorAction, pathCursor: Cursor? = null)

    /**
     * Receive the next action and its associated path cursor (if applicable).
     */
    suspend fun receiveAction(): Pair<ReactorAction, Cursor?>
}

/**
 * Simple in-memory loopback implementation of ReactorEndpoint.
 * Used to demonstrate "Path/cursor transport over ReactorEndpoint" requirement.
 */
class LoopbackReactorEndpoint : ReactorEndpoint {
    private val buffer = kotlinx.coroutines.channels.Channel<Pair<FieldSynapse, Cursor?>>(100)

    // We store the side-band payloads here in this loopback to complete the roundtrip
    // of PublishEntity in memory since Wireproto (FieldSynapse) only encodes metadata.
    private val entityPayloads = mutableMapOf<Long, borg.trikeshed.lcnc.isam.LcncEntity>()

    override suspend fun sendAction(action: ReactorAction, pathCursor: Cursor?) {
        val synapse = action.toFieldSynapse()

        // For PublishEntity, store the payload using the synapse sequence ID (or generated ID)
        val payloadAssocSynapse = if (action is ReactorAction.PublishEntity) {
            val id = kotlinx.datetime.Clock.System.now().toEpochMilliseconds()
            entityPayloads[id] = action.entity
            synapse.copy(seq = id)
        } else {
            synapse
        }

        buffer.send(Pair(payloadAssocSynapse, pathCursor))
    }

    override suspend fun receiveAction(): Pair<ReactorAction, Cursor?> {
        val (synapse, cursor) = buffer.receive()

        val entityPayload = if (synapse.opcode == OPCODE_PUBLISH_ENTITY) {
            entityPayloads.remove(synapse.seq)
        } else {
            null
        }

        val action = synapse.toReactorAction(entityPayload)
        return Pair(action, cursor)
    }
}

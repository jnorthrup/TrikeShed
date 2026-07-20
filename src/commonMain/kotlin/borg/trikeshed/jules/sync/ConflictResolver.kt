package borg.trikeshed.jules.sync

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

enum class ResolutionStrategy {
    LAST_WRITER_WINS,
    MERGE
}

object ConflictResolver {
    fun resolve(
        local: SyncMessage,
        remote: SyncMessage,
        strategy: ResolutionStrategy = ResolutionStrategy.LAST_WRITER_WINS
    ): SyncMessage {
        return when (strategy) {
            ResolutionStrategy.LAST_WRITER_WINS -> resolveLastWriterWins(local, remote)
            ResolutionStrategy.MERGE -> resolveMerge(local, remote)
        }
    }

    private fun resolveLastWriterWins(local: SyncMessage, remote: SyncMessage): SyncMessage {
        return if (local.timestamp > remote.timestamp) {
            local
        } else if (remote.timestamp > local.timestamp) {
            remote
        } else {
            // Break ties using client ID lexicographical order
            if (local.clientId > remote.clientId) local else remote
        }
    }

    private fun resolveMerge(local: SyncMessage, remote: SyncMessage): SyncMessage {
        val baseMessage = resolveLastWriterWins(local, remote)
        
        // Very basic merge if both payloads are JSON Objects
        if (local.payload is JsonObject && remote.payload is JsonObject) {
            val mergedPayload = mutableMapOf<String, JsonElement>()
            mergedPayload.putAll(local.payload)
            
            for ((key, value) in remote.payload) {
                if (mergedPayload.containsKey(key)) {
                    // For conflicting keys, use last writer wins logic based on message timestamp
                    if (remote.timestamp > local.timestamp || (remote.timestamp == local.timestamp && remote.clientId > local.clientId)) {
                        mergedPayload[key] = value
                    }
                } else {
                    mergedPayload[key] = value
                }
            }
            return baseMessage.copy(payload = JsonObject(mergedPayload))
        }
        
        return baseMessage
    }
}

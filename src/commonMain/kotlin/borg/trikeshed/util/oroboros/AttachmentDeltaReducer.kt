package borg.trikeshed.util.oroboros

enum class AttachmentEventType {
    CREATE, MODIFY, DELETE, OVERFLOW
}

data class AttachmentEvent(
    val path: String,
    val type: AttachmentEventType,
    val digest: String? = null
)

enum class AttachmentAction {
    CREATE, REPLACE, DELETE
}

data class AttachmentBatch(
    val actions: Map<String, AttachmentAction>,
    val needsReconciliation: Boolean
)

class AttachmentDeltaReducer(
    private val knownDigests: Map<String, String> = emptyMap()
) {
    private val pendingEvents = mutableMapOf<String, AttachmentEvent>()
    private var overflowReconciliationRequired = false

    fun consume(events: List<AttachmentEvent>) {
        for (event in events) {
            if (event.type == AttachmentEventType.OVERFLOW) {
                pendingEvents.clear()
                overflowReconciliationRequired = true
                continue
            }
            
            val normalizedPath = event.path.replace("\\", "/")
            val existingEvent = pendingEvents[normalizedPath]
            
            val newType = when (event.type) {
                AttachmentEventType.CREATE -> {
                    AttachmentEventType.CREATE
                }
                AttachmentEventType.MODIFY -> {
                    if (existingEvent?.type == AttachmentEventType.CREATE) {
                        AttachmentEventType.CREATE
                    } else {
                        AttachmentEventType.MODIFY
                    }
                }
                AttachmentEventType.DELETE -> {
                    if (existingEvent?.type == AttachmentEventType.CREATE) {
                        if (!knownDigests.containsKey(normalizedPath)) {
                            pendingEvents.remove(normalizedPath)
                            continue
                        }
                    }
                    AttachmentEventType.DELETE
                }
                AttachmentEventType.OVERFLOW -> continue
            }
            
            pendingEvents[normalizedPath] = AttachmentEvent(normalizedPath, newType, event.digest)
        }
    }

    fun takeBatch(batchSize: Int): AttachmentBatch {
        val sortedKeys = pendingEvents.keys.sorted().take(batchSize)
        val actions = mutableMapOf<String, AttachmentAction>()
        
        for (key in sortedKeys) {
            val event = pendingEvents.remove(key)!!
            
            when (event.type) {
                AttachmentEventType.CREATE -> {
                    actions[key] = AttachmentAction.CREATE
                }
                AttachmentEventType.MODIFY -> {
                    val knownDigest = knownDigests[key]
                    if (event.digest == null || knownDigest != event.digest) {
                        actions[key] = AttachmentAction.REPLACE
                    }
                }
                AttachmentEventType.DELETE -> {
                    if (knownDigests.containsKey(key)) {
                        actions[key] = AttachmentAction.DELETE
                    }
                }
                AttachmentEventType.OVERFLOW -> {}
            }
        }
        
        val needsRec = overflowReconciliationRequired
        if (needsRec) {
            overflowReconciliationRequired = false
        }
        
        return AttachmentBatch(actions, needsRec)
    }
}

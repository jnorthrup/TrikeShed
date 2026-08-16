package modelmux

import borg.trikeshed.cursor.Cursor
import borg.trikeshed.cursor.ColumnMeta
import borg.trikeshed.lib.size

enum class ExhaustionSignal {
    HTTP_429,
    QUOTA_EXHAUSTED
}

data class FallbackRequestDescriptor(
    val modelId: String,
    val leaseId: String,
    val retryAttempt: Int
)

object AutoFallbackRule {
    const val MAX_RETRIES = 3

    /**
     * Evaluates a fallback strategy based on the given signal and candidates cursor projection.
     */
    fun evaluate(
        signal: ExhaustionSignal,
        candidatesProjection: Cursor,
        leaseId: String,
        currentAttempt: Int,
        lastAttemptedModelId: String?
    ): FallbackRequestDescriptor? {
        if (currentAttempt >= MAX_RETRIES) return null

        var modelIdCol = -1
        
        if (candidatesProjection.size > 0) {
            val row0 = candidatesProjection.b(0)
            val len = row0.a
            for (c in 0 until len) {
                val meta = row0.b(c).b()
                if (meta.name == "modelId" || meta.name == "id") modelIdCol = c
            }
        }
        
        if (modelIdCol == -1) return null
        
        var foundLast = lastAttemptedModelId == null
        
        for (i in 0 until candidatesProjection.size) {
            val row = candidatesProjection.b(i)
            val modelId = row.b(modelIdCol).a as String
            
            if (foundLast) {
                return FallbackRequestDescriptor(
                    modelId = modelId,
                    leaseId = leaseId,
                    retryAttempt = currentAttempt + 1
                )
            }
            
            if (modelId == lastAttemptedModelId) {
                foundLast = true
            }
        }
        
        return null
    }
}

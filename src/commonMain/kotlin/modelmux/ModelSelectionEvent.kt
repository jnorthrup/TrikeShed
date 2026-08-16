package modelmux

import kotlinx.serialization.Serializable
import borg.trikeshed.parse.json.JsonSupport

/**
 * Event vocabulary for observer/pointcut hooks into modelmux logic.
 */
sealed class ModelSelectionEvent {
    
    /**
     * Emitted at selection time when modelmux finally chooses a provider/model.
     * Serialized to the observer surface as a single JSON line on the forge event stream.
     */
    @Serializable
    data class ModelSelected(
        val provider: String,
        val model: String,
        val strategy: String,
        val requestId: String,
        val at: Long
    ) : ModelSelectionEvent() {
        
        /**
         * Serializes this event to a single JSON line without parsing reactor internals.
         */
        fun toJsonLine(): String {
            val map = mapOf(
                "type" to "ModelSelected",
                "provider" to provider,
                "model" to model,
                "strategy" to strategy,
                "requestId" to requestId,
                "at" to at
            )
            return JsonSupport.stringify(map)
        }
    }
}

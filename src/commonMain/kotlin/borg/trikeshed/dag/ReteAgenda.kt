package borg.trikeshed.dag

import borg.trikeshed.collections.LinearHashMap
import borg.trikeshed.job.ContentId

data class Activation(
    val activationId: String,
    val ruleId: String,
    val ruleVersionCid: ContentId,
    val salience: Int,
    val sequence: Long,
    val supportCids: List<ContentId>,
    val bindings: Map<String, String>,
)

/**
 * Deterministic Rete conflict set. Ordering is salience descending, committed
 * sequence ascending, then activation ID ascending.
 */
class ReteAgenda {
    private val pending = LinearHashMap<String, Activation>()

    val size: Int get() = pending.count

    fun add(activation: Activation): Boolean {
        val existing = pending.get(activation.activationId)
        if (existing != null) {
            require(existing == activation) {
                "activation ID ${activation.activationId} already identifies different content"
            }
            return false
        }
        pending.put(activation.activationId, activation)
        return true
    }

    fun popNext(): Activation? {
        var selected: Activation? = null
        pending.entries().forEach { (_, candidate) ->
            val current = selected
            if (current == null || candidate.precedes(current)) selected = candidate
        }
        return selected?.also { pending.remove(it.activationId) }
    }

    fun removeBySupport(supportCid: ContentId): Int {
        val invalidated = pending.entries()
            .filter { (_, activation) -> supportCid in activation.supportCids }
            .map { it.first }
        invalidated.forEach(pending::remove)
        return invalidated.size
    }

    private fun Activation.precedes(other: Activation): Boolean = when {
        salience != other.salience -> salience > other.salience
        sequence != other.sequence -> sequence < other.sequence
        else -> activationId < other.activationId
    }
}

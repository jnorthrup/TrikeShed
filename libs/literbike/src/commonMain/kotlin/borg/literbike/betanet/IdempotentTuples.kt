package borg.literbike.betanet

/**
 * Idempotent Tuple Operations - In-place updates without versioning.
 * Ported from literbike/src/betanet/idempotent_tuples.rs.
 */

/**
 * Idempotent tuple trait - can be updated in-place safely.
 */
interface IdempotentTuple {
    /** Get unique tuple ID for deduplication */
    fun tupleId(): Long = this.hashCode().toLong()

    /** Check if this tuple supersedes another (for conflict resolution) */
    fun supersedes(other: IdempotentTuple): Boolean = this != other
}

/**
 * Example idempotent tuple types.
 */
data class MetricTuple(
    val timestamp: Long,
    val metricName: String,
    val value: Double,
    val tags: List<String>
) : IdempotentTuple {
    override fun supersedes(other: IdempotentTuple): Boolean {
        if (other !is MetricTuple) return false
        return metricName == other.metricName && timestamp > other.timestamp
    }
}

data class ConfigTuple(
    val key: String,
    val value: String,
    val version: Int
) : IdempotentTuple {
    override fun supersedes(other: IdempotentTuple): Boolean {
        if (other !is ConfigTuple) return false
        return this.key == other.key && this.version > other.version
    }
}

/**
 * Batch operations for efficient bulk updates.
 */
class TupleBatch<T : IdempotentTuple> {
    private val tuples = mutableListOf<T>()

    fun add(tuple: T) {
        tuples.add(tuple)
    }

    fun applyTo(store: MutableList<T>) {
        for (tuple in tuples) {
            val existingIndex = store.indexOfFirst { it.tupleId() == tuple.tupleId() }
            if (existingIndex >= 0) {
                if (tuple.supersedes(store[existingIndex])) {
                    store[existingIndex] = tuple
                }
            } else {
                store.add(tuple)
            }
        }
    }

    /** Deduplicate batch */
    fun dedupe() {
        tuples.sortBy { it.tupleId() }
        val seen = mutableSetOf<Long>()
        tuples.removeAll { !seen.add(it.tupleId()) }
    }

    fun size(): Int = tuples.size
}

/**
 * Conflict-free replicated data type (CRDT) support.
 */
interface CRDTTuple : IdempotentTuple {
    /** Merge two tuples (commutative and associative) */
    fun merge(other: IdempotentTuple): IdempotentTuple

    /** Check if merge is needed */
    fun conflictsWith(other: IdempotentTuple): Boolean = this != other
}

// ConfigTuple CRDT implementation
fun ConfigTuple.mergeWith(other: ConfigTuple): ConfigTuple {
    return if (this.version >= other.version) this else other
}

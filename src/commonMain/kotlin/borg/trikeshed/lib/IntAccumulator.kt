package borg.trikeshed.lib

/** Off-heap growable int list — CCEK via [Key]. @deprecated Prefer context resolution. */
@Deprecated("Resolve via IntAccumulator.Key in coroutine context")
expect class IntAccumulator(initialCapacity: Int = 8) : kotlin.coroutines.CoroutineContext.Element {
    override val key: kotlin.coroutines.CoroutineContext.Key<*>
    companion object Key : kotlin.coroutines.CoroutineContext.Key<IntAccumulator>
    fun add(value: Int)
    val size: Int
    fun toIntArray(): IntArray
    fun close()
}

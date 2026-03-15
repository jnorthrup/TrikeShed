package borg.trikeshed.lib

/** Off-heap growable int list. 10% platform surface — see jvmMain/posixMain actuals. */
expect class IntAccumulator(initialCapacity: Int = 8) {
    fun add(value: Int)
    val size: Int
    fun toIntArray(): IntArray
    fun close()
}

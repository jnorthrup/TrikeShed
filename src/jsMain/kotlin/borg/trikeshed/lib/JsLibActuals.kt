package borg.trikeshed.lib

actual fun assert(value: Boolean) {
    if (!value) throw AssertionError("Assertion failed")
}

actual fun assert(value: Boolean, lazyMessage: () -> Any) {
    if (!value) throw AssertionError(lazyMessage().toString())
}

actual class IntAccumulator actual constructor(initialCapacity: Int) {
    private val values = ArrayList<Int>(initialCapacity.coerceAtLeast(0))

    actual fun add(value: Int) {
        values.add(value)
    }

    actual val size: Int
        get() = values.size

    actual fun toIntArray(): IntArray = values.toIntArray()

    actual fun close() {
        // no-op on JS
    }
}

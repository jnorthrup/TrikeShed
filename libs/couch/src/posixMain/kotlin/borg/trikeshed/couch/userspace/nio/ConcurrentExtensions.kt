package borg.trikeshed.couch.userspace.nio

import kotlin.concurrent.AtomicReference

actual class AtomicStateReference<T> actual constructor(initialValue: T) {
    private val ref = AtomicReference(initialValue)

    actual var value: T
        get() = ref.value
        set(v) { ref.value = v }

    actual fun compareAndSet(expect: T, update: T): Boolean = ref.compareAndSet(expect, update)
}

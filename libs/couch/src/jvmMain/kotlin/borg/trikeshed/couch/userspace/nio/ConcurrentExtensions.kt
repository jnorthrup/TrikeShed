package borg.trikeshed.couch.userspace.nio

import java.util.concurrent.atomic.AtomicReference

actual class AtomicStateReference<T> actual constructor(initialValue: T) {
    private val ref = AtomicReference(initialValue)

    actual var value: T
        get() = ref.get()
        set(v) { ref.set(v) }

    actual fun compareAndSet(expect: T, update: T): Boolean = ref.compareAndSet(expect, update)
}

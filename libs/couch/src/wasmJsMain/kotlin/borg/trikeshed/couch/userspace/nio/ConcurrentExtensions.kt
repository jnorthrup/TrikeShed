package borg.trikeshed.couch.userspace.nio

actual class AtomicStateReference<T> actual constructor(initialValue: T) {
    private var _value: T = initialValue

    actual var value: T
        get() = _value
        set(v) { _value = v }

    actual fun compareAndSet(expect: T, update: T): Boolean {
        if (_value == expect) {
            _value = update
            return true
        }
        return false
    }
}

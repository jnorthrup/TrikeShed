package borg.trikeshed.couch.userspace.nio

expect class AtomicStateReference<T>(initialValue: T) {
    var value: T
    fun compareAndSet(expect: T, update: T): Boolean
}

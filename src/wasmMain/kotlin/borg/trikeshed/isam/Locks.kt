package borg.trikeshed.isam

actual inline fun <R> synchronizedLock(lock: Any, block: () -> R): R {
    return block()
}

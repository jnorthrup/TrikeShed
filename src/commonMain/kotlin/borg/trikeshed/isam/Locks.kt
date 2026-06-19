package borg.trikeshed.isam

expect inline fun <R> synchronizedLock(lock: Any, block: () -> R): R

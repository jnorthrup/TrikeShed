package borg.trikeshed.context

import kotlinx.atomicfu.locks.*

/** State ownership is chosen by the caller, independently of the host's CPU count. */
enum class Synchronization {
    /** The owner serializes every access, including close; no state lock is allocated. */
    NONE,
    /** State access may overlap between callers. */
    MONITOR;

    fun lock(): SynchronizedObject? = if (this == MONITOR) SynchronizedObject() else null
}

/** Inlined so owner-serialized state has neither a monitor nor a callback allocation. */
inline fun <R> synchronize(lock: SynchronizedObject?, block: () -> R): R =
    if (lock == null) block() else synchronized(lock, block)

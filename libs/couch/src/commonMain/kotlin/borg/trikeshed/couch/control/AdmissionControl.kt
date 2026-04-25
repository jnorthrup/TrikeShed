package borg.trikeshed.couch.control

/**
 * Platform-neutral AdmissionControl declaration for multiplatform — implemented per-platform.
 */
expect class AdmissionControl(capacity: Int) {
    fun tryAcquire(): Boolean
    fun release()
    fun seal()
    fun close()
}


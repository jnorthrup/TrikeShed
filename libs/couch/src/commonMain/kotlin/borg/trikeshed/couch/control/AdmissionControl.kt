package borg.trikeshed.couch.control

/**
 * Admission control for limiting concurrent operations.
 * On JVM uses atomic primitives; on other targets uses a simple counter.
 */
class AdmissionControl(val capacity: Int) {
   var permits = capacity
   var sealed = false

    fun tryAcquire(): Boolean =
        if (sealed || permits <= 0) false else { permits--; true }

    fun release() {
        if (!sealed && permits < capacity) permits++
    }

    fun seal() { sealed = true }
    fun close() { sealed = true; permits = 0 }
}


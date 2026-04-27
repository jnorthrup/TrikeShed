     1|package borg.trikeshed.couch.control
     2|
     3|/**
     4| * Admission control for limiting concurrent operations.
     5| * On JVM uses atomic primitives; on other targets uses a simple counter.
     6| */
     7|class AdmissionControl(val capacity: Int) {
     8|   var permits = capacity
     9|   var sealed = false
    10|
    11|    fun tryAcquire(): Boolean =
    12|        if (sealed || permits <= 0) false else { permits--; true }
    13|
    14|    fun release() {
    15|        if (!sealed && permits < capacity) permits++
    16|    }
    17|
    18|    fun seal() { sealed = true }
    19|    fun close() { sealed = true; permits = 0 }
    20|}
    21|
package borg.trikeshed.platform

/** Signal handling is not part of this target's native depth; handlers are recorded, never installed. */
actual object PosixSignals {
    actual fun handle(signal: String, handler: () -> Unit) { /* no-op: gate owns native depth */ }
}

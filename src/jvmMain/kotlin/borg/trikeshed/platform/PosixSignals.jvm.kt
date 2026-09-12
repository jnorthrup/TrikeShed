package borg.trikeshed.platform

/**
 * JVM actual of [PosixSignals] — sun.misc.Signal IS the JDK's own POSIX signal
 * mechanism, so an actual is the one legal place to name it (the SPI boundary
 * rule: java.* lives in actuals, never in commonMain or service code).
 */
actual object PosixSignals {
    actual fun handle(signal: String, handler: () -> Unit) {
        val sig = sun.misc.Signal(signal)
        sun.misc.Signal.handle(sig) { handler() }
    }
}

package borg.trikeshed.userspace.nio.process

// Process spawning requires a host runtime (JVM or native). Kotlin/JS
// (browser or Node) has no portable process-spawning primitive.
actual object ProcessWorkerFactory {
    actual fun create(capability: ProcessCapability): ProcessWorker =
        throw UnsupportedOperationException(
            "Process spawning requires a host runtime (JVM or native); not available on Kotlin/JS"
        )
}

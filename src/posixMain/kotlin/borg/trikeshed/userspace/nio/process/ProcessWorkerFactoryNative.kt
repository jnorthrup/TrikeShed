package borg.trikeshed.userspace.nio.process

actual object ProcessWorkerFactory {
    actual fun create(capability: ProcessCapability): ProcessWorker {
        return ProcessWorkerNative(capability)
    }
}

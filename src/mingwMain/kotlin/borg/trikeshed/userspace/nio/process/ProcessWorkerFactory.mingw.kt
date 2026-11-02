package borg.trikeshed.userspace.nio.process

/** ProcessWorkerNative spawns through posix_spawn; Windows needs CreateProcess, which is not bound yet. */
actual object ProcessWorkerFactory {
    actual fun create(capability: ProcessCapability): ProcessWorker =
        throw UnsupportedOperationException("Process spawning on Windows needs a CreateProcess binding; not bound yet")
}

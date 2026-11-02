package borg.trikeshed.userspace.nio.process

/** Windows workers spawn through CreateProcessA (WindowsSpawn.kt). */
actual object ProcessWorkerFactory {
    actual fun create(capability: ProcessCapability): ProcessWorker = MingwProcessWorker(capability)
}

package borg.trikeshed.userspace.nio.process

expect object ProcessWorkerFactory {
    fun create(capability: ProcessCapability): ProcessWorker
}

package borg.trikeshed.userspace.nio.process

interface ProcessWorker {
    suspend fun spawn(spec: ProcessSpec): ProcessResult
}

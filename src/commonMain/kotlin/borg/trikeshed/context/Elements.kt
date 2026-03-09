package borg.trikeshed.context

import kotlin.coroutines.CoroutineContext

// === Execution Control Elements ===

data class ExecutionId(val id: String) : CoroutineContext.Element {
    override val key = Key
    companion object Key : CoroutineContext.Key<ExecutionId>
}

data class ExecutionPhase(val phase: String) : CoroutineContext.Element {
    override val key = Key
    companion object Key : CoroutineContext.Key<ExecutionPhase>
}

// === I/O & Protocol Elements ===

enum class IoCapability { URING, KQUEUE, NIO, EPOLL, POSIX_FD }
data class IoPreference(val capability: IoCapability) : CoroutineContext.Element {
    override val key = Key
    companion object Key : CoroutineContext.Key<IoPreference>
}

// === Easy-Access Extensions ===

val CoroutineContext.executionId: String? get() = this[ExecutionId]?.id
val CoroutineContext.phase: String? get() = this[ExecutionPhase]?.phase
val CoroutineContext.ioCapability: IoCapability? get() = this[IoPreference]?.capability

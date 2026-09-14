package borg.trikeshed.util.oroboros

import borg.trikeshed.context.AsyncContextElement
import borg.trikeshed.context.AsyncContextKey
import kotlinx.coroutines.channels.ReceiveChannel
import kotlin.coroutines.CoroutineContext

actual val DEFAULT_WALKER_BLOCKED_SEGMENTS: Set<String> = setOf(".git")

actual class FileWatchReactorElement actual constructor(
    root: String,
    parentJob: kotlinx.coroutines.Job?,
    capacity: Int,
    includeGlobs: List<String>,
    excludeGlobs: List<String>,
    walkerBlockedSegments: Set<String>,
    walkerBlockedRelativePrefixes: Set<String>,
) : AsyncContextElement(parentJob = TODO("FileWatchReactorElement: no watching mechanism at this target's gate level")) {
    actual companion object Key : AsyncContextKey<FileWatchReactorElement>()
    actual override val key: CoroutineContext.Key<*> get() = Key
    actual val events: ReceiveChannel<FileEvent>
        get() = TODO("FileWatchReactorElement: no watching mechanism at this target's gate level")
    actual override suspend fun open(): Unit = TODO("FileWatchReactorElement: no watching mechanism at this target's gate level")
    actual override suspend fun drain(): Unit = TODO("FileWatchReactorElement: no watching mechanism at this target's gate level")
    actual override suspend fun close(): Unit = TODO("FileWatchReactorElement: no watching mechanism at this target's gate level")
}

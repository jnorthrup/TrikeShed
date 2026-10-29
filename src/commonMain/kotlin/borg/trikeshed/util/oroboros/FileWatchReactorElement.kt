package borg.trikeshed.util.oroboros

import borg.trikeshed.context.Drain
import kotlinx.coroutines.channels.ReceiveChannel
import kotlin.coroutines.CoroutineContext

/**
 * Common file-event reactor: watch a root, forward include-glob events, drop
 * exclude-glob noise. The contract is platform-neutral; the watching mechanism
 * is the platform actual's (JVM WatchService today, inotify/uring POLL at
 * deeper gate levels). Defaults mirror the git-reactor shape.
 */
expect class FileWatchReactorElement(
    root: String,
    parentJob: kotlinx.coroutines.Job?,
    capacity: Int,
    includeGlobs: List<String>,
    excludeGlobs: List<String>,
    walkerBlockedSegments: Set<String>,
    walkerBlockedRelativePrefixes: Set<String>,
) : CoroutineContext.Element, Drain {
    companion object Key : CoroutineContext.Key<FileWatchReactorElement>

    val events: ReceiveChannel<FileEvent>
    override val key: CoroutineContext.Key<*>

    suspend fun open()
    override suspend fun drain()
    suspend fun close()
}

/** Segment names pruned during the initial walk (shared default across targets). */
expect val DEFAULT_WALKER_BLOCKED_SEGMENTS: Set<String>

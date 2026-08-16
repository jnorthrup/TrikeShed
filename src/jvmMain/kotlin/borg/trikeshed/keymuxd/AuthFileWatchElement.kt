package borg.trikeshed.keymuxd

import borg.trikeshed.context.AsyncContextElement
import borg.trikeshed.context.AsyncContextKey
import borg.trikeshed.context.ElementState
import borg.trikeshed.job.ContentId
import borg.trikeshed.userspace.nio.file.spi.FileOperations
import borg.trikeshed.util.oroboros.FileEvent
import borg.trikeshed.util.oroboros.FileEventType
import borg.trikeshed.util.oroboros.JvmFileWatchReactorElement
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext

data class KeysRefreshed(val source: String, val contentId: ContentId)

class AuthFileWatchElement(
    private val authFilePath: String,
    parentJob: Job? = null,
    private val fileOps: FileOperations? = null
) : AsyncContextElement(ElementState.CREATED, parentJob) {

    companion object Key : AsyncContextKey<AuthFileWatchElement>()
    override val key: CoroutineContext.Key<*> = Key

    private val eventChannel = Channel<KeysRefreshed>(Channel.BUFFERED)
    val events: ReceiveChannel<KeysRefreshed> get() = eventChannel

    private var watchElement: JvmFileWatchReactorElement? = null
    private var watchJob: Job? = null

    override suspend fun open() {
        if (state != ElementState.CREATED) return
        super.open()
        
        val actualFileOps = fileOps
            ?: (coroutineContext[FileOperations.Key]
                ?: error("No FileOperations found in context or passed explicitly"))

        if (!actualFileOps.exists(authFilePath)) {
            actualFileOps.write(authFilePath, "{}".toByteArray())
        }

        // Convert authFilePath to an absolute Unix-style path for matching
        val authFileAbsStr = actualFileOps.resolvePath(authFilePath).replace('\\', '/')
        
        // Find parent directory to watch. We will watch the parent dir and filter manually.
        val lastSlash = authFileAbsStr.lastIndexOf('/')
        val watchDir = when {
            lastSlash == -1 -> "."
            lastSlash == 0 -> "/"
            else -> authFileAbsStr.substring(0, lastSlash)
        }
        val watchFileName = if (lastSlash == -1) authFileAbsStr else authFileAbsStr.substring(lastSlash + 1)
        
        // Use JvmFileWatchReactorElement to watch the directory containing the file
        val element = JvmFileWatchReactorElement(
            root = watchDir,
            parentJob = supervisor,
            includeGlobs = listOf("**"),
            excludeGlobs = emptyList()
        )
        watchElement = element
        element.open()

        watchJob = CoroutineScope(supervisor + Dispatchers.Default).launch {
            for (event in element.events) {
                // The event.path is relative to watchDir and uses '/'
                if (event.path == watchFileName || event.path.endsWith("/" + watchFileName)) {
                    if (event.type == FileEventType.CREATE || event.type == FileEventType.MODIFY) {
                        try {
                            val contentBytes = actualFileOps.readAllBytes(authFileAbsStr)
                            val contentId = ContentId.of(contentBytes)
                            eventChannel.send(KeysRefreshed(authFilePath, contentId))
                        } catch (e: Exception) {
                            // Ignore read errors (e.g. file deleted concurrently)
                        }
                    }
                }
            }
        }
        
        state = ElementState.ACTIVE
    }

    override suspend fun drain() {
        if (state.isLessThan(ElementState.OPEN) || state.isAtLeast(ElementState.CLOSED)) return
        state = ElementState.DRAINING
        watchJob?.cancelAndJoin()
        watchElement?.drain()
        eventChannel.close()
        super.close()
    }

    override suspend fun close() = drain()
}

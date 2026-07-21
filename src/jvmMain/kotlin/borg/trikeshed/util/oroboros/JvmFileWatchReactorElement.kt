package borg.trikeshed.util.oroboros

import borg.trikeshed.context.AsyncContextElement
import borg.trikeshed.context.AsyncContextKey
import borg.trikeshed.context.ElementState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.launch
import java.nio.file.ClosedWatchServiceException
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardWatchEventKinds
import java.nio.file.WatchKey
import java.nio.file.WatchService
import kotlin.coroutines.CoroutineContext

/** JVM bind for the Oroboros file-event reactor. WatchService blocks only on Dispatchers.IO. */
class JvmFileWatchReactorElement(
    root: String,
    parentJob: Job? = null,
    capacity: Int = 64,
    private val ignoredSegments: Set<String> = setOf(".git", ".gradle", ".idea", "build", "node_modules"),
) : AsyncContextElement(ElementState.CREATED, parentJob) {
    companion object Key : AsyncContextKey<JvmFileWatchReactorElement>()
    override val key: CoroutineContext.Key<*> = Key

    private val rootPath = Path.of(root).toAbsolutePath().normalize()
    private val eventChannel = Channel<FileEvent>(capacity)
    private val directories = mutableMapOf<WatchKey, Path>()
    private var watchService: WatchService? = null
    private var watchJob: Job? = null

    val events: ReceiveChannel<FileEvent> get() = eventChannel

    override suspend fun open() {
        if (state != ElementState.CREATED) return
        require(Files.isDirectory(rootPath)) { "Watch root is not a directory: $rootPath" }
        super.open()
        val service = FileSystems.getDefault().newWatchService()
        watchService = service
        registerTree(rootPath, service)
        state = ElementState.ACTIVE
        watchJob = CoroutineScope(supervisor + Dispatchers.IO).launch {
            watchLoop(service)
        }
    }

    override suspend fun drain() {
        if (state.isLessThan(ElementState.OPEN) || state.isAtLeast(ElementState.CLOSED)) return
        state = ElementState.DRAINING
        watchService?.close()
        watchJob?.cancelAndJoin()
        eventChannel.close()
        super.close()
    }

    override suspend fun close() = drain()

    private suspend fun watchLoop(service: WatchService) {
        try {
            while (true) {
                val watchKey = service.take()
                val directory = directories[watchKey]
                if (directory != null) {
                    for (rawEvent in watchKey.pollEvents()) {
                        if (rawEvent.kind() == StandardWatchEventKinds.OVERFLOW) continue
                        @Suppress("UNCHECKED_CAST")
                        val relative = (rawEvent.context() as? Path) ?: continue
                        val path = directory.resolve(relative).normalize()
                        if (isIgnored(path)) continue
                        if (rawEvent.kind() == StandardWatchEventKinds.ENTRY_CREATE && Files.isDirectory(path)) {
                            registerTree(path, service)
                        }
                        val type = when (rawEvent.kind()) {
                            StandardWatchEventKinds.ENTRY_CREATE -> FileEventType.CREATE
                            StandardWatchEventKinds.ENTRY_DELETE -> FileEventType.DELETE
                            else -> FileEventType.MODIFY
                        }
                        eventChannel.send(FileEvent(rootPath.relativize(path).toString().replace('\\', '/'), type))
                    }
                }
                if (!watchKey.reset()) directories.remove(watchKey)
            }
        } catch (_: ClosedWatchServiceException) {
            // Normal drain path.
        } finally {
            eventChannel.close()
        }
    }

    private fun registerTree(start: Path, service: WatchService) {
        Files.walk(start).use { paths ->
            paths.filter { Files.isDirectory(it) && !isIgnored(it) }.forEach { directory ->
                val key = directory.register(
                    service,
                    StandardWatchEventKinds.ENTRY_CREATE,
                    StandardWatchEventKinds.ENTRY_MODIFY,
                    StandardWatchEventKinds.ENTRY_DELETE,
                )
                directories[key] = directory
            }
        }
    }

    private fun isIgnored(path: Path): Boolean {
        val relative = if (path.startsWith(rootPath)) rootPath.relativize(path) else path
        return relative.any { it.toString() in ignoredSegments }
    }
}

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
    /**
     * Inclusive globs (relative to [root]) whose events are forwarded.
     *
     * Default: `.git` plus everything under it — mirrors everything under the embedded git DB
     * (loose objects, packs, refs).
     */
    includeGlobs: List<String> = listOf(".git/**"),
    /**
     * Exclusive globs (relative to [root]) whose events are dropped IF they also
     * pass an include glob.
     *
     * Default: every `.kt` path — drops Kotlin source ("the code checked in").
     */
    excludeGlobs: List<String> = listOf("**" + "/" + "*.kt"),
) : AsyncContextElement(ElementState.CREATED, parentJob) {
    companion object Key : AsyncContextKey<JvmFileWatchReactorElement>()
    override val key: CoroutineContext.Key<*> = Key

    private val rootPath = Path.of(root).toAbsolutePath().normalize()
    private val eventChannel = Channel<FileEvent>(capacity)
    private val directories = mutableMapOf<WatchKey, Path>()
    private var watchService: WatchService? = null
    private var watchJob: Job? = null

    private val glob: PathGlob = PathGlob(includeGlobs, excludeGlobs)

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
                        val relStr = rootPath.relativize(path).toString().replace('\\', '/')
                        if (!glob.accepts(relStr)) continue
                        if (rawEvent.kind() == StandardWatchEventKinds.ENTRY_CREATE && Files.isDirectory(path)) {
                            registerTree(path, service)
                        }
                        val type = when (rawEvent.kind()) {
                            StandardWatchEventKinds.ENTRY_CREATE -> FileEventType.CREATE
                            StandardWatchEventKinds.ENTRY_DELETE -> FileEventType.DELETE
                            else -> FileEventType.MODIFY
                        }
                        eventChannel.send(FileEvent(relStr, type))
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

    private fun registerTree(start: java.nio.file.Path, service: WatchService) {
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

    private fun isIgnored(path: java.nio.file.Path): Boolean {
        val relative = if (path.startsWith(rootPath)) rootPath.relativize(path) else path
        return relative.any { it.toString() in WALKER_BLOCKED_SEGMENTS }
    }
}

/**
 * Walk-time directory pruning: keeps the OS-level watch registration small
 * by not registering dirs that the watcher already excludes.
 *
 * Note: the watcher still registers these at a directory level so OS-level
 * FS events are delivered.  Filters enforced in [PathGlob] apply at event time.
 */
private val WALKER_BLOCKED_SEGMENTS: Set<String> =
    setOf(".gradle", ".idea", "build", "node_modules")

/**
 * Pure glob filter — accepts/rejects a path (relative to a watch root) based
 * on include/exclude lists.  Supports `**`, `*`, `?` glob tokens.
 */
internal class PathGlob(
    includeGlobs: List<String>,
    excludeGlobs: List<String>,
) {
    private val includeRe: List<Regex> = includeGlobs.map { compile(it) }
    private val excludeRe: List<Regex> = excludeGlobs.map { compile(it) }

    fun accepts(path: String): Boolean {
        // Includes: when non-empty, the path must match at least one.
        // Excludes: when the path matches at least one, it is dropped.
        // Empty includes = "no include filter" — accept all candidate paths.
        val included = includeRe.isEmpty() || includeRe.any { it.matches(path) }
        if (!included) return false
        return excludeRe.none { it.matches(path) }
    }

    companion object {
        fun compile(glob: String): Regex {
            val sb = StringBuilder("^")
            var i = 0
            while (i < glob.length) {
                val c = glob[i]
                when {
                    c == '*' && i + 1 < glob.length && glob[i + 1] == '*' -> {
                        sb.append(".*")
                        i += 2
                        if (i < glob.length && glob[i] == '/') i++
                    }
                    c == '*' -> {
                        sb.append("[^/]*")
                        i++
                    }
                    c == '?' -> {
                        sb.append("[^/]")
                        i++
                    }
                    c == '.' || c == '(' || c == ')' || c == '+' ||
                        c == '|' || c == '^' || c == '$' || c == '{' || c == '}' -> {
                        sb.append('\\').append(c)
                        i++
                    }
                    else -> {
                        sb.append(c)
                        i++
                    }
                }
            }
            sb.append('$')
            return Regex(sb.toString())
        }
    }
}

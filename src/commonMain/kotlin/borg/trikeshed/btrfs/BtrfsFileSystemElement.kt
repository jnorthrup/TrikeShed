package borg.trikeshed.btrfs

import borg.trikeshed.userspace.nio.Volume
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext
import kotlin.time.Clock

enum class BtrfsFileType { DIRECTORY, REGULAR }

enum class BtrfsFileError {
    NOT_FOUND, ALREADY_EXISTS, NOT_DIRECTORY, IS_DIRECTORY, NOT_EMPTY, INVALID_PATH, TOO_LARGE, IO,
}

class BtrfsFileSystemException(
    val error: BtrfsFileError,
    val path: String,
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

data class BtrfsFileStat(
    val path: String,
    val type: BtrfsFileType,
    val size: Long,
    val generation: ULong,
    val mode: UInt = if (type == BtrfsFileType.DIRECTORY) 0x41edu else 0x81a4u,
    val modifiedSeconds: Long = 0,
    val modifiedNanoseconds: Int = 0,
    val uid: UInt = 0u,
    val gid: UInt = 0u,
    val accessedSeconds: Long = 0,
    val accessedNanoseconds: Int = 0,
    val changedSeconds: Long = 0,
    val changedNanoseconds: Int = 0,
    val createdSeconds: Long = 0,
    val createdNanoseconds: Int = 0,
)

/**
 * A mounted common-Kotlin filesystem over a canonical Btrfs logical image.
 *
 * This element takes ownership of [backing]. Its bounded request queue serializes
 * reads, mutations and topology changes. A mutation writes a fresh native image
 * into a new backing generation, then publishes that generation; live tree blocks
 * are never rewritten in place. This favors atomic publication over incremental
 * write performance. Paths and regular files are held in the decoded snapshot.
 *
 * Mounting this element installs no host kernel filesystem. A host transport must
 * delegate its operations to these suspend methods. The backing member containers
 * are not standalone Btrfs images; [export] produces a standalone native image.
 * Do not access the transferred backing concurrently through another owner.
 */
class BtrfsFileSystemElement private constructor(
    parent: CoroutineScope,
    private val backing: BtrfsRaidRebalance,
    initial: BtrfsImageSnapshot,
    queueCapacity: Int,
) : CoroutineContext.Element {
    companion object Key : CoroutineContext.Key<BtrfsFileSystemElement> {
        /** Ownership transfers on entry, including cleanup if image validation fails. */
        suspend fun mount(
            scope: CoroutineScope,
            backing: BtrfsRaidRebalance,
            queueCapacity: Int = 16,
        ): BtrfsFileSystemElement {
            try {
                require(queueCapacity > 0) { "Filesystem queue capacity must be positive" }
                requireNotNull(scope.coroutineContext[Job]) { "Filesystem mount requires an owning Job" }.ensureActive()
                val image = BtrfsFilesystemImage.read(backing)
                currentCoroutineContext().ensureActive()
                return BtrfsFileSystemElement(scope, backing, image, queueCapacity)
            } catch (failure: Throwable) {
                withContext(NonCancellable) {
                    runCatching { backing.drain() }.exceptionOrNull()?.let { failure.addSuppressed(it) }
                }
                throw failure
            }
        }

        /** Initialize an empty filesystem through the same generation publication boundary. */
        suspend fun format(
            scope: CoroutineScope,
            backing: BtrfsRaidRebalance,
            label: String = "",
            queueCapacity: Int = 16,
        ): BtrfsFileSystemElement {
            try {
                require(queueCapacity > 0) { "Filesystem queue capacity must be positive" }
                requireNotNull(scope.coroutineContext[Job]) { "Filesystem mount requires an owning Job" }.ensureActive()
                backing.transact(copy = false) { BtrfsFilesystemImage.write(it, emptyList(), label) }
                return mount(scope, backing, queueCapacity)
            } catch (failure: Throwable) {
                withContext(NonCancellable) {
                    runCatching { backing.drain() }.exceptionOrNull()?.let { failure.addSuppressed(it) }
                }
                throw failure
            }
        }
    }

    override val key: CoroutineContext.Key<*> get() = Key
    private var image = initial
    private var mutationFailure: Throwable? = null
    private var closeFailure: Throwable? = null
    private val supervisor = SupervisorJob(parent.coroutineContext[Job])
    private val scope = CoroutineScope(parent.coroutineContext + supervisor + this)

    private abstract class Request(val caller: Job?) {
        val settled = CompletableDeferred<Unit>()
        abstract suspend fun execute()
        abstract fun fail(failure: Throwable)
    }

    private class Operation<T>(caller: Job?, val body: suspend () -> T) : Request(caller) {
        val result = CompletableDeferred<T>()
        override suspend fun execute() {
            try { result.complete(body()) }
            catch (failure: Throwable) { result.completeExceptionally(failure) }
            finally { settled.complete(Unit) }
        }
        override fun fail(failure: Throwable) {
            result.completeExceptionally(failure)
            settled.complete(Unit)
        }
    }

    private val input = Channel<Request>(queueCapacity, onUndeliveredElement = {
        it.fail(CancellationException("Filesystem request was not admitted"))
    })
    private val consumer = scope.launch(start = CoroutineStart.UNDISPATCHED) {
        try {
            while (true) {
                val request = input.receiveCatching().getOrNull() ?: break
                if (request.caller?.isActive == false) {
                    request.fail(CancellationException("Filesystem caller cancelled before execution"))
                } else {
                    withContext(NonCancellable) { request.execute() }
                }
            }
        } finally {
            input.close()
            while (true) {
                val request = input.tryReceive().getOrNull() ?: break
                request.fail(CancellationException("Filesystem mount stopped"))
            }
            withContext(NonCancellable) {
                try { backing.drain() }
                catch (failure: Throwable) { closeFailure = failure }
                finally { supervisor.complete() }
            }
        }
    }

    private suspend fun <T> admit(body: suspend () -> T): T {
        currentCoroutineContext().ensureActive()
        val request = Operation(currentCoroutineContext()[Job]) {
            mutationFailure?.let { throw BtrfsFileSystemException(BtrfsFileError.IO, "/",
                "Filesystem publication is uncertain; reopen the committed generation", it) }
            body()
        }
        try {
            input.send(request)
            return request.result.await()
        } catch (closed: ClosedSendChannelException) {
            // A closed send never transfers ownership. In contrast, a cancelled
            // send can already have been received: its settlement remains owned
            // by the consumer or Channel's undelivered callback.
            request.fail(closed)
            throw closed
        } finally {
            withContext(NonCancellable) { request.settled.await() }
        }
    }

    suspend fun stat(path: String): BtrfsFileStat? {
        val name = normalize(path)
        return admit {
            var prefix = name.substringBeforeLast('/', "")
            while (prefix.isNotEmpty()) {
                if (entry(prefix)?.data != null) fail(BtrfsFileError.NOT_DIRECTORY, prefix)
                prefix = prefix.substringBeforeLast('/', "")
            }
            (if (name.isEmpty()) image.rootMetadata else entry(name))?.let {
                BtrfsFileStat("/$name", if (it.data == null) BtrfsFileType.DIRECTORY else BtrfsFileType.REGULAR,
                    it.data?.size?.toLong() ?: 0, image.generation, it.mode, it.modifiedSeconds, it.modifiedNanoseconds,
                    it.uid, it.gid, it.accessedSeconds, it.accessedNanoseconds, it.changedSeconds,
                    it.changedNanoseconds, it.createdSeconds, it.createdNanoseconds)
            }
        }
    }

    suspend fun list(path: String = "/"): List<String> {
        val name = normalize(path)
        return admit {
            directory(name)
            val prefix = if (name.isEmpty()) "" else "$name/"
            image.entries.asSequence().filter { it.path.startsWith(prefix) && it.path != name }
                .map { it.path.removePrefix(prefix).substringBefore('/') }.distinct().sorted().toList()
        }
    }

    suspend fun read(path: String): ByteArray {
        val name = normalize(path)
        return admit { file(name).copyOf() }
    }

    suspend fun read(path: String, offset: Long, length: Int): ByteArray {
        val name = normalize(path)
        require(offset >= 0 && length >= 0) { "Read range must be nonnegative" }
        return admit {
            val bytes = file(name)
            if (offset >= bytes.size) ByteArray(0)
            else bytes.copyOfRange(offset.toInt(), minOf(bytes.size.toLong(), offset + length).toInt())
        }
    }

    suspend fun write(path: String, bytes: ByteArray) {
        val name = mutablePath(path)
        if (bytes.size.toLong() > BtrfsFilesystemImage.MAX_SNAPSHOT_DATA_BYTES) fail(BtrfsFileError.TOO_LARGE, name)
        val captured = bytes.copyOf()
        admit {
            parent(name)
            if (entry(name)?.data == null && entry(name) != null) fail(BtrfsFileError.IS_DIRECTORY, name)
            checkDataBudget(name, captured.size.toLong())
            val entries = entries()
            entries[name] = written(entry(name)?.copy(data = captured) ?: BtrfsImageEntry(name, captured))
            commit(entries.values.toList())
        }
    }

    /** Atomic create; nonexclusive creation preserves an existing regular file's bytes. */
    suspend fun createEmpty(path: String, exclusive: Boolean = true, permissions: UInt = 0x1a4u) {
        val name = mutablePath(path)
        require((permissions and 0xfffff000u) == 0u) { "Only permission and special mode bits may change" }
        admit {
            parent(name)
            entry(name)?.let {
                if (it.data == null) fail(BtrfsFileError.IS_DIRECTORY, name)
                if (exclusive) fail(BtrfsFileError.ALREADY_EXISTS, name)
                return@admit
            }
            commit(image.entries + written(BtrfsImageEntry(name, ByteArray(0), mode = 0x8000u or permissions)))
        }
    }

    /** Offset writes are one filesystem transaction, including zero-filled extension. */
    suspend fun write(path: String, offset: Long, bytes: ByteArray): Int {
        val name = mutablePath(path)
        require(offset >= 0) { "Write offset must be nonnegative" }
        if (offset > BtrfsFilesystemImage.MAX_SNAPSHOT_DATA_BYTES - bytes.size) fail(BtrfsFileError.TOO_LARGE, name)
        val captured = bytes.copyOf()
        return admit {
            val old = file(name)
            if (captured.isEmpty()) return@admit 0
            val size = maxOf(old.size.toLong(), offset + captured.size)
            checkDataBudget(name, size)
            val updated = old.copyOf(size.toInt())
            captured.copyInto(updated, offset.toInt())
            val entries = entries()
            entries[name] = written(requireNotNull(entry(name)).copy(data = updated))
            commit(entries.values.toList())
            captured.size
        }
    }

    suspend fun truncate(path: String, size: Long) {
        val name = mutablePath(path)
        require(size >= 0) { "File size must be nonnegative" }
        if (size > BtrfsFilesystemImage.MAX_SNAPSHOT_DATA_BYTES) fail(BtrfsFileError.TOO_LARGE, name)
        admit {
            val old = file(name)
            if (size == old.size.toLong()) return@admit
            checkDataBudget(name, size)
            val entries = entries()
            entries[name] = written(requireNotNull(entry(name)).copy(data = old.copyOf(size.toInt())))
            commit(entries.values.toList())
        }
    }

    suspend fun mkdir(path: String, recursive: Boolean = false, permissions: UInt = 0x1edu) {
        val name = normalize(path)
        require((permissions and 0xfffff000u) == 0u) { "Only permission and special mode bits may change" }
        admit {
            if (name.isEmpty()) {
                if (!recursive) fail(BtrfsFileError.ALREADY_EXISTS, name)
                return@admit
            }
            entry(name)?.let {
                if (recursive && it.data == null) return@admit
                fail(BtrfsFileError.ALREADY_EXISTS, name)
            }
            val entries = entries()
            if (recursive) {
                var prefix = ""
                for (part in name.split('/')) {
                    prefix = if (prefix.isEmpty()) part else "$prefix/$part"
                    val old = entries[prefix]
                    if (old?.data != null) fail(BtrfsFileError.NOT_DIRECTORY, prefix)
                    if (old == null) entries[prefix] = written(BtrfsImageEntry(prefix, mode = 0x4000u or permissions))
                }
            } else {
                parent(name)
                entries[name] = written(BtrfsImageEntry(name, mode = 0x4000u or permissions))
            }
            commit(entries.values.toList())
        }
    }

    suspend fun remove(path: String, recursive: Boolean = false, expectedType: BtrfsFileType? = null) {
        val name = mutablePath(path)
        admit {
            parent(name)
            val target = entry(name) ?: fail(BtrfsFileError.NOT_FOUND, name)
            if (expectedType == BtrfsFileType.REGULAR && target.data == null) {
                fail(BtrfsFileError.IS_DIRECTORY, name)
            }
            if (expectedType == BtrfsFileType.DIRECTORY && target.data != null) {
                fail(BtrfsFileError.NOT_DIRECTORY, name)
            }
            val prefix = "$name/"
            if (target.data == null && !recursive && image.entries.any { it.path.startsWith(prefix) }) {
                fail(BtrfsFileError.NOT_EMPTY, name)
            }
            commit(image.entries.filterNot { it.path == name || it.path.startsWith(prefix) })
        }
    }

    suspend fun rename(source: String, destination: String, replace: Boolean = false) {
        val from = mutablePath(source)
        val to = mutablePath(destination)
        admit {
            parent(from)
            val sourceEntry = entry(from) ?: fail(BtrfsFileError.NOT_FOUND, from)
            if (from == to) return@admit
            if (to.startsWith("$from/")) fail(BtrfsFileError.INVALID_PATH, to)
            parent(to)
            entry(to)?.let {
                if (!replace) fail(BtrfsFileError.ALREADY_EXISTS, to)
                if (sourceEntry.data == null && it.data != null) fail(BtrfsFileError.NOT_DIRECTORY, to)
                if (sourceEntry.data != null && it.data == null) fail(BtrfsFileError.IS_DIRECTORY, to)
                if (it.data == null && image.entries.any { item -> item.path.startsWith("$to/") }) {
                    fail(BtrfsFileError.NOT_EMPTY, to)
                }
            }
            val entries = image.entries.filterNot { it.path == to }.map {
                when {
                    it.path == from -> changed(it.copy(path = to))
                    it.path.startsWith("$from/") -> it.copy(path = to + it.path.removePrefix(from))
                    else -> it
                }
            }
            commit(entries)
        }
    }

    suspend fun snapshot(): BtrfsImageSnapshot = admit {
        image.copy(entries = image.entries.map { it.copy(data = it.data?.copyOf()) }, fsid = image.fsid.copyOf())
    }

    /** Permission bits only; the native inode's directory/regular-file type is retained. */
    suspend fun setMode(path: String, permissions: UInt) {
        val name = normalize(path)
        require((permissions and 0xfffff000u) == 0u) { "Only permission and special mode bits may change" }
        admit {
            parent(name)
            val old = if (name.isEmpty()) image.rootMetadata else entry(name) ?: fail(BtrfsFileError.NOT_FOUND, name)
            val updated = changed(old.copy(mode = (old.mode and 0xfffff000u) or permissions))
            if (name.isEmpty()) {
                commit(image.entries, rootMetadata = updated)
                return@admit
            }
            val entries = entries()
            entries[name] = updated
            commit(entries.values.toList())
        }
    }

    suspend fun setModifiedTime(path: String, seconds: Long, nanoseconds: Int = 0): Unit =
        setTimes(path, modifiedSeconds = seconds, modifiedNanoseconds = nanoseconds)

    /** Null seconds preserve that timestamp; both updates share one publication. */
    suspend fun setTimes(
        path: String,
        accessedSeconds: Long? = null,
        accessedNanoseconds: Int = 0,
        modifiedSeconds: Long? = null,
        modifiedNanoseconds: Int = 0,
    ) {
        val name = normalize(path)
        require(accessedNanoseconds in 0..999_999_999 && modifiedNanoseconds in 0..999_999_999) {
            "Nanoseconds must be within a second"
        }
        admit {
            parent(name)
            val old = if (name.isEmpty()) image.rootMetadata else entry(name) ?: fail(BtrfsFileError.NOT_FOUND, name)
            if (accessedSeconds == null && modifiedSeconds == null) return@admit
            val updated = changed(old.copy(
                accessedSeconds = accessedSeconds ?: old.accessedSeconds,
                accessedNanoseconds = if (accessedSeconds == null) old.accessedNanoseconds else accessedNanoseconds,
                modifiedSeconds = modifiedSeconds ?: old.modifiedSeconds,
                modifiedNanoseconds = if (modifiedSeconds == null) old.modifiedNanoseconds else modifiedNanoseconds,
            ))
            if (name.isEmpty()) {
                commit(image.entries, rootMetadata = updated)
                return@admit
            }
            val entries = entries()
            entries[name] = updated
            commit(entries.values.toList())
        }
    }

    suspend fun config(): BtrfsRaidImageConfig = admit { backing.config() }
    /** Space available to the managed snapshot layout and its in-memory data quota. */
    suspend fun statFs(): BtrfsImageSpace = admit {
        BtrfsFilesystemImage.space(backing.capacity * backing.blockSize, image)
    }
    suspend fun generation(): Long = admit { backing.generation() }
    suspend fun metadataCompression(): BtrfsMetadataCompression = admit { backing.metadataCompression }
    suspend fun setMetadataCompression(compression: BtrfsMetadataCompression): Unit = admit {
        publication { backing.setMetadataCompression(compression) }
    }
    suspend fun sync(): Unit = admit { backing.sync() }

    /** Replace topology and grow its native filesystem in the same committed generation. */
    suspend fun migrate(destinationConfig: BtrfsRaidImageConfig): Long {
        val captured = destinationConfig.snapshot()
        return admit {
            var written: BtrfsImageSnapshot? = null
            val generation = nextGeneration()
            val committed = publication {
                backing.migrate(captured, copy = false) { destination ->
                    written = BtrfsFilesystemImage.write(destination, image.entries, image.label, generation,
                        fsid = image.fsid, rootMetadata = image.rootMetadata)
                }
            }
            image = requireNotNull(written)
            committed
        }
    }

    /** Destination is borrowed and must be a new, separately owned image volume. */
    suspend fun export(destination: Volume): BtrfsImageSnapshot {
        require(destination !== backing) { "Export requires a separate destination volume" }
        return admit {
            BtrfsFilesystemImage.write(destination, image.entries, image.label, image.generation,
                fsid = image.fsid, rootMetadata = image.rootMetadata)
                .also { destination.sync() }
        }
    }

    /** Stops admission, settles all admitted requests, then closes owned image members. */
    suspend fun drain(): Unit = withContext(NonCancellable) {
        input.close()
        consumer.join()
        supervisor.join()
        closeFailure?.let { throw it }
    }

    private fun entry(path: String): BtrfsImageEntry? = image.entries.firstOrNull { it.path == path }
    private fun written(entry: BtrfsImageEntry): BtrfsImageEntry {
        val now = Clock.System.now()
        return entry.copy(modifiedSeconds = now.epochSeconds, modifiedNanoseconds = now.nanosecondsOfSecond,
            changedSeconds = now.epochSeconds, changedNanoseconds = now.nanosecondsOfSecond)
    }
    private fun changed(entry: BtrfsImageEntry): BtrfsImageEntry {
        val now = Clock.System.now()
        return entry.copy(changedSeconds = now.epochSeconds, changedNanoseconds = now.nanosecondsOfSecond)
    }
    private fun entries(): LinkedHashMap<String, BtrfsImageEntry> =
        image.entries.associateByTo(LinkedHashMap()) { it.path }

    /** Check before allocating extensions; the native image budget covers all files. */
    private fun checkDataBudget(path: String, replacementSize: Long) {
        val others = image.entries.sumOf { if (it.path == path) 0L else it.data?.size?.toLong() ?: 0L }
        if (replacementSize > BtrfsFilesystemImage.MAX_SNAPSHOT_DATA_BYTES - others) {
            fail(BtrfsFileError.TOO_LARGE, path)
        }
    }

    private fun directory(path: String) {
        if (path.isEmpty()) return
        var prefix = ""
        for (part in path.split('/')) {
            prefix = if (prefix.isEmpty()) part else "$prefix/$part"
            val found = entry(prefix) ?: fail(BtrfsFileError.NOT_FOUND, prefix)
            if (found.data != null) fail(BtrfsFileError.NOT_DIRECTORY, prefix)
        }
    }

    private fun parent(path: String) = directory(path.substringBeforeLast('/', ""))
    private fun file(path: String): ByteArray {
        if (path.isEmpty()) fail(BtrfsFileError.IS_DIRECTORY, path)
        parent(path)
        return (entry(path) ?: fail(BtrfsFileError.NOT_FOUND, path)).data
            ?: fail(BtrfsFileError.IS_DIRECTORY, path)
    }

    private fun nextGeneration(): ULong {
        check(image.generation < ULong.MAX_VALUE) { "Btrfs image generation exhausted" }
        return image.generation + 1u
    }

    private suspend fun commit(entries: List<BtrfsImageEntry>, rootMetadata: BtrfsImageEntry = image.rootMetadata) {
        var written: BtrfsImageSnapshot? = null
        val generation = nextGeneration()
        publication {
            backing.transact(copy = false) { destination ->
                written = BtrfsFilesystemImage.write(destination, entries, image.label, generation,
                    fsid = image.fsid, rootMetadata = rootMetadata)
            }
        }
        image = requireNotNull(written)
    }

    private suspend fun <T> publication(body: suspend () -> T): T {
        val previous = backing.generation()
        try { return body() }
        catch (failure: Throwable) {
            // A pre-publication failure leaves the old image available. An
            // ambiguous publication must not serve a potentially stale cache.
            try {
                if (backing.generation() != previous) mutationFailure = failure
            } catch (inspection: Throwable) {
                mutationFailure = failure
                if (inspection !== failure) failure.addSuppressed(inspection)
            }
            throw failure
        }
    }

    private fun mutablePath(path: String): String = normalize(path).also {
        if (it.isEmpty()) fail(BtrfsFileError.INVALID_PATH, path)
    }

    private fun normalize(path: String): String {
        if ('\u0000' in path) fail(BtrfsFileError.INVALID_PATH, path)
        val parts = ArrayList<String>()
        for (part in path.split('/')) when (part) {
            "", "." -> Unit
            ".." -> if (parts.isEmpty()) fail(BtrfsFileError.INVALID_PATH, path) else parts.removeAt(parts.lastIndex)
            else -> {
                if (part.encodeToByteArray().size > 255) fail(BtrfsFileError.INVALID_PATH, path)
                parts.add(part)
            }
        }
        return parts.joinToString("/").also {
            if (it.encodeToByteArray().size > 4096) fail(BtrfsFileError.INVALID_PATH, path)
        }
    }

    private fun fail(error: BtrfsFileError, path: String): Nothing =
        throw BtrfsFileSystemException(error, path, "${error.name}: /$path")
}

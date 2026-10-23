package borg.trikeshed.btrfs

import borg.trikeshed.userspace.nio.file.spi.FileOperations
import borg.trikeshed.userspace.nio.file.spi.JvmFileOperations
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_BYTE
import java.lang.foreign.ValueLayout.JAVA_INT
import java.lang.foreign.ValueLayout.JAVA_LONG
import java.lang.foreign.ValueLayout.JAVA_SHORT
import java.lang.invoke.MethodHandle
import java.lang.invoke.MethodHandles
import java.nio.charset.CharacterCodingException
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/**
 * macFUSE 2 high-level transport for the common [BtrfsFileSystemElement].
 *
 * Requires an installed macFUSE system component and its libfuse.2.dylib, a
 * macOS arm64 JVM with native access enabled, and an existing empty mountpoint.
 * The single native request loop runs on an IO thread; callbacks submit to the
 * filesystem element's bounded mailbox. No Btrfs kernel module is involved.
 *
 * Regular file/directory operations and fsync are supported. The current image
 * model retains modes and inode timestamps, but has no mutable POSIX ownership,
 * links or xattrs. It projects mount-process uid/gid. Unsupported metadata changes
 * return ENOTSUP. statfs reports the managed writer's data capacity and snapshot
 * quota; inode availability is unknown. Linux Btrfs ioctls are unsupported.
 * macFUSE's default hidden-file handling retains open files across unlink.
 *
 * ABI: macfuse/library include/fuse.h and fuse_common.h, FUSE_USE_VERSION=26,
 * first 37 pointer fields of fuse_operations (through utimens). The 144-byte
 * Darwin arm64 stat layout is the same one used by JvmFileSyscalls.
 * Callers retain ownership of the filesystem and drain it after [serve] returns.
 */
object BtrfsMacFuseMount {
    val libraryCandidates = listOf(
        "/usr/local/lib/libfuse.2.dylib",
        "/Library/Filesystems/macfuse.fs/Contents/Frameworks/libfuse.2.dylib",
    )

    fun requireHost(osName: String = System.getProperty("os.name"), architecture: String = System.getProperty("os.arch")) {
        require(osName.lowercase().contains("mac") && architecture.lowercase() in setOf("arm64", "aarch64")) {
            "The macFUSE transport requires macOS arm64; this host is $osName/$architecture"
        }
        check(ADDRESS.byteSize() == 8L) { "The macFUSE transport requires a 64-bit JVM" }
    }

    internal fun validateMountPoint(files: FileOperations, requested: String): String {
        require(requested.isNotBlank() && '\u0000' !in requested) { "Invalid mountpoint" }
        val path = files.canonicalPath(requested)
        require(path != "/" && files.isDir(path)) { "Mountpoint must be an existing directory: $path" }
        require(files.listDir(path).isEmpty()) { "Mountpoint must be empty: $path" }
        return path
    }

    /** Runs until a normal unmount, coroutine cancellation, or JVM shutdown. */
    @OptIn(InternalCoroutinesApi::class)
    suspend fun serve(
        filesystem: BtrfsFileSystemElement,
        mountPoint: String,
        libraryPath: String? = null,
        onShutdown: (suspend () -> Unit)? = null,
    ) {
        requireHost()
        val caller = currentCoroutineContext()
        caller.ensureActive()
        val files = caller[FileOperations] ?: JvmFileOperations()
        withContext(Dispatchers.IO) {
            val path = validateMountPoint(files, mountPoint)
            val library = libraryPath?.also {
                require('\u0000' !in it && files.isFile(it)) { "macFUSE library is not a file: $it" }
            } ?: libraryCandidates.firstOrNull(files::isFile)
                ?: throw UnsupportedOperationException("Install macFUSE; libfuse.2.dylib was not found in $libraryCandidates")
            Arena.ofShared().use { arena ->
                val native = BtrfsMacFuseNative(SymbolLookup.libraryLookup(Path.of(library), arena))
                check(native.version() in 26..29) { "Only the macFUSE 2.6–2.9 high-level ABI is supported" }
                val callbacks = BtrfsMacFuseCallbacks(filesystem, native.uid(), native.gid())
                val operations = callbacks.operations(arena)
                val arguments = native.arguments(arena)
                val point = arena.allocateFrom(path)
                var channel = MemorySegment.NULL
                var fuse = MemorySegment.NULL
                val gate = Any()
                var released = false
                var unmountRequested = false
                val completed = CountDownLatch(1)
                fun unmount() = synchronized(gate) {
                    if (!released && !unmountRequested && channel != MemorySegment.NULL) {
                        unmountRequested = true
                        if (fuse != MemorySegment.NULL) native.exit(fuse)
                        native.unmount(point, channel)
                    }
                }
                // Cancellation handlers must not block the caller's dispatcher: an admitted
                // filesystem request may still need it before the native loop can settle.
                val unmountThread = Thread({ runCatching { unmount() } }, "trikeshed-btrfs-unmount-request")
                var unmountStarted = false
                fun requestUnmount() = synchronized(gate) {
                    if (!released && !unmountStarted) {
                        unmountStarted = true
                        unmountThread.start()
                    }
                }
                val shutdown = Thread({
                    requestUnmount()
                    if (completed.await(30, TimeUnit.SECONDS)) {
                        runBlocking { if (onShutdown != null) onShutdown() else filesystem.drain() }
                    } else System.err.println("macFUSE shutdown did not settle within 30 seconds: $path")
                }, "trikeshed-btrfs-unmount")
                var hookAdded = false
                var cancellation: kotlinx.coroutines.DisposableHandle? = null
                try {
                    synchronized(gate) {
                        caller.ensureActive()
                        // Recheck directly before asking macFUSE to mount; never cover known files.
                        require(files.listDir(path).isEmpty()) { "Mountpoint is no longer empty: $path" }
                        channel = native.mount(point, arguments)
                        check(channel != MemorySegment.NULL) { "macFUSE could not mount $path; check its system component and mount permissions" }
                        fuse = native.create(channel, arguments, operations)
                        check(fuse != MemorySegment.NULL) { "macFUSE could not create the filesystem session" }
                    }
                    Runtime.getRuntime().addShutdownHook(shutdown)
                    hookAdded = true
                    cancellation = caller[Job]?.invokeOnCompletion(onCancelling = true, invokeImmediately = true) { cause ->
                        if (cause != null) requestUnmount()
                    }
                    caller.ensureActive()
                    check(native.loop(fuse) == 0) { "macFUSE request loop failed for $path" }
                } finally {
                    cancellation?.dispose()
                    if (hookAdded) runCatching { Runtime.getRuntime().removeShutdownHook(shutdown) }
                    synchronized(gate) {
                        try {
                            unmount()
                            if (fuse != MemorySegment.NULL) native.destroy(fuse)
                        } finally {
                            released = true
                            native.freeArguments(arguments)
                            completed.countDown()
                        }
                    }
                    if (unmountStarted) unmountThread.join()
                }
            }
        }
    }
}

/** Native bindings are loaded only after host and mountpoint validation. */
internal class BtrfsMacFuseNative(private val symbols: SymbolLookup) {
    private val linker = Linker.nativeLinker()
    private fun bind(name: String, descriptor: FunctionDescriptor): MethodHandle =
        linker.downcallHandle(symbols.find(name).orElseThrow {
            UnsupportedOperationException("The macFUSE library does not export $name")
        }, descriptor)

    private val version = bind("fuse_version", FunctionDescriptor.of(JAVA_INT))
    private val mount = bind("fuse_mount", FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS))
    private val create = bind("fuse_new", FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, ADDRESS, JAVA_LONG, ADDRESS))
    private val loop = bind("fuse_loop", FunctionDescriptor.of(JAVA_INT, ADDRESS))
    private val exit = bind("fuse_exit", FunctionDescriptor.ofVoid(ADDRESS))
    private val unmount = bind("fuse_unmount", FunctionDescriptor.ofVoid(ADDRESS, ADDRESS))
    private val destroy = bind("fuse_destroy", FunctionDescriptor.ofVoid(ADDRESS))
    private val freeArgs = bind("fuse_opt_free_args", FunctionDescriptor.ofVoid(ADDRESS))

    fun version(): Int = version.invokeExact() as Int
    private fun identity(name: String): Int = linker.downcallHandle(linker.defaultLookup().find(name).orElseThrow(),
        FunctionDescriptor.of(JAVA_INT)).invokeExact() as Int
    fun uid(): Int = identity("geteuid")
    fun gid(): Int = identity("getegid")
    fun mount(point: MemorySegment, args: MemorySegment): MemorySegment = mount.invokeExact(point, args) as MemorySegment
    fun create(channel: MemorySegment, args: MemorySegment, operations: MemorySegment): MemorySegment =
        create.invokeExact(channel, args, operations, BtrfsMacFuseCallbacks.OPERATIONS_BYTES, MemorySegment.NULL) as MemorySegment
    fun loop(fuse: MemorySegment): Int = loop.invokeExact(fuse) as Int
    fun exit(fuse: MemorySegment) { exit.invokeExact(fuse) }
    fun unmount(point: MemorySegment, channel: MemorySegment) { unmount.invokeExact(point, channel) }
    fun destroy(fuse: MemorySegment) { destroy.invokeExact(fuse) }
    fun freeArguments(args: MemorySegment) { freeArgs.invokeExact(args) }

    fun arguments(arena: Arena): MemorySegment {
        val options = listOf("trikeshed-btrfs", "-o",
            "fsname=trikeshed-btrfs,volname=TrikeShed,nosuid,default_permissions,attr_timeout=0,entry_timeout=0,negative_timeout=0")
        val argv = arena.allocate((options.size + 1L) * ADDRESS.byteSize(), ADDRESS.byteAlignment()).fill(0)
        options.forEachIndexed { i, value -> argv.setAtIndex(ADDRESS, i.toLong(), arena.allocateFrom(value)) }
        return arena.allocate(24, 8).fill(0).also {
            it.set(JAVA_INT, 0, options.size)
            it.set(ADDRESS, 8, argv)
            // allocated=0: fuse_opt_free_args frees only vectors subsequently allocated by libfuse.
        }
    }
}

/** macFUSE callbacks contain every exception before returning through the native ABI. */
internal class BtrfsMacFuseCallbacks(private val filesystem: BtrfsFileSystemElement, private val uid: Int, private val gid: Int) {
    companion object {
        const val OPERATIONS_BYTES = 37L * 8
        const val STAT_BYTES = 144L
        const val STATVFS_BYTES = 88L
        const val FILE_INFO_BYTES = 40L
        const val ENOTSUP = 45

        fun errno(error: BtrfsFileError): Int = when (error) {
            BtrfsFileError.NOT_FOUND -> 2
            BtrfsFileError.ALREADY_EXISTS -> 17
            BtrfsFileError.NOT_DIRECTORY -> 20
            BtrfsFileError.IS_DIRECTORY -> 21
            BtrfsFileError.NOT_EMPTY -> 66
            BtrfsFileError.INVALID_PATH -> 22
            BtrfsFileError.TOO_LARGE -> 27
            BtrfsFileError.IO -> 5
        }

        fun statBuffer(buffer: MemorySegment, stat: BtrfsFileStat, uid: Int, gid: Int) {
            val bytes = buffer.reinterpret(STAT_BYTES).fill(0)
            val directory = stat.type == BtrfsFileType.DIRECTORY
            bytes.set(JAVA_SHORT, 4, ((if (directory) 0x4000 else 0x8000) or (stat.mode.toInt() and 0xfff)).toShort())
            bytes.set(JAVA_SHORT, 6, (if (directory) 2 else 1).toShort())
            bytes.set(JAVA_INT, 16, uid)
            bytes.set(JAVA_INT, 20, gid)
            bytes.set(JAVA_LONG, 32, stat.accessedSeconds)
            bytes.set(JAVA_LONG, 40, stat.accessedNanoseconds.toLong())
            bytes.set(JAVA_LONG, 48, stat.modifiedSeconds)
            bytes.set(JAVA_LONG, 56, stat.modifiedNanoseconds.toLong())
            bytes.set(JAVA_LONG, 64, stat.changedSeconds)
            bytes.set(JAVA_LONG, 72, stat.changedNanoseconds.toLong())
            bytes.set(JAVA_LONG, 80, stat.createdSeconds)
            bytes.set(JAVA_LONG, 88, stat.createdNanoseconds.toLong())
            bytes.set(JAVA_LONG, 96, stat.size)
            bytes.set(JAVA_LONG, 104, (stat.size + 4095) / 4096 * 8)
            bytes.set(JAVA_INT, 112, 4096)
        }
    }

    fun operations(arena: Arena): MemorySegment {
        val table = arena.allocate(OPERATIONS_BYTES, 8).fill(0)
        fun callback(index: Long, name: String, descriptor: FunctionDescriptor) {
            val method = MethodHandles.lookup().findVirtual(javaClass, name, descriptor.toMethodType()).bindTo(this)
            table.setAtIndex(ADDRESS, index, Linker.nativeLinker().upcallStub(method, descriptor, arena))
        }
        fun integer(vararg arguments: java.lang.foreign.MemoryLayout) = FunctionDescriptor.of(JAVA_INT, *arguments)
        callback(0, "getattr", integer(ADDRESS, ADDRESS))
        callback(1, "readlink", integer(ADDRESS, ADDRESS, JAVA_LONG))
        callback(3, "mknod", integer(ADDRESS, JAVA_SHORT, JAVA_INT))
        callback(4, "mkdir", integer(ADDRESS, JAVA_SHORT))
        callback(5, "unlink", integer(ADDRESS))
        callback(6, "rmdir", integer(ADDRESS))
        callback(7, "linkUnsupported", integer(ADDRESS, ADDRESS))
        callback(8, "rename", integer(ADDRESS, ADDRESS))
        callback(9, "linkUnsupported", integer(ADDRESS, ADDRESS))
        callback(10, "chmod", integer(ADDRESS, JAVA_SHORT))
        callback(11, "chown", integer(ADDRESS, JAVA_INT, JAVA_INT))
        callback(12, "truncate", integer(ADDRESS, JAVA_LONG))
        callback(13, "utime", integer(ADDRESS, ADDRESS))
        callback(14, "open", integer(ADDRESS, ADDRESS))
        callback(15, "read", integer(ADDRESS, ADDRESS, JAVA_LONG, JAVA_LONG, ADDRESS))
        callback(16, "write", integer(ADDRESS, ADDRESS, JAVA_LONG, JAVA_LONG, ADDRESS))
        callback(17, "statfs", integer(ADDRESS, ADDRESS))
        callback(18, "flush", integer(ADDRESS, ADDRESS))
        callback(19, "release", integer(ADDRESS, ADDRESS))
        callback(20, "fsync", integer(ADDRESS, JAVA_INT, ADDRESS))
        callback(21, "setxattr", integer(ADDRESS, ADDRESS, ADDRESS, JAVA_LONG, JAVA_INT, JAVA_INT))
        callback(22, "getxattr", integer(ADDRESS, ADDRESS, ADDRESS, JAVA_LONG, JAVA_INT))
        callback(23, "listxattr", integer(ADDRESS, ADDRESS, JAVA_LONG))
        callback(24, "metadataUnsupported", integer(ADDRESS, ADDRESS))
        callback(25, "opendir", integer(ADDRESS, ADDRESS))
        callback(26, "readdir", integer(ADDRESS, ADDRESS, ADDRESS, JAVA_LONG, ADDRESS))
        callback(27, "release", integer(ADDRESS, ADDRESS))
        callback(28, "fsync", integer(ADDRESS, JAVA_INT, ADDRESS))
        callback(31, "access", integer(ADDRESS, JAVA_INT))
        callback(32, "create", integer(ADDRESS, JAVA_SHORT, ADDRESS))
        callback(33, "ftruncate", integer(ADDRESS, JAVA_LONG, ADDRESS))
        callback(34, "fgetattr", integer(ADDRESS, ADDRESS, ADDRESS))
        callback(36, "utimens", integer(ADDRESS, ADDRESS))
        return table
    }

    private fun decodePath(value: MemorySegment): String {
        require(value != MemorySegment.NULL) { "Path is null" }
        val bytes = value.reinterpret(4096)
        var length = 0L
        while (length < bytes.byteSize() && bytes.get(JAVA_BYTE, length) != 0.toByte()) length++
        require(length < bytes.byteSize()) { "Path has no terminator within 4096 bytes" }
        return try { bytes.asSlice(0, length).toArray(JAVA_BYTE).decodeToString(throwOnInvalidSequence = true) }
        catch (failure: CharacterCodingException) { throw IllegalArgumentException("Path must be valid UTF-8", failure) }
    }

    private fun call(body: suspend () -> Int): Int = try { runBlocking { body() } }
    catch (failure: BtrfsFileSystemException) { -errno(failure.error) }
    catch (_: CancellationException) { -4 }
    catch (_: IllegalArgumentException) { -22 }
    catch (_: UnsupportedOperationException) { -ENOTSUP }
    catch (_: Throwable) { -5 }

    fun getattr(path: MemorySegment, buffer: MemorySegment): Int = call {
        val entry = filesystem.stat(decodePath(path)) ?: return@call -2
        statBuffer(buffer, entry, uid, gid)
        0
    }

    fun statfs(path: MemorySegment, buffer: MemorySegment): Int = call {
        val space = filesystem.statFs()
        // Darwin arm64 sys/statvfs.h: 11 eight-byte fields. Unknown inode limits stay zero.
        val bytes = buffer.reinterpret(STATVFS_BYTES).fill(0)
        bytes.set(JAVA_LONG, 0, space.blockSize.toLong())
        bytes.set(JAVA_LONG, 8, space.blockSize.toLong())
        bytes.set(JAVA_LONG, 16, space.totalBlocks)
        bytes.set(JAVA_LONG, 24, space.freeDataBlocks)
        bytes.set(JAVA_LONG, 32, space.availableDataBlocks)
        bytes.set(JAVA_LONG, 72, 2L) // ST_NOSUID, matching the explicit mount option.
        bytes.set(JAVA_LONG, 80, 255L)
        0
    }

    fun mkdir(path: MemorySegment, mode: Short): Int = call { filesystem.mkdir(decodePath(path), permissions = (mode.toInt() and 0xfff).toUInt()); 0 }
    fun mknod(path: MemorySegment, mode: Short, device: Int): Int = call {
        if ((mode.toInt() and 0xf000) !in setOf(0, 0x8000)) return@call -ENOTSUP
        filesystem.createEmpty(decodePath(path), permissions = (mode.toInt() and 0xfff).toUInt()); 0
    }

    fun unlink(path: MemorySegment): Int = call {
        filesystem.remove(decodePath(path), expectedType = BtrfsFileType.REGULAR); 0
    }

    fun rmdir(path: MemorySegment): Int = call {
        filesystem.remove(decodePath(path), expectedType = BtrfsFileType.DIRECTORY); 0
    }

    fun rename(source: MemorySegment, destination: MemorySegment): Int = call {
        filesystem.rename(decodePath(source), decodePath(destination), replace = true); 0
    }

    fun truncate(path: MemorySegment, size: Long): Int = call { filesystem.truncate(decodePath(path), size); 0 }
    fun ftruncate(path: MemorySegment, size: Long, info: MemorySegment): Int = truncate(path, size)
    fun fgetattr(path: MemorySegment, stat: MemorySegment, info: MemorySegment): Int = getattr(path, stat)

    fun open(path: MemorySegment, info: MemorySegment): Int = call {
        val entry = filesystem.stat(decodePath(path)) ?: return@call -2
        if (entry.type != BtrfsFileType.REGULAR) return@call -21
        directIo(info)
        0
    }

    private fun directIo(info: MemorySegment) {
        val bytes = info.reinterpret(FILE_INFO_BYTES)
        bytes.set(JAVA_INT, 20, bytes.get(JAVA_INT, 20) or 1)
    }

    fun create(path: MemorySegment, mode: Short, info: MemorySegment): Int = call {
        filesystem.createEmpty(decodePath(path), exclusive = true, permissions = (mode.toInt() and 0xfff).toUInt())
        directIo(info)
        0
    }

    fun read(path: MemorySegment, destination: MemorySegment, size: Long, offset: Long, info: MemorySegment): Int = call {
        require(size in 0..Int.MAX_VALUE.toLong() && offset >= 0)
        val bytes = filesystem.read(decodePath(path), offset, size.toInt())
        MemorySegment.copy(MemorySegment.ofArray(bytes), 0, destination.reinterpret(size), 0, bytes.size.toLong())
        bytes.size
    }

    fun write(path: MemorySegment, source: MemorySegment, size: Long, offset: Long, info: MemorySegment): Int = call {
        require(size in 0..Int.MAX_VALUE.toLong() && offset >= 0)
        filesystem.write(decodePath(path), offset, source.reinterpret(size).toArray(JAVA_BYTE))
    }

    fun opendir(path: MemorySegment, info: MemorySegment): Int = call {
        val entry = filesystem.stat(decodePath(path)) ?: return@call -2
        if (entry.type == BtrfsFileType.DIRECTORY) 0 else -20
    }

    fun readdir(path: MemorySegment, buffer: MemorySegment, filler: MemorySegment, offset: Long, info: MemorySegment): Int = call {
        require(offset >= 0)
        val entries = listOf(".", "..") + filesystem.list(decodePath(path))
        if (offset >= entries.size) return@call 0
        val fill = Linker.nativeLinker().downcallHandle(filler, FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, JAVA_LONG))
        Arena.ofConfined().use { arena ->
            for (index in offset.toInt() until entries.size) {
                val name = arena.allocateFrom(entries[index])
                if ((fill.invokeExact(buffer, name, MemorySegment.NULL, index + 1L) as Int) != 0) break
            }
        }
        0
    }

    fun access(path: MemorySegment, mask: Int): Int = call {
        val entry = filesystem.stat(decodePath(path)) ?: return@call -2
        if (mask and 7.inv() != 0) -22
        else if (mask and 1 != 0 && entry.mode and 0x49u == 0u) -13
        else 0
    }

    fun flush(path: MemorySegment, info: MemorySegment): Int = call { filesystem.sync(); 0 }
    fun fsync(path: MemorySegment, dataOnly: Int, info: MemorySegment): Int = flush(path, info)
    fun release(path: MemorySegment, info: MemorySegment): Int = 0
    fun readlink(path: MemorySegment, buffer: MemorySegment, size: Long): Int = -ENOTSUP
    fun linkUnsupported(source: MemorySegment, destination: MemorySegment): Int = -ENOTSUP
    fun chmod(path: MemorySegment, mode: Short): Int = call {
        filesystem.setMode(decodePath(path), (mode.toInt() and 0xfff).toUInt()); 0
    }
    fun utime(path: MemorySegment, times: MemorySegment): Int = call {
        val now = Instant.now()
        val bytes = if (times == MemorySegment.NULL) null else times.reinterpret(16)
        filesystem.setTimes(decodePath(path),
            accessedSeconds = bytes?.get(JAVA_LONG, 0) ?: now.epochSecond,
            modifiedSeconds = bytes?.get(JAVA_LONG, 8) ?: now.epochSecond)
        0
    }
    fun utimens(path: MemorySegment, times: MemorySegment): Int = call {
        val now = Instant.now()
        val bytes = if (times == MemorySegment.NULL) null else times.reinterpret(32)
        val seconds = LongArray(2)
        val nanoseconds = IntArray(2)
        val omitted = BooleanArray(2)
        for (index in 0..1) {
            // Darwin sys/stat.h: UTIME_NOW=-1, UTIME_OMIT=-2.
            val nanos = bytes?.get(JAVA_LONG, index * 16L + 8) ?: -1L
            when (nanos) {
                -2L -> omitted[index] = true
                -1L -> { seconds[index] = now.epochSecond; nanoseconds[index] = now.nano }
                else -> {
                    require(nanos in 0..999_999_999L) { "Invalid timespec nanoseconds" }
                    seconds[index] = requireNotNull(bytes).get(JAVA_LONG, index * 16L)
                    nanoseconds[index] = nanos.toInt()
                }
            }
        }
        filesystem.setTimes(decodePath(path),
            accessedSeconds = if (omitted[0]) null else seconds[0], accessedNanoseconds = nanoseconds[0],
            modifiedSeconds = if (omitted[1]) null else seconds[1], modifiedNanoseconds = nanoseconds[1])
        0
    }
    fun chown(path: MemorySegment, owner: Int, group: Int): Int = -ENOTSUP
    fun metadataUnsupported(path: MemorySegment, data: MemorySegment): Int = -ENOTSUP
    fun setxattr(path: MemorySegment, name: MemorySegment, data: MemorySegment, size: Long, flags: Int, position: Int): Int = -ENOTSUP
    fun getxattr(path: MemorySegment, name: MemorySegment, data: MemorySegment, size: Long, position: Int): Int = -ENOTSUP
    fun listxattr(path: MemorySegment, data: MemorySegment, size: Long): Int = -ENOTSUP
}

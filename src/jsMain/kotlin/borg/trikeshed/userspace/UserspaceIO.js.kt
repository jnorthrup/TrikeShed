package borg.trikeshed.userspace

import borg.trikeshed.lib.Series
import borg.trikeshed.lib.fs
import borg.trikeshed.lib.get
import borg.trikeshed.lib.j
import borg.trikeshed.lib.size
import borg.trikeshed.userspace.UringOp.Companion.UringSubmission
import borg.trikeshed.userspace.nio.ByteOrder
import kotlin.js.jsTypeOf
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

private val nodeFs: dynamic get() = runCatching { fs }.getOrNull()

internal fun nodeUringInteger(value: Long): dynamic {
    val text = value.toString()
    return js("BigInt(text)")
}

private class JsDescriptor(val fd: Int, val module: dynamic = null, val handle: dynamic = null)

private object JsFileTable {
    private var nextId = 1 shl 20
    private val files = mutableMapOf<Int, JsDescriptor>()
    fun register(fd: Int, module: dynamic = null, handle: dynamic = null): Int =
        (nextId++).also { files[it] = JsDescriptor(fd, module, handle) }
    fun descriptor(id: Int): JsDescriptor? = files[id]
    fun remove(id: Int): JsDescriptor? = files.remove(id)
    fun close(id: Int, userData: Long = 0): Int {
        val descriptor = files.remove(id) ?: return -9
        return try {
            if (descriptor.module == null) { nodeFs.closeSync(descriptor.fd); 0 }
            else descriptor.module.execute(descriptor.handle, UringOp.CLOSE.code, descriptor.fd,
                null, 0, 0, nodeUringInteger(0), nodeUringInteger(userData)) as Int
        } catch (failure: dynamic) { jsIoError(failure) }
    }
}

private fun jsIoError(failure: dynamic): Int = if (failure is IllegalArgumentException) -22 else when (failure?.code as? String) {
    "ENOENT" -> -2
    "EINTR" -> -4
    "EIO" -> -5
    "EAGAIN", "EWOULDBLOCK" -> -11
    "EFAULT" -> -14
    "EBADF" -> -9
    "EACCES", "EPERM" -> -13
    "EEXIST" -> -17
    "ENOTDIR" -> -20
    "EISDIR" -> -21
    "ENFILE" -> -23
    "EMFILE" -> -24
    "EFBIG" -> -27
    "EINVAL", "ERR_OUT_OF_RANGE", "ERR_INVALID_ARG_TYPE" -> -22
    "ENOSPC" -> -28
    "EROFS" -> -30
    "ENAMETOOLONG" -> -36
    "ELOOP" -> -40
    "EOPNOTSUPP", "ENOSYS" -> -95
    else -> -5
}

private fun jsOpenFlags(host: dynamic, flags: Long): Int {
    require(flags >= 0 && flags and (3L or 64L or 128L or 512L).inv() == 0L && flags and 3L != 3L)
    val constants = host.constants
    var nativeFlags: Int = when ((flags and 3L).toInt()) {
        0 -> constants.O_RDONLY as Int
        1 -> constants.O_WRONLY as Int
        else -> constants.O_RDWR as Int
    }
    if (flags and 64L != 0L) nativeFlags = nativeFlags or (constants.O_CREAT as Int)
    if (flags and 128L != 0L) nativeFlags = nativeFlags or (constants.O_EXCL as Int)
    if (flags and 512L != 0L) nativeFlags = nativeFlags or (constants.O_TRUNC as Int)
    return nativeFlags
}

private fun jsOpen(path: String, flags: Long, mode: Int = 438): Int {
    val host = nodeFs ?: throw UnsupportedOperationException("Host file primitives are unavailable")
    require(mode in 0..511)
    return JsFileTable.register(host.openSync(path, jsOpenFlags(host, flags), mode) as Int)
}

private fun UringSubmission.nodePath(): String {
    val bytes = requireNotNull(buffer)
    require(len > 0 && len <= bytes.remaining())
    val start = bytes.arrayOffset() + bytes.position()
    return bytes.array().decodeToString(start, start + len, throwOnInvalidSequence = true).also {
        require('\u0000' !in it)
    }
}

/** Encode the facade metadata payload without changing the caller's byte order. */
private fun jsStatxResult(sub: UringSubmission, size: Long, mtime: Long, kind: Long): Int {
    if (size < 0) return if (size >= Int.MIN_VALUE.toLong()) size.toInt() else -5
    val buffer = sub.buffer ?: return -22
    if (buffer.isReadOnly() || sub.len < 24 || sub.len > buffer.remaining() || sub.operationFlags != 0 || sub.offset != 0L || sub.addr != 0L) return -22
    buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN).putLong(size).putLong(mtime).putLong(kind)
    buffer.position(buffer.position() + 24)
    return 24
}

private fun jsStatxResult(sub: UringSubmission, metadata: dynamic): Int = jsStatxResult(
    sub, metadata.size.toString().toLong(), (metadata.mtimeMs as Double).toLong(),
    if (metadata.isFile() as Boolean) 1L else if (metadata.isDirectory() as Boolean) 2L else 0L,
)

actual class FileImpl actual constructor(actual val id: Int) {
    actual fun isOpen(): Boolean = JsFileTable.descriptor(id) != null
    actual fun close() { JsFileTable.close(id) }
    actual fun size(): Long = JsFileTable.descriptor(id)?.let {
        if (it.module == null) (nodeFs.fstatSync(it.fd).size as Double).toLong()
        else if (jsTypeOf(it.module.size) == "function") (it.module.size(it.fd).toString() as String).toLong()
        else -1L
    } ?: -1L
}

internal actual object FilesImpl {
    actual fun open(path: String, readOnly: Boolean): FileImpl =
        FileImpl(jsOpen(path, if (readOnly) 0L else 2L))
}

internal actual object ChannelsImpl {
    actual fun socket(domain: Int, type: Int, protocol: Int): FileImpl =
        throw UnsupportedOperationException("Socket construction requires a supported uring SOCKET adapter")
}

/** Node file primitives service the common uring contract; restricted hosts explicitly reject file operations. */
internal class JsUserspaceChannelBackend : UserspaceChannelBackend {
    private val host: dynamic = nodeFs
    override val capabilities = if (host == null) UringOp.NOP.mask else UringOp.caps(
        UringOp.NOP, UringOp.OPENAT, UringOp.READ, UringOp.WRITE, UringOp.FSYNC, UringOp.FTRUNCATE, UringOp.CLOSE, UringOp.STATX,
    )
    override val availability = if (host == null) "emulated: host file primitives unavailable"
        else "emulated: Node file primitives; no native io_uring bridge selected"
    private val owned = mutableSetOf<Int>()
    private var closed = false

    private fun validation(sub: UringSubmission): Int {
        if (closed) return -9
        if (sub.flags != 0 || capabilities and sub.opcode.mask == 0L) return -95
        if (sub.opcode == UringOp.NOP) return 0
        if (sub.opcode == UringOp.OPENAT) return when {
            sub.fd != -100 -> -95
            sub.operationFlags !in 0..511 -> -22
            else -> 0
        }
        val descriptor = JsFileTable.descriptor(sub.fd) ?: return -9
        if (sub.opcode == UringOp.CLOSE) return 0
        if (descriptor.module != null) return -95
        if (sub.opcode == UringOp.READ || sub.opcode == UringOp.WRITE) {
            val buffer = sub.buffer ?: return -22
            if (sub.len < 0 || sub.len > buffer.remaining() || sub.offset < -1 || sub.offset > 9007199254740991L) return -22
            if (sub.opcode == UringOp.READ && buffer.isReadOnly()) return -22
        }
        if (sub.opcode == UringOp.STATX) {
            val buffer = sub.buffer ?: return -22
            if (buffer.isReadOnly() || sub.len < 24 || sub.len > buffer.remaining() || sub.operationFlags != 0 || sub.offset != 0L || sub.addr != 0L) return -22
        }
        if (sub.opcode == UringOp.FTRUNCATE && (sub.offset < 0 || sub.offset > 9007199254740991L)) return -22
        return 0
    }

    internal fun execute(sub: UringSubmission): Int {
        val invalid = validation(sub)
        if (invalid != 0) return invalid
        return try {
            when (sub.opcode) {
                UringOp.NOP -> 0
                UringOp.OPENAT -> jsOpen(sub.nodePath(), sub.offset, sub.operationFlags).also { owned.add(it) }
                UringOp.CLOSE -> { owned.remove(sub.fd); JsFileTable.close(sub.fd, sub.userData) }
                else -> {
                    val fd = JsFileTable.descriptor(sub.fd)!!.fd
                    when (sub.opcode) {
                        UringOp.READ, UringOp.WRITE -> {
                            val buffer = sub.buffer!!
                            val start = buffer.arrayOffset() + buffer.position()
                            val offset = if (sub.offset == -1L) null else sub.offset.toDouble()
                            val count = if (sub.opcode == UringOp.READ)
                                host.readSync(fd, buffer.array(), start, sub.len, offset) as Int
                            else host.writeSync(fd, buffer.array(), start, sub.len, offset) as Int
                            buffer.position(buffer.position() + count)
                            count
                        }
                        UringOp.STATX -> jsStatxResult(sub, host.fstatSync(fd))
                        UringOp.FSYNC -> { host.fsyncSync(fd); 0 }
                        UringOp.FTRUNCATE -> { host.ftruncateSync(fd, sub.offset.toDouble()); 0 }
                        else -> -95
                    }
                }
            }
        } catch (failure: dynamic) { jsIoError(failure) }
    }

    /** An admitted Node callback retains the buffer until the OS primitive settles. */
    private suspend fun executeAsync(sub: UringSubmission): Int {
        val invalid = validation(sub)
        if (invalid != 0) return invalid
        if (sub.opcode == UringOp.NOP) return 0
        return try {
            when (sub.opcode) {
                UringOp.OPENAT -> {
                    val path = sub.nodePath()
                    val flags = jsOpenFlags(host, sub.offset)
                    suspendCoroutine { continuation ->
                        host.open(path, flags, sub.operationFlags, { error: dynamic, fd: dynamic ->
                            continuation.resume(if (error != null) jsIoError(error)
                                else JsFileTable.register(fd as Int).also { owned.add(it) })
                        })
                    }
                }
                UringOp.CLOSE -> {
                    val descriptor = JsFileTable.descriptor(sub.fd)!!
                    owned.remove(sub.fd)
                    if (descriptor.module != null) JsFileTable.close(sub.fd, sub.userData)
                    else {
                        JsFileTable.remove(sub.fd)
                        suspendCoroutine { continuation ->
                            host.close(descriptor.fd, { error: dynamic ->
                                continuation.resume(if (error == null) 0 else jsIoError(error))
                            })
                        }
                    }
                }
                UringOp.READ, UringOp.WRITE -> {
                    val fd = JsFileTable.descriptor(sub.fd)!!.fd
                    val buffer = sub.buffer!!
                    val position = buffer.position()
                    val start = buffer.arrayOffset() + position
                    val offset = if (sub.offset == -1L) null else sub.offset.toDouble()
                    suspendCoroutine { continuation ->
                        val completion = { error: dynamic, transferred: dynamic, _: dynamic ->
                            if (error != null) continuation.resume(jsIoError(error))
                            else {
                                val count = transferred as Int
                                buffer.position(position + count)
                                continuation.resume(count)
                            }
                        }
                        if (sub.opcode == UringOp.READ) host.read(fd, buffer.array(), start, sub.len, offset, completion)
                        else host.write(fd, buffer.array(), start, sub.len, offset, completion)
                    }
                }
                UringOp.STATX -> {
                    val fd = JsFileTable.descriptor(sub.fd)!!.fd
                    suspendCoroutine { continuation ->
                        host.fstat(fd, { error: dynamic, metadata: dynamic ->
                            val result = if (error != null) jsIoError(error) else try {
                                jsStatxResult(sub, metadata)
                            } catch (failure: dynamic) { jsIoError(failure) }
                            continuation.resume(result)
                        })
                    }
                }
                UringOp.FSYNC, UringOp.FTRUNCATE -> {
                    val fd = JsFileTable.descriptor(sub.fd)!!.fd
                    suspendCoroutine { continuation ->
                        val completion = { error: dynamic ->
                            continuation.resume(if (error == null) 0 else jsIoError(error))
                        }
                        if (sub.opcode == UringOp.FSYNC) host.fsync(fd, completion)
                        else host.ftruncate(fd, sub.offset.toDouble(), completion)
                    }
                }
                else -> -95
            }
        } catch (failure: dynamic) { jsIoError(failure) }
    }

    override fun submitBatch(submissions: List<UringSubmission>): List<SelectionResult> =
        submissions.map { SelectionResult(execute(it), it.userData) }

    override suspend fun batchEnqueue(submissions: Series<UringSubmission>): Series<UringCompletion> {
        val results = arrayOfNulls<UringCompletion>(submissions.size)
        for (index in results.indices) {
            val sub = submissions[index]
            results[index] = UringCompletion(sub.userData, executeAsync(sub), 0)
        }
        return results.size j { results[it]!! }
    }

    override fun close() {
        if (closed) return
        closed = true
        owned.forEach { JsFileTable.close(it) }
        owned.clear()
    }
}

actual fun openUserspaceChannelBackend(entries: Int): UserspaceChannelBackend {
    require(entries > 0)
    val discovery = discoverNodeUringBackend(entries)
    check(nodeUringMode() != UringBackendMode.NATIVE || discovery.backend != null) {
        "Native Node io_uring required: ${discovery.report.description}"
    }
    val selected = discovery.backend ?: JsUserspaceChannelBackend()
    return object : UserspaceChannelBackend by selected {
        override val probeReport = discovery.report
        override val availability = discovery.report.description
    }
}

/** ABI1 uses synchronous primitives, Int8Array bytes and BigInt offsets/identities.
 * execute must return the actual completion/negative errno after releasing its
 * borrow. An admitted effect may not throw or return before its memory is safe.
 * Kernel-unsupported operations are POSIX-emulated against the same OS fd.
 */
internal fun nodeNativeChannelBackend(module: dynamic, handle: dynamic): UserspaceChannelBackend =
    NodeNativeChannelBackend(module, handle)

private class NodeNativeChannelBackend(private val module: dynamic, private val handle: dynamic) : UserspaceChannelBackend {
    override val capabilities = UringOp.caps(UringOp.NOP, UringOp.OPENAT, UringOp.READ,
        UringOp.WRITE, UringOp.FSYNC, UringOp.FTRUNCATE, UringOp.CLOSE) or
        (if (jsTypeOf(module.size) == "function" || jsTypeOf(nodeFs?.fstatSync) == "function") UringOp.STATX.mask else 0L)
    override val nativeCapabilities: Long = UringOp.entries.fold(0L) { mask, op ->
        if (op != UringOp.STATX && capabilities and op.mask != 0L && op.code >= 0 && module.supports(handle, op.code) as Boolean)
            mask or op.mask else mask
    }
    override val availability = "io_uring: Node ABI1 setup and operation probe succeeded; unavailable kernel ops use POSIX emulation"
    private val owned = mutableSetOf<Int>()
    private val emulated = JsUserspaceChannelBackend()
    private var closed = false

    private fun execute(sub: UringSubmission): Int {
        if (closed) return -9
        if (sub.flags != 0 || capabilities and sub.opcode.mask == 0L) return -95
        // ABI1 fixes OPENAT's creation mode at 0666 and carries no raw address.
        if (sub.addr != 0L || sub.operationFlags != (if (sub.opcode == UringOp.OPENAT) 438 else 0)) return -95
        if (sub.opcode == UringOp.OPENAT && sub.fd != -100) return -95
        val descriptor = JsFileTable.descriptor(sub.fd)
        if (descriptor != null && descriptor.module == null) return emulated.execute(sub)
        if (sub.opcode != UringOp.NOP && sub.opcode != UringOp.OPENAT && descriptor == null) return -9
        val buffer = sub.buffer
        if (sub.opcode == UringOp.STATX) {
            if (buffer == null || buffer.isReadOnly() || sub.len < 24 || sub.len > buffer.remaining() || sub.operationFlags != 0 || sub.offset != 0L || sub.addr != 0L) return -22
            return try {
                if (jsTypeOf(module.size) == "function") jsStatxResult(sub, module.size(descriptor!!.fd).toString().toLong(), 0L, 0L)
                else jsStatxResult(sub, nodeFs.fstatSync(descriptor!!.fd))
            } catch (failure: dynamic) { jsIoError(failure) }
        }
        if (sub.opcode == UringOp.READ || sub.opcode == UringOp.WRITE || sub.opcode == UringOp.OPENAT) {
            if (buffer == null || sub.len < 0 || sub.len > buffer.remaining()) return -22
            if (sub.opcode == UringOp.READ && buffer.isReadOnly()) return -22
            if (sub.opcode != UringOp.OPENAT && sub.offset < -1) return -22
        }
        // CLOSE removes the same ownership record used by FileImpl.close().
        if (sub.opcode == UringOp.CLOSE) { owned.remove(sub.fd); return JsFileTable.close(sub.fd, sub.userData) }
        val result = module.execute(handle, sub.opcode.code, descriptor?.fd ?: sub.fd,
            buffer?.array(), buffer?.let { it.arrayOffset() + it.position() } ?: 0,
            sub.len, nodeUringInteger(sub.offset), nodeUringInteger(sub.userData)) as Int
        if (result >= 0 && sub.opcode == UringOp.OPENAT)
            return JsFileTable.register(result, module, handle).also { owned.add(it) }
        if (result > 0 && (sub.opcode == UringOp.READ || sub.opcode == UringOp.WRITE))
            buffer!!.position(buffer.position() + result)
        return result
    }

    override fun submitBatch(submissions: List<UringSubmission>): List<SelectionResult> =
        submissions.map { SelectionResult(execute(it), it.userData) }
    override suspend fun batchEnqueue(submissions: Series<UringSubmission>): Series<UringCompletion> {
        val results = Array(submissions.size) { index ->
            val sub = submissions[index]
            UringCompletion(sub.userData, execute(sub), 0)
        }
        return results.size j { results[it] }
    }
    override fun close() {
        if (closed) return
        closed = true
        owned.forEach { JsFileTable.close(it) }
        owned.clear()
        emulated.close()
        module.close(handle)
    }
}

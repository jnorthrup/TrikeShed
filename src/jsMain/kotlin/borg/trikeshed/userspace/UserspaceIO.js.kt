package borg.trikeshed.userspace

import borg.trikeshed.lib.Series
import borg.trikeshed.lib.fs
import borg.trikeshed.lib.get
import borg.trikeshed.lib.j
import borg.trikeshed.lib.size
import borg.trikeshed.userspace.UringOp.Companion.UringSubmission
import borg.trikeshed.userspace.nio.ByteBuffer
import borg.trikeshed.userspace.nio.ByteOrder
import kotlin.js.jsTypeOf
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.CompletableDeferred
import org.khronos.webgl.Float64Array
import org.khronos.webgl.Int32Array
import org.khronos.webgl.get
import org.khronos.webgl.set

private val nodeFs: dynamic get() = runCatching { fs }.getOrNull()

/** ABI2 control fields: opcode, fd, start, length, exact offset, transport id. */
private const val NODE_BATCH_FIELDS = 6
/** SQEs per native submission transport call. */
private const val NODE_BATCH_LIMIT = 64
private const val NOT_BATCHED = Int.MIN_VALUE

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
        val descriptor = files[id] ?: return -9
        val result = try {
            if (descriptor.module == null) { nodeFs.closeSync(descriptor.fd); 0 }
            else descriptor.module.execute(descriptor.handle, UringOp.CLOSE.code, descriptor.fd,
                null, 0, 0, nodeUringInteger(0), nodeUringInteger(userData)) as Int
        } catch (failure: dynamic) { jsIoError(failure) }
        if (result != -16) files.remove(id)
        return result
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
/**
 * The descriptor form of STATX (an empty path with AT_EMPTY_PATH): the path's NUL, then struct statx
 * in host order. A path is -EOPNOTSUPP here.
 */
private fun jsStatxInvalid(sub: UringSubmission): Int? {
    val buffer = sub.buffer ?: return -22
    if (buffer.isReadOnly() || sub.addr != 0L || sub.offset !in 0L..0xffffffffL || buffer.remaining() - sub.len < 1 + UringOp.STATX_SIZE ||
        buffer.array()[buffer.arrayOffset() + buffer.position() + sub.len] != 0.toByte() ||
        sub.operationFlags and (UringOp.AT_SYMLINK_NOFOLLOW or UringOp.AT_EMPTY_PATH or 0x800).inv() != 0) return -22
    if (sub.len != 0 || sub.operationFlags and UringOp.AT_EMPTY_PATH == 0) return -95
    return null
}

private fun jsStatxResult(sub: UringSubmission, mask: Int, fill: (ByteBuffer) -> Unit): Int {
    jsStatxInvalid(sub)?.let { return it }
    val buffer = sub.buffer!!
    val statx = ByteBuffer.wrap(buffer.array(), buffer.arrayOffset() + buffer.position() + 1, UringOp.STATX_SIZE).order(ByteOrder.nativeOrder())
    for (at in 0 until UringOp.STATX_SIZE step 8) statx.putLong(at, 0L)
    statx.putInt(0x00, mask)
    fill(statx)
    return 0
}

private fun jsStatxResult(sub: UringSubmission, metadata: dynamic): Int = jsStatxResult(sub, 0x71f) { statx ->
    fun field(name: String): Long = metadata[name].toString().toLong()
    val linux = js("typeof process !== 'undefined' && process.platform === 'linux'") as Boolean
    fun major(dev: Long): Int = if (linux) (((dev ushr 8) and 0xfff) or ((dev ushr 32) and 0xfffff000L)).toInt() else ((dev ushr 24) and 0xff).toInt()
    fun minor(dev: Long): Int = if (linux) ((dev and 0xff) or ((dev ushr 12) and 0xffffff00L)).toInt() else (dev and 0xffffff).toInt()
    statx.putInt(0x04, field("blksize").toInt())
    statx.putInt(0x10, field("nlink").toInt())
    statx.putInt(0x14, field("uid").toInt())
    statx.putInt(0x18, field("gid").toInt())
    statx.putShort(0x1c, field("mode").toShort())
    statx.putLong(0x20, field("ino"))
    statx.putLong(0x28, field("size"))
    statx.putLong(0x30, field("blocks"))
    statx.putInt(0x80, major(field("rdev")))
    statx.putInt(0x84, minor(field("rdev")))
    statx.putInt(0x88, major(field("dev")))
    statx.putInt(0x8c, minor(field("dev")))
}

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
        if (sub.opcode == UringOp.STATX) jsStatxInvalid(sub)?.let { return it }
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

    private class Request(val submission: UringSubmission)
    private val pending = mutableListOf<Request>()
    private val active = mutableListOf<Request>()
    private val completed = mutableListOf<SelectionResult>()
    private var completion = CompletableDeferred<Unit>()
    private var dispatching = false
    override val deferredCapabilities get() = capabilities

    /** Host callbacks are the event transport; commonMain owns the coroutine and buffer admission. */
    private fun executeAsync(sub: UringSubmission, complete: (Int) -> Unit) {
        val invalid = validation(sub)
        if (invalid != 0 || sub.opcode == UringOp.NOP) { complete(invalid); return }
        try {
            when (sub.opcode) {
                UringOp.OPENAT -> host.open(sub.nodePath(), jsOpenFlags(host, sub.offset), sub.operationFlags,
                    { error: dynamic, fd: dynamic ->
                        complete(if (error != null) jsIoError(error)
                            else JsFileTable.register(fd as Int).also { owned.add(it) })
                    })
                UringOp.CLOSE -> {
                    val descriptor = JsFileTable.descriptor(sub.fd)!!
                    owned.remove(sub.fd)
                    if (descriptor.module != null) complete(JsFileTable.close(sub.fd, sub.userData))
                    else {
                        JsFileTable.remove(sub.fd)
                        host.close(descriptor.fd, { error: dynamic -> complete(if (error == null) 0 else jsIoError(error)) })
                    }
                }
                UringOp.READ, UringOp.WRITE -> {
                    val fd = JsFileTable.descriptor(sub.fd)!!.fd
                    val buffer = sub.buffer!!
                    val position = buffer.position()
                    val start = buffer.arrayOffset() + position
                    val offset = if (sub.offset == -1L) null else sub.offset.toDouble()
                    val callback = { error: dynamic, transferred: dynamic, _: dynamic ->
                        if (error != null) complete(jsIoError(error))
                        else {
                            val count = transferred as Int
                            buffer.position(position + count)
                            complete(count)
                        }
                    }
                    if (sub.opcode == UringOp.READ) host.read(fd, buffer.array(), start, sub.len, offset, callback)
                    else host.write(fd, buffer.array(), start, sub.len, offset, callback)
                }
                UringOp.STATX -> host.fstat(JsFileTable.descriptor(sub.fd)!!.fd, { error: dynamic, metadata: dynamic ->
                    complete(if (error != null) jsIoError(error) else try { jsStatxResult(sub, metadata) }
                        catch (failure: dynamic) { jsIoError(failure) })
                })
                UringOp.FSYNC -> host.fsync(JsFileTable.descriptor(sub.fd)!!.fd,
                    { error: dynamic -> complete(if (error == null) 0 else jsIoError(error)) })
                UringOp.FTRUNCATE -> host.ftruncate(JsFileTable.descriptor(sub.fd)!!.fd, sub.offset.toDouble(),
                    { error: dynamic -> complete(if (error == null) 0 else jsIoError(error)) })
                else -> complete(-95)
            }
        } catch (failure: dynamic) { complete(jsIoError(failure)) }
    }

    private fun dispatch() {
        if (dispatching) return
        dispatching = true
        try {
            var index = 0
            while (index < pending.size) {
                val request = pending[index]
                if (active.any { dependsOn(request.submission, it.submission) } ||
                    (0 until index).any { dependsOn(request.submission, pending[it].submission) }) { index++; continue }
                pending.removeAt(index)
                active.add(request)
                executeAsync(request.submission) { result ->
                    active.remove(request)
                    completed.add(SelectionResult(result, request.submission.userData))
                    completion.complete(Unit)
                    dispatch()
                }
            }
        } finally { dispatching = false }
    }

    override fun submitBatch(submissions: List<UringSubmission>): List<SelectionResult> =
        submissions.map { sub ->
            val result = if (active.any { dependsOn(sub, it.submission) } || pending.any { dependsOn(sub, it.submission) })
                -16 else execute(sub)
            SelectionResult(result, sub.userData)
        }

    override fun submitDeferredBatch(submissions: List<UringSubmission>): List<SelectionResult> {
        submissions.forEach { pending.add(Request(it)) }
        dispatch()
        return reapCompletions()
    }

    override fun reapCompletions(minComplete: Int): List<SelectionResult> = completed.toMutableList().also {
        completed.clear()
        if (completion.isCompleted) completion = CompletableDeferred()
    }

    override suspend fun awaitCompletions() {
        if (completed.isEmpty()) completion.await()
    }

    override fun cancelPending() {
        pending.forEach { completed.add(SelectionResult(-125, it.submission.userData)) }
        pending.clear()
        if (completed.isNotEmpty()) completion.complete(Unit)
        // Node fs callbacks cannot be revoked: their terminal callbacks retain the borrowed bytes until reap.
    }

    override suspend fun batchEnqueue(submissions: Series<UringSubmission>): Series<UringCompletion> {
        val results = submitDeferredBatch(List(submissions.size) { submissions[it] }).toMutableList()
        while (results.size < submissions.size) {
            awaitCompletions()
            results.addAll(reapCompletions())
        }
        return results.size j { UringCompletion(results[it].userData, results[it].res, 0) }
    }

    override fun close() {
        if (closed) return
        check(pending.isEmpty() && active.isEmpty()) { "Node file effects must drain before close" }
        closed = true
        owned.forEach { JsFileTable.close(it) }
        owned.clear()
    }
}

actual fun openUserspaceChannelBackend(entries: Int, mode: UringBackendMode?): UserspaceChannelBackend {
    require(entries > 0)
    val requested = mode ?: nodeUringMode()
    val discovery = discoverNodeUringBackend(entries, requested)
    check(requested != UringBackendMode.NATIVE || discovery.backend != null) {
        "Native Node io_uring required: ${discovery.report.description}"
    }
    val selected = discovery.backend ?: JsUserspaceChannelBackend()
    return object : UserspaceChannelBackend by selected {
        override val probeReport = discovery.report
        override val availability = discovery.report.description
    }
}

/** ABI2 separates admission, terminal CQ reaping and event-loop readiness.
 * The synchronous execute export retains ABI1's BigInt offset semantics for compatibility effects.
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
    override val availability = "io_uring: Node ABI2 submission, CQ reaping and event-loop wakeups; unavailable kernel ops use POSIX emulation"
    private val owned = mutableSetOf<Int>()
    private val emulated = JsUserspaceChannelBackend()
    private var closed = false
    override val deferredCapabilities get() = capabilities
    private val batched = nativeCapabilities.let { mask ->
        BooleanArray(UringOp.entries.size) { mask and UringOp.entries[it].mask != 0L }
    }
    private class Request(val submission: UringSubmission, val id: Int)
    private var nextId = 0
    private val pending = mutableListOf<Request>()
    private val active = mutableMapOf<Int, Request>()
    private val completed = mutableListOf<SelectionResult>()
    private val run = ArrayList<Request>(NODE_BATCH_LIMIT)
    private val runFds = IntArray(NODE_BATCH_LIMIT)
    private val control = Float64Array(NODE_BATCH_LIMIT * NODE_BATCH_FIELDS)
    private val arrays = arrayOfNulls<ByteArray>(NODE_BATCH_LIMIT)
    private val results = Int32Array(NODE_BATCH_LIMIT)
    private var dispatching = false

    private fun execute(sub: UringSubmission): Int {
        if (closed) return -9
        if (sub.flags != 0 || capabilities and sub.opcode.mask == 0L) return -95
        // Native OPENAT fixes creation mode at 0666 and carries no raw address.
        if (sub.addr != 0L || sub.operationFlags != (if (sub.opcode == UringOp.OPENAT) 438 else if (sub.opcode == UringOp.STATX) sub.operationFlags else 0)) return -95
        if (sub.opcode == UringOp.OPENAT && sub.fd != -100) return -95
        val descriptor = JsFileTable.descriptor(sub.fd)
        if (descriptor != null && descriptor.module == null) return emulated.execute(sub)
        if (sub.opcode != UringOp.NOP && sub.opcode != UringOp.OPENAT && descriptor == null) return -9
        val buffer = sub.buffer
        if (sub.opcode == UringOp.STATX) {
            jsStatxInvalid(sub)?.let { return it }
            return try {
                if (jsTypeOf(module.size) == "function") {
                    val size = module.size(descriptor!!.fd).toString().toLong()
                    if (size < 0) (if (size >= Int.MIN_VALUE.toLong()) size.toInt() else -5)
                    else jsStatxResult(sub, 0x200) { it.putLong(0x28, size) }
                } else jsStatxResult(sub, nodeFs.fstatSync(descriptor!!.fd))
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

    override fun submitBatch(submissions: List<UringSubmission>): List<SelectionResult> {
        submissions.forEach { sub ->
            do { nextId = if (nextId == Int.MAX_VALUE) 1 else nextId + 1 }
            while (nextId in active || pending.any { it.id == nextId })
            pending.add(Request(sub, nextId))
        }
        dispatch()
        return reapCompletions()
    }

    private fun dispatch() {
        if (dispatching) return
        dispatching = true
        try {
            var repeat: Boolean
            do {
                var index = 0
                while (index < pending.size) {
                    val request = pending[index]
                    val sub = request.submission
                    if (active.values.any { dependsOn(sub, it.submission) } ||
                        (0 until index).any { dependsOn(sub, pending[it].submission) }) { index++; continue }
                    val fd = batchFd(sub)
                    if (fd == NOT_BATCHED) {
                        flush()
                        // The synchronous compatibility ABI never shares a ring with outstanding effects.
                        if (active.isNotEmpty()) { index++; continue }
                        pending.removeAt(index)
                        completed.add(SelectionResult(execute(sub), sub.userData))
                    } else {
                        pending.removeAt(index)
                        runFds[run.size] = fd
                        run.add(request)
                        active[request.id] = request
                        if (run.size == NODE_BATCH_LIMIT) flush()
                    }
                }
                repeat = flush()
            } while (repeat && pending.isNotEmpty())
        } finally { dispatching = false }
    }

    /** BigInt-only offsets stay on execute; no narrowing a file position into Float64. */
    private fun batchFd(sub: UringSubmission): Int {
        if (closed || sub.flags != 0 || !batched[sub.opcode.ordinal] || sub.addr != 0L ||
            sub.operationFlags != (if (sub.opcode == UringOp.OPENAT) 438 else 0) ||
            sub.offset !in -9007199254740991L..9007199254740991L) return NOT_BATCHED
        if (sub.opcode == UringOp.OPENAT) {
            if (sub.fd != -100 || sub.buffer == null || sub.len <= 0 || sub.len > sub.buffer.remaining()) return NOT_BATCHED
            return sub.fd
        }
        val descriptor = JsFileTable.descriptor(sub.fd)
        if (descriptor != null && descriptor.module == null) return NOT_BATCHED
        if (sub.opcode == UringOp.NOP) return descriptor?.fd ?: sub.fd
        if (descriptor == null) return NOT_BATCHED
        if (sub.opcode == UringOp.READ || sub.opcode == UringOp.WRITE) {
            val buffer = sub.buffer ?: return NOT_BATCHED
            if (sub.len < 0 || sub.len > buffer.remaining() || sub.offset < -1) return NOT_BATCHED
            if (sub.opcode == UringOp.READ && buffer.isReadOnly()) return NOT_BATCHED
        }
        return descriptor.fd
    }

    /** Admit many independent SQEs and retain all Kotlin buffers through their terminal CQEs. */
    private fun flush(): Boolean {
        val count = run.size
        if (count == 0) return false
        for (index in 0 until count) {
            val request = run[index]
            val sub = request.submission
            val field = index * NODE_BATCH_FIELDS
            val buffer = sub.buffer
            control[field] = sub.opcode.code.toDouble()
            control[field + 1] = runFds[index].toDouble()
            control[field + 2] = if (buffer == null) 0.0 else (buffer.arrayOffset() + buffer.position()).toDouble()
            control[field + 3] = sub.len.toDouble()
            control[field + 4] = sub.offset.toDouble()
            control[field + 5] = request.id.toDouble()
            arrays[index] = buffer?.array()
        }
        val refused = module.submitBatch(handle, count, control, arrays, results) as Int
        var rejected = false
        for (index in 0 until count) {
            val request = run[index]
            arrays[index] = null
            val result = if (refused < 0) refused else results[index]
            if (result < 0) {
                active.remove(request.id)
                completed.add(SelectionResult(result, request.submission.userData))
                rejected = true
            }
        }
        run.clear()
        return rejected
    }

    override fun reapCompletions(minComplete: Int): List<SelectionResult> {
        val cqes: dynamic = module.reapCompletions(handle, if (completed.isEmpty() && active.isNotEmpty()) minComplete else 0)
        val length = cqes.length as Int
        var index = 0
        while (index < length) {
            val id = cqes[index++] as Int
            val result = cqes[index++] as Int
            val request = checkNotNull(active.remove(id)) { "Unknown Node uring completion: $id" }
            val sub = request.submission
            val res = when {
                result >= 0 && sub.opcode == UringOp.OPENAT ->
                    JsFileTable.register(result, module, handle).also { owned.add(it) }
                sub.opcode == UringOp.CLOSE -> {
                    owned.remove(sub.fd)
                    JsFileTable.remove(sub.fd)
                    result
                }
                else -> result.also {
                    if (it > 0 && (sub.opcode == UringOp.READ || sub.opcode == UringOp.WRITE))
                        sub.buffer!!.position(sub.buffer.position() + it)
                }
            }
            completed.add(SelectionResult(res, sub.userData))
        }
        dispatch()
        return completed.toMutableList().also { completed.clear() }
    }

    override suspend fun awaitCompletions() {
        if (completed.isNotEmpty()) return
        suspendCancellableCoroutine<Unit> { continuation ->
            val promise: dynamic = module.awaitCompletions(handle)
            continuation.invokeOnCancellation { module.cancelWait(handle) }
            promise.then({ _: dynamic ->
                if (continuation.isActive) continuation.resume(Unit)
                null
            }, { failure: dynamic ->
                if (continuation.isActive) continuation.resumeWithException(IllegalStateException(failure.toString() as String))
                null
            })
        }
    }

    override fun cancelPending() {
        pending.forEach { completed.add(SelectionResult(-125, it.submission.userData)) }
        pending.clear()
        module.cancelPending(handle)
    }

    override suspend fun batchEnqueue(submissions: Series<UringSubmission>): Series<UringCompletion> {
        val results = submitBatch(List(submissions.size) { submissions[it] }).toMutableList()
        while (results.size < submissions.size) {
            awaitCompletions()
            results.addAll(reapCompletions())
        }
        return results.size j { UringCompletion(results[it].userData, results[it].res, 0) }
    }
    override fun close() {
        if (closed) return
        check(pending.isEmpty() && active.isEmpty()) { "Node uring effects must drain before close" }
        closed = true
        owned.forEach { JsFileTable.close(it) }
        owned.clear()
        emulated.close()
        check((module.close(handle) as? Int ?: 0) >= 0) { "Node uring close refused" }
    }
}

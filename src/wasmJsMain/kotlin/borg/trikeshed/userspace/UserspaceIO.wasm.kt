@file:OptIn(UnsafeWasmMemoryApi::class, ExperimentalWasmJsInterop::class)

package borg.trikeshed.userspace

import borg.trikeshed.lib.Series
import borg.trikeshed.lib.get
import borg.trikeshed.lib.j
import borg.trikeshed.lib.size
import borg.trikeshed.userspace.UringOp.Companion.UringSubmission
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.wasm.unsafe.Pointer
import kotlin.wasm.unsafe.UnsafeWasmMemoryApi
import kotlin.wasm.unsafe.wasmMemory
import kotlin.wasm.unsafe.withScopedMemoryAllocator

actual class FileImpl actual constructor(actual val id: Int) {
    actual fun isOpen(): Boolean = false
    actual fun close() {}
    actual fun size(): Long = -1L
}

internal actual object FilesImpl {
    actual fun open(path: String, readOnly: Boolean): FileImpl =
        throw UnsupportedOperationException("Wasm host file primitives are unavailable")
}

internal actual object ChannelsImpl {
    actual fun socket(domain: Int, type: Int, protocol: Int): FileImpl =
        throw UnsupportedOperationException("Wasm host socket primitives are unavailable")
}

/** Node's `fs` module when this module runs under Node (22.3 or later: `process.getBuiltinModule`); null in a browser. */
@JsFun("""() => (typeof process === 'object' && process.versions && process.versions.node
    && typeof process.getBuiltinModule === 'function') ? process.getBuiltinModule('node:fs') : null""")
internal external fun wasmNodeFs(): JsAny?

/**
 * One SQE through Node's synchronous `fs`: op 0 OPENAT (Linux O_ flags in [flags], mapped to the host's constants),
 * 1 READ, 2 WRITE ([len] bytes at linear-memory [addr]; [pos] < 0 is the file position), 3 FSYNC, 4 CLOSE. Returns the
 * result, or -errno with Linux numbers (UserspaceIO.js.kt's table).
 */
@JsFun("""(fs, mem, op, fd, addr, len, pos, path, flags, mode) => {
    try {
        switch (op) {
            case 0: {
                const c = fs.constants, a = flags & 3;
                let f = a === 0 ? c.O_RDONLY : a === 1 ? c.O_WRONLY : c.O_RDWR;
                if (flags & 64) f |= c.O_CREAT;
                if (flags & 128) f |= c.O_EXCL;
                if (flags & 512) f |= c.O_TRUNC;
                return fs.openSync(path, f, mode);
            }
            case 1: return fs.readSync(fd, new Uint8Array(mem.buffer, addr, len), 0, len, pos < 0 ? null : pos);
            case 2: return fs.writeSync(fd, new Uint8Array(mem.buffer, addr, len), 0, len, pos < 0 ? null : pos);
            case 3: fs.fsyncSync(fd); return 0;
            case 4: fs.closeSync(fd); return 0;
        }
        return -95;
    } catch (e) {
        const E = { ENOENT: 2, EINTR: 4, EIO: 5, EBADF: 9, EAGAIN: 11, EWOULDBLOCK: 11, EACCES: 13, EPERM: 13,
            EFAULT: 14, EEXIST: 17, ENOTDIR: 20, EISDIR: 21, EINVAL: 22, ERR_OUT_OF_RANGE: 22,
            ERR_INVALID_ARG_TYPE: 22, ENFILE: 23, EMFILE: 24, EFBIG: 27, ENOSPC: 28, EROFS: 30, ENAMETOOLONG: 36,
            ELOOP: 40, EOPNOTSUPP: 95, ENOSYS: 95 };
        return -(E[e && e.code] || 5);
    }
}""")
internal external fun wasmNodeFsCall(fs: JsAny, mem: JsAny, op: Int, fd: Int, addr: Int, len: Int, pos: Double,
    path: String?, flags: Int, mode: Int): Int

/** Linear memory -> [dst][[at], [at] + [len]): four bytes per load where the run allows. */
internal fun Pointer.copyTo(dst: ByteArray, at: Int, len: Int) {
    var i = 0
    while (i + 4 <= len) {
        val v = (this + i).loadInt()
        dst[at + i] = v.toByte(); dst[at + i + 1] = (v shr 8).toByte(); dst[at + i + 2] = (v shr 16).toByte(); dst[at + i + 3] = (v shr 24).toByte()
        i += 4
    }
    while (i < len) { dst[at + i] = (this + i).loadByte(); i++ }
}

/** [src][[at], [at] + [len]) -> linear memory: four bytes per store where the run allows. */
internal fun Pointer.copyFrom(src: ByteArray, at: Int, len: Int) {
    var i = 0
    while (i + 4 <= len) {
        (this + i).storeInt((src[at + i].toInt() and 0xff) or ((src[at + i + 1].toInt() and 0xff) shl 8) or
            ((src[at + i + 2].toInt() and 0xff) shl 16) or (src[at + i + 3].toInt() shl 24))
        i += 4
    }
    while (i < len) { (this + i).storeByte(src[at + i]); i++ }
}

/**
 * The wasmJs ring: an emulated ring that, under Node, services each SQE with one synchronous `fs` call before
 * submitBatch returns (the wasmWasi ring's shape; descriptors are Node's own). READ and WRITE move their bytes through
 * a scoped linear-memory window that Node reads or fills in place. In a browser there is no file host: NOP only.
 */
internal class WasmUserspaceChannelBackend(val fs: JsAny?) : UserspaceChannelBackend {
    override val capabilities: Long = if (fs == null) UringOp.NOP.mask
        else UringOp.caps(UringOp.NOP, UringOp.OPENAT, UringOp.READ, UringOp.WRITE, UringOp.FSYNC, UringOp.CLOSE)
    override val availability: String get() = if (fs == null) "emulated: restricted Wasm host; file primitives unavailable"
        else "emulated: Node fs from wasmJs; no kernel ring"
    var closed = false

    fun execute(sub: UringSubmission): Int {
        if (closed) return -9
        if (sub.flags != 0 || capabilities and sub.opcode.mask == 0L) return -95
        val fs = fs ?: return 0
        return when (sub.opcode) {
            UringOp.NOP -> 0
            UringOp.OPENAT -> {
                if (sub.fd != UringOp.AT_FDCWD) return -95
                val flags = sub.offset
                if (flags < 0 || flags and (3L or 64L or 128L or 512L).inv() != 0L || flags and 3L == 3L) return -22
                if (sub.operationFlags !in 0..511) return -22
                val bytes = sub.buffer ?: return -14
                if (sub.len <= 0 || sub.len > bytes.remaining()) return -22
                val start = bytes.arrayOffset() + bytes.position()
                val path = bytes.array().decodeToString(start, start + sub.len)
                if ('\u0000' in path) return -22
                wasmNodeFsCall(fs, wasmMemory, 0, -1, 0, 0, -1.0, path, flags.toInt(), sub.operationFlags)
            }
            UringOp.READ, UringOp.WRITE -> {
                val buffer = sub.buffer ?: return -14
                if (sub.len < 0 || sub.len > buffer.remaining() || sub.offset < -1L) return -22
                if (sub.opcode == UringOp.READ && buffer.isReadOnly()) return -22
                val start = buffer.arrayOffset() + buffer.position()
                val n = withScopedMemoryAllocator { a ->
                    val window = a.allocate(maxOf(sub.len, 1))
                    if (sub.opcode == UringOp.WRITE) {
                        window.copyFrom(buffer.array(), start, sub.len)
                        wasmNodeFsCall(fs, wasmMemory, 2, sub.fd, window.address.toInt(), sub.len, sub.offset.toDouble(), null, 0, 0)
                    } else wasmNodeFsCall(fs, wasmMemory, 1, sub.fd, window.address.toInt(), sub.len, sub.offset.toDouble(), null, 0, 0)
                        .also { if (it > 0) window.copyTo(buffer.array(), start, it) }
                }
                if (n > 0) buffer.position(buffer.position() + n)
                n
            }
            UringOp.FSYNC -> wasmNodeFsCall(fs, wasmMemory, 3, sub.fd, 0, 0, -1.0, null, 0, 0)
            UringOp.CLOSE -> wasmNodeFsCall(fs, wasmMemory, 4, sub.fd, 0, 0, -1.0, null, 0, 0)
            else -> -95
        }
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

    override fun close() { closed = true }
}

actual fun openUserspaceChannelBackend(entries: Int, mode: UringBackendMode?): UserspaceChannelBackend {
    require(entries > 0)
    return WasmUserspaceChannelBackend(wasmNodeFs())
}

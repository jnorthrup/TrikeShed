@file:OptIn(UnsafeWasmMemoryApi::class, ExperimentalWasmInterop::class)

package borg.trikeshed.tilting.leaf

import borg.trikeshed.tilting.lz4.Lz4BlockOps
import borg.trikeshed.tilting.lzo.LzoBlockOps
import kotlin.wasm.ExperimentalWasmInterop
import kotlin.wasm.WasmImport
import kotlin.wasm.unsafe.UnsafeWasmMemoryApi
import kotlin.wasm.unsafe.withScopedMemoryAllocator

/** WASI has no C library to bind: the kotlin implementations are the platform's. */
actual object LeafSpecialization {
    actual val platform: String get() = "wasi"
    actual fun lz4(): Lz4BlockOps? = null
    actual fun lzo(): LzoBlockOps? = null
}

@WasmImport("wasi_snapshot_preview1", "path_open")
private external fun wasiPathOpen(
    fd: Int, dirflags: Int, pathPtr: Int, pathLen: Int, oflags: Int,
    rightsBase: Long, rightsInheriting: Long, fdflags: Int, resultPtr: Int,
): Int

@WasmImport("wasi_snapshot_preview1", "fd_read")
private external fun wasiFdRead(fd: Int, iovsPtr: Int, iovsLen: Int, nreadPtr: Int): Int

@WasmImport("wasi_snapshot_preview1", "fd_close")
private external fun wasiFdClose(fd: Int): Int

/** Reads [name] from the first preopened directory (fd 3, e.g. `wasmtime run --dir <host dir>::/data`). */
fun readPreopened(name: String): ByteArray = withScopedMemoryAllocator { a ->
    val path = name.encodeToByteArray()
    val p = a.allocate(path.size)
    for (i in path.indices) (p + i).storeByte(path[i])
    val fdOut = a.allocate(4)
    val opened = wasiPathOpen(3, 0, p.address.toInt(), path.size, 0, 2L /* FD_READ */, 0L, 0, fdOut.address.toInt())
    check(opened == 0) { "path_open($name) errno=$opened" }
    val fd = fdOut.loadInt()
    val chunk = 1 shl 20
    val buf = a.allocate(chunk)
    val iov = a.allocate(8)
    val nread = a.allocate(4)
    var out = ByteArray(chunk)
    var size = 0
    while (true) {
        iov.storeInt(buf.address.toInt())
        (iov + 4).storeInt(chunk)
        val read = wasiFdRead(fd, iov.address.toInt(), 1, nread.address.toInt())
        check(read == 0) { "fd_read errno=$read" }
        val n = nread.loadInt()
        if (n == 0) break
        if (size + n > out.size) out = out.copyOf(maxOf(out.size * 2, size + n))
        for (i in 0 until n) out[size + i] = (buf + i).loadByte()
        size += n
    }
    wasiFdClose(fd)
    out.copyOf(size)
}

/** The permutation's wasi cells: `wasmtime run --dir <dir holding corpus-4k.bin>::/data trikeshed.wasm`. */
fun main() {
    leafPermutation(readPreopened("corpus-4k.bin")) { println(it) }
}

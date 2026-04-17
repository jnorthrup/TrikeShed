package borg.trikeshed.common

import borg.trikeshed.lib.ByteSeries
import borg.trikeshed.lib.Join
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.Series2

private object WasmNoopSeekHandle : SeekHandle {
    override fun open(filename: String, readOnly: Boolean): Long = 0L

    override fun close(handle: Long) {
        // no-op
    }

    override fun pread(handle: Long, buf: ByteArray, offset: Int, length: Int, fileOffset: Long): Int = -1

    override fun size(handle: Long): Long = 0L

    override fun read(handle: Long, buf: ByteArray, offset: Int, length: Int): Int = -1

    override fun seek(handle: Long, position: Long): Long = position.coerceAtLeast(0)
}

actual object System {
//  System  actual fun getenv(name: String, string: String): String? = null

/*    actual val homedir: String
        get() = "/"*/

    actual fun getenv(name: String, defaultVal: String?): String? {
        // Try Node.js process.env, then Deno.env.get, then a user-provided global map __trikeshedEnv
        val g: dynamic = js("typeof globalThis !== 'undefined' ? globalThis : null")
        try {
            val process: dynamic = js("typeof process !== 'undefined' ? process : null")
            if (process != null) {
                val envObj: dynamic = process.env
                if (envObj != null) {
                    val v = envObj[name]
                    if (v != null) return v as String
                }
            }
        } catch (_: Throwable) {
        }

        try {
            val deno: dynamic = js("typeof Deno !== 'undefined' ? Deno : null")
            if (deno != null) {
                val v = deno.env?.get(name)
                if (v != null) return v as String
            }
        } catch (_: Throwable) {
        }

        try {
            if (g != null) {
                val envMap: dynamic = g.__trikeshedEnv
                if (envMap != null) {
                    val v = envMap[name]
                    if (v != null) return v as String
                }
            }
        } catch (_: Throwable) {
        }

        return defaultVal
    }

    // Runtime helpers to configure a process-like env map for WASM/JS contexts
    fun setEnv(name: String, value: String?) {
        val g: dynamic = js("typeof globalThis !== 'undefined' ? globalThis : null")
        if (g != null) {
            var envMap: dynamic = g.__trikeshedEnv
            if (envMap == null) {
                envMap = js("{}")
                g.__trikeshedEnv = envMap
            }
            envMap[name] = value
        }
    }

    fun setEnvMap(map: dynamic) {
        val g: dynamic = js("typeof globalThis !== 'undefined' ? globalThis : null")
        if (g != null) {
            g.__trikeshedEnv = map
        }
    }

    fun clearEnv() {
        val g: dynamic = js("typeof globalThis !== 'undefined' ? globalThis : null")
        if (g != null) {
            g.__trikeshedEnv = js("{}")
        }
    }

    actual val homedir: String
        get() {
            // Prefer HOME, then USERPROFILE (Windows), else use root as a safe fallback for WASM
            return getenv("HOME", getenv("USERPROFILE", "/")) ?: "/"
        }
}

actual object Files {
    actual fun readAllLines(filename: String): List<String> = emptyList()

    actual fun readAllBytes(filename: String): ByteArray = ByteArray(0)

    actual fun readString(filename: String): String = ""

    actual fun write(filename: String, bytes: ByteArray) {
        // no-op
    }

    actual fun write(filename: String, lines: List<String>) {
        // no-op
    }

    actual fun write(filename: String, string: String) {
        // no-op
    }

    actual fun cwd(): String = "/"

    actual fun exists(filename: String): Boolean = false

    actual fun streamLines(fileName: String, bufsize: Int): Sequence<Join<Long, ByteArray>> = emptySequence()

    actual fun iterateLines(fileName: String, bufsize: Int): Iterable<Join<Long, Series<Byte>>> = emptyList()
}

actual fun mktemp(): String = "/tmp/wasm"

actual fun rm(path: String): Boolean = false

actual fun mkdir(path: String): Boolean = false

actual fun readLinesSeq(path: String): Sequence<String> = Files.readAllLines(path).asSequence()

actual fun readLines(path: String): List<String> = Files.readAllLines(path)

actual fun platformSeekHandle(): SeekHandle = WasmNoopSeekHandle

actual fun ioUringHandle(): SeekHandle? = null

actual class FileBuffer actual constructor(
    actual val filename: String,
    actual val initialOffset: Long,
    actual val blkSize: Long,
    actual val readOnly: Boolean,
    actual val closeChannelOnMap: Boolean,
) : LongSeries<Byte> {
    private val delegate = SeekFileBufferCommon(filename, initialOffset, blkSize, readOnly, WasmNoopSeekHandle)

    actual override val a: Long
        get() = delegate.a

    actual override val b: (Long) -> Byte
        get() = delegate.b

    actual fun close() {
        delegate.close()
    }

    actual fun open() {
        delegate.open()
    }

    actual fun isOpen(): Boolean = delegate.isOpen()

    actual fun size(): Long = delegate.size()

    actual fun get(index: Long): Byte = delegate.get(index)

    actual fun put(index: Long, value: Byte) {
        delegate.put(index, value)
    }
}

actual class SeekFileBuffer actual constructor(
    actual val filename: String,
    actual val initialOffset: Long,
    actual val blkSize: Long,
    actual val readOnly: Boolean,
) : LongSeries<Byte> {
    private val delegate = SeekFileBufferCommon(filename, initialOffset, blkSize, readOnly, WasmNoopSeekHandle)

    actual override val a: Long
        get() = delegate.a

    actual override val b: (Long) -> Byte
        get() = delegate.b

    actual fun close() {
        delegate.close()
    }

    actual fun open() {
        delegate.open()
    }

    actual fun isOpen(): Boolean = delegate.isOpen()

    actual fun size(): Long = delegate.size()

    actual fun get(index: Long): Byte = delegate.get(index)

    actual fun seek(pos: Long) {
        delegate.seek(pos)
    }

    actual fun put(index: Long, value: Byte) {
        delegate.put(index, value)
    }

    actual fun readv(requests: Series2<Long, ByteSeries>): IntArray = delegate.readv(requests)
}

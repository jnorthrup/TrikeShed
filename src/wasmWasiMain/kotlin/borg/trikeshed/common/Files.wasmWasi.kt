package borg.trikeshed.common

import borg.trikeshed.lib.Join
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.j
import borg.trikeshed.lib.toSeries
import borg.trikeshed.userspace.UringOp
import borg.trikeshed.userspace.wasi.Wasi
import kotlin.random.Random

/** Files through WASI preview 1: paths resolve through the preopened directories (`wasmtime run --dir <host>::<guest>`). */
actual object Files {
    actual fun readAllLines(filename: String): List<String> = readString(filename).lines()

    actual fun readAllBytes(filename: String): ByteArray {
        val fd = Wasi.open(filename.encodeToByteArray(), UringOp.O_RDONLY)
        check(fd >= 0) { "open($filename) errno=${-fd}" }
        try {
            var out = ByteArray((Wasi.stat(fd)?.size ?: 0L).toInt().coerceAtLeast(4096))
            var size = 0
            while (true) {
                if (size == out.size) out = out.copyOf(out.size * 2)
                val n = Wasi.pread(fd, out, size, out.size - size, size.toLong())
                check(n >= 0) { "read($filename) errno=${-n}" }
                if (n == 0) break
                size += n
            }
            return out.copyOf(size)
        } finally {
            Wasi.close(fd)
        }
    }

    actual fun readString(filename: String): String = readAllBytes(filename).decodeToString()

    actual fun write(filename: String, bytes: ByteArray) {
        val fd = Wasi.open(filename.encodeToByteArray(), UringOp.O_WRONLY or UringOp.O_CREAT or UringOp.O_TRUNC)
        check(fd >= 0) { "open($filename) errno=${-fd}" }
        try {
            var at = 0
            while (at < bytes.size) {
                val n = Wasi.pwrite(fd, bytes, at, bytes.size - at, at.toLong())
                check(n > 0) { "write($filename) errno=${-n}" }
                at += n
            }
        } finally {
            Wasi.close(fd)
        }
    }

    actual fun write(filename: String, lines: List<String>) = write(filename, lines.joinToString("\n"))
    actual fun write(filename: String, string: String) = write(filename, string.encodeToByteArray())
    actual fun cwd(): String = Wasi.cwd
    actual fun exists(filename: String): Boolean = Wasi.stat(filename) != null
    actual fun lastModified(filename: String): Long = (Wasi.stat(filename)?.mtimNanos ?: 0L) / 1_000_000L

    actual fun streamLines(fileName: String, bufsize: Int): Sequence<Join<Long, ByteArray>> = sequence {
        val bytes = readAllBytes(fileName)
        var start = 0
        for (index in bytes.indices) if (bytes[index] == '\n'.code.toByte()) {
            yield(start.toLong() j bytes.copyOfRange(start, index + 1))
            start = index + 1
        }
        if (start < bytes.size) yield(start.toLong() j bytes.copyOfRange(start, bytes.size))
    }

    actual fun iterateLines(fileName: String, bufsize: Int): Iterable<Join<Long, Series<Byte>>> =
        streamLines(fileName, bufsize).map { (offset, bytes) -> offset j bytes.toSeries() }.asIterable()

    actual fun listDir(path: String): List<String> = Wasi.list(path).map { resolvePath(path, it) }
    actual fun isDir(path: String): Boolean = Wasi.stat(path)?.isDirectory == true
    actual fun isFile(path: String): Boolean = Wasi.stat(path)?.isRegular == true

    actual fun mkdirs(path: String) {
        var prefix = if (path.startsWith("/")) "" else "."
        for (part in path.split('/').filter { it.isNotEmpty() }) {
            prefix = if (prefix.isEmpty()) "/$part" else "$prefix/$part"
            if (!isDir(prefix)) Wasi.mkdir(prefix)
        }
    }

    actual fun deleteRecursively(path: String) {
        val stat = Wasi.stat(path) ?: return
        if (stat.isDirectory) {
            for (child in Wasi.list(path)) deleteRecursively(resolvePath(path, child))
            Wasi.rmdir(path)
        } else Wasi.unlink(path)
    }

    actual fun resolvePath(vararg parts: String): String =
        parts.filter { it.isNotEmpty() }.joinToString("/").replace(Regex("/+"), "/")

    actual fun readZip(path: String): List<Join<String, ByteArray>> = throw UnsupportedOperationException("readZip on WASI")

    actual fun createTempDir(prefix: String): String {
        val dir = resolvePath(cwd(), "$prefix${Random.nextLong().toULong().toString(36)}")
        mkdirs(dir)
        return dir
    }

    actual fun rename(src: String, dst: String): Boolean = Wasi.rename(src, dst) == 0
}

actual val homedirGet: String get() = Wasi.environment["HOME"] ?: Wasi.cwd

actual fun mktemp(): String = Files.createTempDir("tmp")

actual fun rm(path: String): Boolean = Files.exists(path).also { if (it) Files.deleteRecursively(path) }

actual fun mkdir(path: String): Boolean { Files.mkdirs(path); return Files.isDir(path) }

actual fun readLinesSeq(path: String): Sequence<String> = Files.readString(path).lineSequence()

actual fun readLines(path: String): List<String> = Files.readAllLines(path)

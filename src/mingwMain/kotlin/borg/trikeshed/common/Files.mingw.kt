@file:OptIn(ExperimentalForeignApi::class)

package borg.trikeshed.common

import borg.trikeshed.lib.Join
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.j
import borg.trikeshed.lib.toSeries
import kotlinx.cinterop.*
import platform.posix.*
import platform.windows.CreateDirectoryA
import platform.windows.DeleteFileA
import platform.windows.FILE_ATTRIBUTE_DIRECTORY
import platform.windows.FindClose
import platform.windows.FindFirstFileA
import platform.windows.FindNextFileA
import platform.windows.GetFileAttributesA
import platform.windows.GetTempPathA
import platform.windows.INVALID_FILE_ATTRIBUTES
import platform.windows.INVALID_HANDLE_VALUE
import platform.windows.RemoveDirectoryA
import platform.windows.WIN32_FIND_DATAA
import kotlin.random.Random

/**
 * Files on Windows: the C runtime for bytes (binary mode, so no CRLF translation), Win32 for directories. Paths take
 * either separator; [resolvePath] joins with '/', which every call here accepts.
 */
actual object Files {
    actual fun readAllLines(filename: String): List<String> =
        readString(filename).replace("\r\n", "\n").split('\n').let { parts ->
            if (parts.isNotEmpty() && parts.last().isEmpty()) parts.dropLast(1) else parts
        }

    actual fun readAllBytes(filename: String): ByteArray {
        val file = fopen(filename, "rb") ?: throw IllegalArgumentException("fopen($filename) failed")
        try {
            require(_fseeki64(file, 0, SEEK_END) == 0) { "fseek($filename, SEEK_END) failed" }
            val size = _ftelli64(file)
            require(size >= 0) { "ftell($filename) failed" }
            require(_fseeki64(file, 0, SEEK_SET) == 0) { "fseek($filename, SEEK_SET) failed" }
            if (size == 0L) return ByteArray(0)
            val bytes = ByteArray(size.toInt())
            val read = bytes.usePinned { fread(it.addressOf(0), 1u, size.toULong(), file) }
            return if (read.toLong() == size) bytes else bytes.copyOf(read.toInt())
        } finally {
            fclose(file)
        }
    }

    actual fun readString(filename: String): String = readAllBytes(filename).decodeToString()

    actual fun write(filename: String, bytes: ByteArray) {
        val file = fopen(filename, "wb") ?: throw IllegalArgumentException("fopen($filename) failed")
        try {
            if (bytes.isNotEmpty()) {
                val written = bytes.usePinned { fwrite(it.addressOf(0), 1u, bytes.size.toULong(), file) }
                require(written.toInt() == bytes.size) { "fwrite($filename) failed" }
            }
        } finally {
            fclose(file)
        }
    }

    actual fun write(filename: String, lines: List<String>) = write(filename, lines.joinToString("\n"))
    actual fun write(filename: String, string: String) = write(filename, string.encodeToByteArray())

    actual fun cwd(): String = memScoped {
        val buf = allocArray<ByteVar>(4096)
        _getcwd(buf, 4096)?.toKString() ?: ""
    }

    actual fun exists(filename: String): Boolean = GetFileAttributesA(filename) != INVALID_FILE_ATTRIBUTES

    actual fun lastModified(filename: String): Long = memScoped {
        val st = alloc<_stat64>()
        if (_stat64(filename, st.ptr) == 0) st.st_mtime * 1000L else 0L
    }

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

    /** Entry names (not paths), without "." and "..", as the POSIX form answers. */
    actual fun listDir(path: String): List<String> = memScoped {
        val data = alloc<WIN32_FIND_DATAA>()
        val handle = FindFirstFileA("$path/*", data.ptr)
        if (handle == null || handle.rawValue == INVALID_HANDLE_VALUE?.rawValue) return@memScoped emptyList()
        val names = mutableListOf<String>()
        try {
            do {
                val name = data.cFileName.toKString()
                if (name != "." && name != "..") names.add(name)
            } while (FindNextFileA(handle, data.ptr) != 0)
        } finally {
            FindClose(handle)
        }
        names
    }

    actual fun isDir(path: String): Boolean {
        val attributes = GetFileAttributesA(path)
        return attributes != INVALID_FILE_ATTRIBUTES && attributes and FILE_ATTRIBUTE_DIRECTORY.toUInt() != 0u
    }

    actual fun isFile(path: String): Boolean {
        val attributes = GetFileAttributesA(path)
        return attributes != INVALID_FILE_ATTRIBUTES && attributes and FILE_ATTRIBUTE_DIRECTORY.toUInt() == 0u
    }

    /** Every missing directory along [path]. */
    actual fun mkdirs(path: String) {
        val parts = path.replace('\\', '/').split('/')
        var prefix = ""
        for (part in parts) {
            prefix = if (prefix.isEmpty() && path.startsWith("/")) "/$part" else if (prefix.isEmpty()) part else "$prefix/$part"
            if (part.isEmpty() || part.endsWith(":")) continue
            if (!isDir(prefix)) CreateDirectoryA(prefix, null)
        }
    }

    actual fun deleteRecursively(path: String) {
        if (!exists(path)) return
        if (isDir(path)) {
            for (name in listDir(path)) deleteRecursively("$path/$name")
            RemoveDirectoryA(path)
        } else DeleteFileA(path)
    }

    actual fun resolvePath(vararg parts: String): String = parts.joinToString("/")

    actual fun readZip(path: String): List<Join<String, ByteArray>> = throw UnsupportedOperationException("readZip unsupported")

    /** A new directory under the user's temporary directory (GetTempPathA). */
    actual fun createTempDir(prefix: String): String = memScoped {
        val buf = allocArray<ByteVar>(261)
        val n = GetTempPathA(261u, buf).toInt()
        check(n in 1..260) { "GetTempPathA failed" }
        val base = buf.toKString().trimEnd('\\', '/')
        var dir: String
        do {
            dir = "$base/$prefix-${Random.nextLong().toULong().toString(36)}"
            val made = CreateDirectoryA(dir, null) != 0
            if (!made) check(exists(dir)) { "CreateDirectoryA($dir) failed" }
        } while (!made)
        dir
    }

    actual fun rename(src: String, dst: String): Boolean = platform.posix.rename(src, dst) == 0
}

/** emulates shell command */
actual fun mktemp(): String = Files.createTempDir("tmp")

actual fun rm(path: String): Boolean = remove(path) == 0

actual fun mkdir(path: String): Boolean = CreateDirectoryA(path, null) != 0

actual fun readLinesSeq(path: String): Sequence<String> = Files.readAllLines(path).asSequence()

actual fun readLines(path: String): List<String> = Files.readAllLines(path)

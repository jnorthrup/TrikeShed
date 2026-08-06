package borg.trikeshed.common

import borg.trikeshed.lib.Join
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.j
import borg.trikeshed.lib.toSeries
import borg.trikeshed.lib.jsCwd
import borg.trikeshed.lib.jsExists
import borg.trikeshed.lib.jsMkdir
import borg.trikeshed.lib.jsReadBytes
import borg.trikeshed.lib.jsReadString
import borg.trikeshed.lib.jsRm
import borg.trikeshed.lib.jsWriteBytes
import borg.trikeshed.lib.jsWriteString
import kotlin.random.Random
import borg.trikeshed.lib.fs
import borg.trikeshed.lib.path
import borg.trikeshed.lib.os

fun streamByteLines(bytes: ByteArray): Sequence<Join<Long, ByteArray>> = sequence {
    var offset = 0L
    var lineStart = 0L
    val line = ArrayList<Byte>()

    for (byte in bytes) {
        line += byte
        offset++
        if (byte == '\n'.code.toByte()) {
            yield(lineStart j line.toByteArray())
            line.clear()
            lineStart = offset
        }
    }

    if (line.isNotEmpty()) {
        yield(lineStart j line.toByteArray())
    }
}

actual object Files {
    actual fun readAllLines(filename: String): List<String> =
        jsReadString(filename).replace("\r\n", "\n").split('\n').let { parts ->
            if (parts.isNotEmpty() && parts.last().isEmpty()) parts.dropLast(1) else parts
        }

    actual fun readAllBytes(filename: String): ByteArray = jsReadBytes(filename)

    actual fun readString(filename: String): String = jsReadString(filename)

    actual fun write(filename: String, bytes: ByteArray) {
        jsWriteBytes(filename, bytes)
    }

    actual fun write(filename: String, lines: List<String>) {
        write(filename, lines.joinToString("\n"))
    }

    actual fun write(filename: String, string: String) {
        jsWriteString(filename, string)
    }

    actual fun cwd(): String = jsCwd()

    actual fun exists(filename: String): Boolean = jsExists(filename)

    actual fun streamLines(fileName: String, bufsize: Int): Sequence<Join<Long, ByteArray>> =
        streamByteLines(readAllBytes(fileName))

    actual fun iterateLines(fileName: String, bufsize: Int): Iterable<Join<Long, Series<Byte>>> =
        streamLines(fileName, bufsize).map { (offset, bytes) -> offset j bytes.toSeries() }.asIterable()

    actual fun listDir(path: String): List<String> {
        val entries: dynamic = fs.readdirSync(path)
        val result = mutableListOf<String>()
        val length = entries.length as Int
        for (i in 0 until length) result.add(entries[i] as String)
        return result
    }

    actual fun isDir(path: String): Boolean {
        val stat: dynamic = fs.statSync(path)
        return (stat.isDirectory() as Boolean)
    }

    actual fun isFile(path: String): Boolean {
        val stat: dynamic = fs.statSync(path)
        return (stat.isFile() as Boolean)
    }

    actual fun mkdirs(path: String) { jsMkdir(path) }

    actual fun deleteRecursively(path: String) { jsRm(path) }

    actual fun resolvePath(vararg parts: String): String =
        path.join(jsCwd(), parts.joinToString("/")) as String

    actual fun readZip(path: String): List<Join<String, ByteArray>> = error("readZip JS not supported")

    actual fun createTempDir(prefix: String): String {
        val dir = path.join(os.tmpdir(), "$prefix-${Random.nextInt(1_000_000)}") as String
        jsMkdir(dir)
        return dir
    }
}

@file:Suppress("NonAsciiCharacters")

package borg.trikeshed.common

import borg.trikeshed.System as RootSystem
import borg.trikeshed.TypeEvidence as RootTypeEvidence
import borg.trikeshed.Usable as RootUsable
import borg.trikeshed.LongSeries
import borg.trikeshed.cursor.RowVec
import borg.trikeshed.lib.Series
import borg.trikeshed.toLongSeries as rootToLongSeries
import borg.trikeshed.toRowVec as rootToRowVec
import borg.trikeshed.lib.Join
import borg.trikeshed.lib.j
import borg.trikeshed.lib.toSeries
import borg.trikeshed.userspace.nio.file.Paths
import kotlin.time.Clock

typealias TypeEvidence = RootTypeEvidence
typealias Usable = RootUsable

object Files {
    fun readAllLines(filename: String): List<String> =
        NioFiles.readAllLines(Paths.get(filename))
    fun readAllBytes(filename: String): ByteArray =
        NioFiles.readAllBytes(Paths.get(filename))
    fun readString(filename: String): String =
        NioFiles.readString(Paths.get(filename))
    fun write(filename: String, bytes: ByteArray) =
        NioFiles.write(Paths.get(filename), bytes)
    fun write(filename: String, lines: List<String>) =
        NioFiles.write(Paths.get(filename), lines)
    fun write(filename: String, string: String) =
        NioFiles.writeString(Paths.get(filename), string)
    fun cwd(): String = System.getProperty("user.dir") ?: "."
    fun exists(filename: String): Boolean =
        NioFiles.exists(Paths.get(filename))
    fun streamLines(fileName: String, bufsize: Int): Sequence<Join<Long, ByteArray>> =
        NioFiles.readAllBytes(Paths.get(fileName)).let { bytes ->
            sequence {
                var start = 0
                bytes.forEachIndexed { i, b ->
                    if (b == '\n'.code.toByte()) {
                        yield(start.toLong() to bytes.copyOfRange(start, i + 1))
                        start = i + 1
                    }
                }
                if (start < bytes.size)
                    yield(start.toLong() to bytes.copyOfRange(start, bytes.size))
            }
        }.map { (off, arr) -> off j arr }
    fun iterateLines(fileName: String, bufsize: Int): Iterable<Join<Long, Series<Byte>>> =
        streamLines(fileName, bufsize).map { (off, arr) -> off j arr.toSeries() }.asIterable()
    fun listDir(path: String): List<String> = TODO("NIO listDir via opendir")
    fun isDir(path: String): Boolean =
        NioFiles.isDirectory(Paths.get(path))
    fun isFile(path: String): Boolean =
        NioFiles.isRegularFile(Paths.get(path))
    fun mkdirs(path: String) =
        NioFiles.createDirectories(Paths.get(path))
    fun deleteRecursively(path: String): Unit = TODO("NIO walkFileTree + delete")
    fun resolvePath(vararg parts: String): String =
        parts.fold(Paths.get(".")) { p, seg -> p.resolve(seg) }.toString()
    fun readZip(path: String): List<Pair<String, ByteArray>> = TODO("NIO zip reading")
    fun createTempDir(prefix: String): String = TODO("NIO createTempDirectory")
}

private object NioFiles {
    fun readAllLines(p: borg.trikeshed.userspace.nio.file.Path): List<String> =
        borg.trikeshed.userspace.nio.file.Files.readAllLines(p)
    fun readAllBytes(p: borg.trikeshed.userspace.nio.file.Path): ByteArray =
        borg.trikeshed.userspace.nio.file.Files.readAllBytes(p)
    fun readString(p: borg.trikeshed.userspace.nio.file.Path): String =
        borg.trikeshed.userspace.nio.file.Files.readString(p)
    fun write(p: borg.trikeshed.userspace.nio.file.Path, bytes: ByteArray) =
        borg.trikeshed.userspace.nio.file.Files.write(p, bytes)
    fun write(p: borg.trikeshed.userspace.nio.file.Path, lines: List<String>) =
        borg.trikeshed.userspace.nio.file.Files.write(p, lines)
    fun writeString(p: borg.trikeshed.userspace.nio.file.Path, s: String) =
        borg.trikeshed.userspace.nio.file.Files.writeString(p, s)
    fun exists(p: borg.trikeshed.userspace.nio.file.Path): Boolean =
        borg.trikeshed.userspace.nio.file.Files.exists(p)
    fun isDirectory(p: borg.trikeshed.userspace.nio.file.Path): Boolean =
        borg.trikeshed.userspace.nio.file.Files.isDirectory(p)
    fun isRegularFile(p: borg.trikeshed.userspace.nio.file.Path): Boolean =
        borg.trikeshed.userspace.nio.file.Files.isRegularFile(p)
    fun createDirectories(p: borg.trikeshed.userspace.nio.file.Path) =
        borg.trikeshed.userspace.nio.file.Files.createDirectories(p)
    fun lines(p: borg.trikeshed.userspace.nio.file.Path): Sequence<String> =
        borg.trikeshed.userspace.nio.file.Files.lines(p)
}

object System {
    fun getenv(name: String, defaultVal: String? = null): String? = RootSystem.getenv(name, defaultVal)
    val homedir: String get() = RootSystem.homedir
}

fun System.currentTimeMillis(): Long = Clock.System.now().toEpochMilliseconds()
fun System.getProperty(string: String, defVal: String? = null): String? = getenv(string, defVal)

fun mktemp(): String = Files.createTempDir("trikeshed")
fun rm(path: String): Boolean = runCatching { NioFiles.exists(Paths.get(path)) }.getOrDefault(false)
fun mkdir(path: String): Boolean = runCatching { NioFiles.createDirectories(Paths.get(path)); true }.getOrDefault(false)

fun readLinesSeq(path: String): Sequence<String> =
    NioFiles.lines(Paths.get(path))
fun readLines(path: String): List<String> =
    NioFiles.readAllLines(Paths.get(path))

fun <A> Series<A>.toLongSeries(): LongSeries<A> = rootToLongSeries()
fun TypeEvidence.toRowVec(): RowVec = rootToRowVec()

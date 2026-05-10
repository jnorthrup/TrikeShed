package borg.trikeshed.userspace.nio.file.spi

import borg.trikeshed.lib.Join
import borg.trikeshed.lib.Series
import kotlin.coroutines.CoroutineContext

/**
 * Platform filesystem operations — replaces [borg.trikeshed.lib.Files] expect object.
 *
 * Registered into [borg.trikeshed.context.SupervisorContextElement] by each platform.
 * Lives alongside [FileSystemProvider] in the NIO file SPI namespace.
 */
interface FileOperations : CoroutineContext.Element {
    companion object Key : CoroutineContext.Key<FileOperations>
    override val key: CoroutineContext.Key<*> get() = Key

    fun readAllLines(filename: String): List<String>
    fun readAllBytes(filename: String): ByteArray
    fun readString(filename: String): String
    fun write(filename: String, bytes: ByteArray)
    fun write(filename: String, lines: List<String>)
    fun write(filename: String, string: String)
    fun cwd(): String
    fun exists(filename: String): Boolean
    fun streamLines(fileName: String, bufsize: Int = 64): Sequence<Join<Long, ByteArray>>
    fun iterateLines(fileName: String, bufsize: Int = 12): Iterable<Join<Long, Series<Byte>>>
    fun listDir(path: String): List<String>
    fun isDir(path: String): Boolean
    fun isFile(path: String): Boolean
    fun mkdirs(path: String)
    fun deleteRecursively(path: String)
    fun resolvePath(vararg parts: String): String
    fun readZip(path: String): List<Pair<String, ByteArray>>
    fun createTempDir(prefix: String): String
}

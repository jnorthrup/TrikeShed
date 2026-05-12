package borg.trikeshed.userspace.nio.file.spi

import borg.trikeshed.lib.Join
import borg.trikeshed.lib.Series
import kotlin.coroutines.CoroutineContext

/**
 * Pure filesystem interface — the single source of truth for all file I/O.
 *
 * No expect/actual. No static objects. Each platform provides one implementation
 * registered via [PlatformProviders] into the coroutine context.
 *
 * Access via coroutine context:
 *   val fs = coroutineContext[FileOperations.Key]
 *   fs?.readString("config.yaml")
 *
 * Or use the top-level [borg.trikeshed.userspace.nio.file.Files] accessor property.
 */
interface FileOperations : CoroutineContext.Element {
    companion object Key : CoroutineContext.Key<FileOperations>
    override val key: CoroutineContext.Key<*> get() = Key

    fun readAllLines(filename: String): List<String>
    fun readAllBytes(filename: String): ByteArray
    fun readString(filename: String): CharSequence
    fun write(filename: String, bytes: ByteArray)
    fun write(filename: String, lines: List<String>)
    fun write(filename: String, string: String)
    fun cwd(): CharSequence
    fun exists(filename: String): Boolean
    fun streamLines(fileName: String, bufsize: Int = 64): Sequence<Join<Long, ByteArray>>
    fun iterateLines(fileName: String, bufsize: Int = 12): Iterable<Join<Long, Series<Byte>>>
    fun listDir(path: String): List<String>
    fun isDir(path: String): Boolean
    fun isFile(path: String): Boolean
    fun mkdirs(path: String)
    fun deleteRecursively(path: String)
    fun resolvePath(vararg parts: String): CharSequence
    fun readZip(path: String): List<Pair<String, ByteArray>>
    fun createTempDir(prefix: String): CharSequence
}

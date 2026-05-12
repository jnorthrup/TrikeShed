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

    fun readAllLines(filename: CharSequence): List<String>
    fun readAllBytes(filename: CharSequence): ByteArray
    fun readString(filename: CharSequence): CharSequence
    fun write(filename: CharSequence, bytes: ByteArray)
    fun write(filename: CharSequence, lines: List<CharSequence>)
    fun write(filename: CharSequence, string: CharSequence)
    fun cwd(): CharSequence
    fun exists(filename: CharSequence): Boolean
    fun streamLines(fileName: CharSequence, bufsize: Int = 64): Sequence<Join<Long, ByteArray>>
    fun iterateLines(fileName: CharSequence, bufsize: Int = 12): Iterable<Join<Long, Series<Byte>>>
    fun listDir(path: CharSequence): List<String>
    fun isDir(path: CharSequence): Boolean
    fun isFile(path: CharSequence): Boolean
    fun mkdirs(path: CharSequence)
    fun deleteRecursively(path: CharSequence)
    fun resolvePath(vararg parts: CharSequence): CharSequence
    fun readZip(path: CharSequence): List<Pair<String, ByteArray>>
    fun createTempDir(prefix: CharSequence): CharSequence
}

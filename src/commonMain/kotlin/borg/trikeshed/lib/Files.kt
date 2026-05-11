package borg.trikeshed.lib

import borg.trikeshed.userspace.nio.file.spi.FileOperations

/**
 * Platform filesystem accessor.
 *
 * Resolves to the platform [FileOperations] registered during init.
 * Usage unchanged: `Files.readString("foo.txt")`, `Files.cwd()`, etc.
 *
 * No expect/actual. Each platform's [PlatformProviders] assigns [fileOperations]
 * during static init. Until then, calls throw.
 */
val Files: FileOperations
    get() = fileOperations

/**
 * Mutable platform hook — each target's PlatformProviders sets this once during init.
 */
var fileOperations: FileOperations = UninitializedFileOperations
    internal set

private object UninitializedFileOperations : FileOperations {
    override val key get() = FileOperations.Key
    override fun readAllLines(filename: String): Nothing = error("FileOperations not initialized — call PlatformProviders.init() first")
    override fun readAllBytes(filename: String): Nothing = error("FileOperations not initialized — call PlatformProviders.init() first")
    override fun readString(filename: String): Nothing = error("FileOperations not initialized — call PlatformProviders.init() first")
    override fun write(filename: String, bytes: ByteArray): Nothing = error("FileOperations not initialized")
    override fun write(filename: String, lines: List<String>): Nothing = error("FileOperations not initialized")
    override fun write(filename: String, string: String): Nothing = error("FileOperations not initialized")
    override fun cwd(): Nothing = error("FileOperations not initialized")
    override fun exists(filename: String): Nothing = error("FileOperations not initialized")
    override fun streamLines(fileName: String, bufsize: Int): Nothing = error("FileOperations not initialized")
    override fun iterateLines(fileName: String, bufsize: Int): Nothing = error("FileOperations not initialized")
    override fun listDir(path: String): Nothing = error("FileOperations not initialized")
    override fun isDir(path: String): Boolean = error("FileOperations not initialized")
    override fun isFile(path: String): Boolean = error("FileOperations not initialized")
    override fun mkdirs(path: String): Nothing = error("FileOperations not initialized")
    override fun deleteRecursively(path: String): Nothing = error("FileOperations not initialized")
    override fun resolvePath(vararg parts: String): Nothing = error("FileOperations not initialized")
    override fun readZip(path: String): Nothing = error("FileOperations not initialized")
    override fun createTempDir(prefix: String): Nothing = error("FileOperations not initialized")
}

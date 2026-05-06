@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING", "RedundantVisibilityModifier", "unused")

package borg.trikeshed.userspace.nio.file

// Generated from Amazon Corretto JDK 25 java.base NIO public/protected API via javap.
// Declarations intentionally mirror JDK taxonomy and contain no implementations.
public abstract class FileSystem {
    protected constructor()
    fun provider(): borg.trikeshed.userspace.nio.file.spi.FileSystemProvider = TODO("NIO common stub")
    fun close(): Unit = TODO("NIO common stub")
    fun isOpen(): Boolean = TODO("NIO common stub")
    fun isReadOnly(): Boolean = TODO("NIO common stub")
    fun getSeparator(): String = TODO("NIO common stub")
    fun getRootDirectories(): Iterable<borg.trikeshed.userspace.nio.file.Path> = TODO("NIO common stub")
    fun getFileStores(): Iterable<borg.trikeshed.userspace.nio.file.FileStore> = TODO("NIO common stub")
    fun supportedFileAttributeViews(): java.util.Set<String> = TODO("NIO common stub")
    fun getPath(p0: String, vararg p1: String): borg.trikeshed.userspace.nio.file.Path = TODO("NIO common stub")
    fun getPathMatcher(p0: String): borg.trikeshed.userspace.nio.file.PathMatcher = TODO("NIO common stub")
    fun getUserPrincipalLookupService(): borg.trikeshed.userspace.nio.file.attribute.UserPrincipalLookupService = TODO("NIO common stub")
    fun newWatchService(): borg.trikeshed.userspace.nio.file.WatchService = TODO("NIO common stub")
}

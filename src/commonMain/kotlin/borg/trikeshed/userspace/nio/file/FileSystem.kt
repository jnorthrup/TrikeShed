@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING", "RedundantVisibilityModifier", "unused")

package borg.trikeshed.userspace.nio.file

// Generated from Amazon Corretto JDK 25 java.base NIO public/protected API via javap.
// Declarations intentionally mirror JDK taxonomy and contain no implementations.
public abstract class FileSystem {
    protected constructor()
    abstract fun provider(): borg.trikeshed.userspace.nio.file.spi.FileSystemProvider
    abstract fun close(): Unit
    abstract fun isOpen(): Boolean
    abstract fun isReadOnly(): Boolean
    abstract fun getSeparator(): String
    abstract fun getRootDirectories(): Iterable<borg.trikeshed.userspace.nio.file.Path>
    abstract fun getFileStores(): Iterable<borg.trikeshed.userspace.nio.file.FileStore>
    abstract fun supportedFileAttributeViews(): Set<String>
    abstract fun getPath(p0: String, vararg p1: String): borg.trikeshed.userspace.nio.file.Path
    abstract fun getPathMatcher(p0: String): borg.trikeshed.userspace.nio.file.PathMatcher
    abstract fun getUserPrincipalLookupService(): borg.trikeshed.userspace.nio.file.attribute.UserPrincipalLookupService
    abstract fun newWatchService(): borg.trikeshed.userspace.nio.file.WatchService
}

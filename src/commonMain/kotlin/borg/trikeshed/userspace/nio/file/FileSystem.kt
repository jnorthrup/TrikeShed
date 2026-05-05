@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING", "RedundantVisibilityModifier", "unused")

package borg.trikeshed.userspace.nio.file

// Generated from Amazon Corretto JDK 25 java.base NIO public/protected API via javap.
// Declarations intentionally mirror JDK taxonomy and contain no implementations.
expect abstract class FileSystem {
    protected constructor()
    fun provider(): borg.trikeshed.userspace.nio.file.spi.FileSystemProvider
    fun close(): Unit
    fun isOpen(): Boolean
    fun isReadOnly(): Boolean
    fun getSeparator(): String
    fun getRootDirectories(): Iterable<borg.trikeshed.userspace.nio.file.Path>
    fun getFileStores(): Iterable<borg.trikeshed.userspace.nio.file.FileStore>
    fun supportedFileAttributeViews(): java.util.Set<String>
    fun getPath(p0: String, vararg p1: String): borg.trikeshed.userspace.nio.file.Path
    fun getPathMatcher(p0: String): borg.trikeshed.userspace.nio.file.PathMatcher
    fun getUserPrincipalLookupService(): borg.trikeshed.userspace.nio.file.attribute.UserPrincipalLookupService
    fun newWatchService(): borg.trikeshed.userspace.nio.file.WatchService
}

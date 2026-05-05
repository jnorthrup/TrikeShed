@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING", "RedundantVisibilityModifier", "unused")

package borg.trikeshed.userspace.nio.file

// Generated from Amazon Corretto JDK 25 java.base NIO public/protected API via javap.
// Declarations intentionally mirror JDK taxonomy and contain no implementations.
public interface Path : Comparable<borg.trikeshed.userspace.nio.file.Path>, Iterable<borg.trikeshed.userspace.nio.file.Path>, borg.trikeshed.userspace.nio.file.Watchable {
    fun getFileSystem(): borg.trikeshed.userspace.nio.file.FileSystem
    fun isAbsolute(): Boolean
    fun getRoot(): borg.trikeshed.userspace.nio.file.Path
    fun getFileName(): borg.trikeshed.userspace.nio.file.Path
    fun getParent(): borg.trikeshed.userspace.nio.file.Path
    fun getNameCount(): Int
    fun getName(p0: Int): borg.trikeshed.userspace.nio.file.Path
    fun subpath(p0: Int, p1: Int): borg.trikeshed.userspace.nio.file.Path
    fun startsWith(p0: borg.trikeshed.userspace.nio.file.Path): Boolean
    fun startsWith(p0: String): Boolean
    fun endsWith(p0: borg.trikeshed.userspace.nio.file.Path): Boolean
    fun endsWith(p0: String): Boolean
    fun normalize(): borg.trikeshed.userspace.nio.file.Path
    fun resolve(p0: borg.trikeshed.userspace.nio.file.Path): borg.trikeshed.userspace.nio.file.Path
    fun resolve(p0: String): borg.trikeshed.userspace.nio.file.Path
    fun resolve(p0: borg.trikeshed.userspace.nio.file.Path, vararg p1: borg.trikeshed.userspace.nio.file.Path): borg.trikeshed.userspace.nio.file.Path
    fun resolve(p0: String, vararg p1: String): borg.trikeshed.userspace.nio.file.Path
    fun resolveSibling(p0: borg.trikeshed.userspace.nio.file.Path): borg.trikeshed.userspace.nio.file.Path
    fun resolveSibling(p0: String): borg.trikeshed.userspace.nio.file.Path
    fun relativize(p0: borg.trikeshed.userspace.nio.file.Path): borg.trikeshed.userspace.nio.file.Path
    fun toUri(): java.net.URI
    fun toAbsolutePath(): borg.trikeshed.userspace.nio.file.Path
    fun toRealPath(vararg p0: borg.trikeshed.userspace.nio.file.LinkOption): borg.trikeshed.userspace.nio.file.Path
    fun toFile(): java.io.File
    fun register(p0: borg.trikeshed.userspace.nio.file.WatchService, p1: Array<borg.trikeshed.userspace.nio.file.WatchEvent.Kind<*>>, vararg p2: borg.trikeshed.userspace.nio.file.WatchEvent.Modifier): borg.trikeshed.userspace.nio.file.WatchKey
    fun register(p0: borg.trikeshed.userspace.nio.file.WatchService, vararg p1: borg.trikeshed.userspace.nio.file.WatchEvent.Kind<*>): borg.trikeshed.userspace.nio.file.WatchKey
    fun iterator(): java.util.Iterator<borg.trikeshed.userspace.nio.file.Path>
    override fun compareTo(p0: borg.trikeshed.userspace.nio.file.Path): Int
    override fun equals(p0: Any?): Boolean
    override fun hashCode(): Int
    override fun toString(): String
    override fun compareTo(p0: Any): Int
    companion object {
        fun of(p0: String, vararg p1: String): borg.trikeshed.userspace.nio.file.Path
        fun of(p0: java.net.URI): borg.trikeshed.userspace.nio.file.Path
    }
}

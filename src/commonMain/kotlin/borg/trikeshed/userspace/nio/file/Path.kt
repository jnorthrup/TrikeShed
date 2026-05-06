@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING", "RedundantVisibilityModifier", "unused")

package borg.trikeshed.userspace.nio.file

// Generated from Amazon Corretto JDK 25 java.base NIO public/protected API via javap.
// Declarations intentionally mirror JDK taxonomy and contain no implementations.
public interface Path : Comparable<borg.trikeshed.userspace.nio.file.Path>, Iterable<borg.trikeshed.userspace.nio.file.Path>, borg.trikeshed.userspace.nio.file.Watchable {
    fun getFileSystem(): borg.trikeshed.userspace.nio.file.FileSystem = TODO("NIO common stub")
    fun isAbsolute(): Boolean = TODO("NIO common stub")
    fun getRoot(): borg.trikeshed.userspace.nio.file.Path = TODO("NIO common stub")
    fun getFileName(): borg.trikeshed.userspace.nio.file.Path = TODO("NIO common stub")
    fun getParent(): borg.trikeshed.userspace.nio.file.Path = TODO("NIO common stub")
    fun getNameCount(): Int = TODO("NIO common stub")
    fun getName(p0: Int): borg.trikeshed.userspace.nio.file.Path = TODO("NIO common stub")
    fun subpath(p0: Int, p1: Int): borg.trikeshed.userspace.nio.file.Path = TODO("NIO common stub")
    fun startsWith(p0: borg.trikeshed.userspace.nio.file.Path): Boolean = TODO("NIO common stub")
    fun startsWith(p0: String): Boolean = TODO("NIO common stub")
    fun endsWith(p0: borg.trikeshed.userspace.nio.file.Path): Boolean = TODO("NIO common stub")
    fun endsWith(p0: String): Boolean = TODO("NIO common stub")
    fun normalize(): borg.trikeshed.userspace.nio.file.Path = TODO("NIO common stub")
    fun resolve(p0: borg.trikeshed.userspace.nio.file.Path): borg.trikeshed.userspace.nio.file.Path = TODO("NIO common stub")
    fun resolve(p0: String): borg.trikeshed.userspace.nio.file.Path = TODO("NIO common stub")
    fun resolve(p0: borg.trikeshed.userspace.nio.file.Path, vararg p1: borg.trikeshed.userspace.nio.file.Path): borg.trikeshed.userspace.nio.file.Path = TODO("NIO common stub")
    fun resolve(p0: String, vararg p1: String): borg.trikeshed.userspace.nio.file.Path = TODO("NIO common stub")
    fun resolveSibling(p0: borg.trikeshed.userspace.nio.file.Path): borg.trikeshed.userspace.nio.file.Path = TODO("NIO common stub")
    fun resolveSibling(p0: String): borg.trikeshed.userspace.nio.file.Path = TODO("NIO common stub")
    fun relativize(p0: borg.trikeshed.userspace.nio.file.Path): borg.trikeshed.userspace.nio.file.Path = TODO("NIO common stub")
    fun toUri(): java.net.URI = TODO("NIO common stub")
    fun toAbsolutePath(): borg.trikeshed.userspace.nio.file.Path = TODO("NIO common stub")
    fun toRealPath(vararg p0: borg.trikeshed.userspace.nio.file.LinkOption): borg.trikeshed.userspace.nio.file.Path = TODO("NIO common stub")
    fun toFile(): java.io.File = TODO("NIO common stub")
    fun register(p0: borg.trikeshed.userspace.nio.file.WatchService, p1: Array<borg.trikeshed.userspace.nio.file.WatchEvent.Kind<*>>, vararg p2: borg.trikeshed.userspace.nio.file.WatchEvent.Modifier): borg.trikeshed.userspace.nio.file.WatchKey = TODO("NIO common stub")
    fun register(p0: borg.trikeshed.userspace.nio.file.WatchService, vararg p1: borg.trikeshed.userspace.nio.file.WatchEvent.Kind<*>): borg.trikeshed.userspace.nio.file.WatchKey = TODO("NIO common stub")
    fun iterator(): java.util.Iterator<borg.trikeshed.userspace.nio.file.Path> = TODO("NIO common stub")
    override fun compareTo(p0: borg.trikeshed.userspace.nio.file.Path): Int = TODO("NIO common stub")
    override fun equals(p0: Any?): Boolean = TODO("NIO common stub")
    override fun hashCode(): Int = TODO("NIO common stub")
    override fun toString(): String = TODO("NIO common stub")
    override fun compareTo(p0: Any): Int = TODO("NIO common stub")
    companion object {
        fun of(p0: String, vararg p1: String): borg.trikeshed.userspace.nio.file.Path = TODO("NIO common stub")
        fun of(p0: java.net.URI): borg.trikeshed.userspace.nio.file.Path = TODO("NIO common stub")
    }
}

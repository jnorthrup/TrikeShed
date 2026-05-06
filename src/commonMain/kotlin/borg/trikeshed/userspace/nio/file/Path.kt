@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING", "RedundantVisibilityModifier", "unused")

package borg.trikeshed.userspace.nio.file

// Generated from Amazon Corretto JDK 25 java.base NIO public/protected API via javap.
// Declarations intentionally mirror JDK taxonomy and contain no implementations.
public interface Path : Comparable<Path>, Iterable<Path>, Watchable {
    fun getFileSystem(): FileSystem = TODO("NIO common stub")
    fun isAbsolute(): Boolean = TODO("NIO common stub")
    fun getRoot(): Path = TODO("NIO common stub")
    fun getFileName(): Path = TODO("NIO common stub")
    fun getParent(): Path = TODO("NIO common stub")
    fun getNameCount(): Int = TODO("NIO common stub")
    fun getName(p0: Int): Path = TODO("NIO common stub")
    fun subpath(p0: Int, p1: Int): Path = TODO("NIO common stub")
    fun startsWith(p0: Path): Boolean = TODO("NIO common stub")
    fun startsWith(p0: String): Boolean = TODO("NIO common stub")
    fun endsWith(p0: Path): Boolean = TODO("NIO common stub")
    fun endsWith(p0: String): Boolean = TODO("NIO common stub")
    fun normalize(): Path = TODO("NIO common stub")
    fun resolve(p0: Path): Path = TODO("NIO common stub")
    fun resolve(p0: String): Path = TODO("NIO common stub")
    fun resolve(p0: Path, vararg p1: Path): Path = TODO("NIO common stub")
    fun resolve(p0: String, vararg p1: String): Path = TODO("NIO common stub")
    fun resolveSibling(p0: Path): Path = TODO("NIO common stub")
    fun resolveSibling(p0: String): Path = TODO("NIO common stub")
    fun relativize(p0: Path): Path = TODO("NIO common stub")
    fun toUri(): String = TODO("NIO common stub")
    fun toAbsolutePath(): Path = TODO("NIO common stub")
    fun toRealPath(vararg p0: LinkOption): Path = TODO("NIO common stub")
    fun toFile(): Any = TODO("NIO common stub")
    override fun register(p0: WatchService, p1: Array<WatchEvent.Kind<*>>, vararg p2: WatchEvent.Modifier): WatchKey = TODO("NIO common stub")
    override fun register(p0: WatchService, vararg p1: WatchEvent.Kind<*>): WatchKey = TODO("NIO common stub")
    override fun iterator(): kotlin.collections.Iterator<Path> = TODO("NIO common stub")
    override fun compareTo(other: Path): Int = TODO("NIO common stub")

    companion object {
        fun of(p0: String, vararg p1: String): Path = TODO("NIO common stub")
        fun of(p0: String): Path = TODO("NIO common stub")
    }
}

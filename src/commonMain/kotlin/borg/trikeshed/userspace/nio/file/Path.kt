@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING", "RedundantVisibilityModifier", "unused")

package borg.trikeshed.userspace.nio.file

// Generated from Amazon Corretto JDK 25 java.base NIO public/protected API via javap.
// Declarations intentionally mirror JDK taxonomy and contain no implementations.
public interface Path : Comparable<Path>, Iterable<Path>, Watchable {
    fun getFileSystem(): FileSystem
    fun isAbsolute(): Boolean
    fun getRoot(): Path
    fun getFileName(): Path
    fun getParent(): Path
    fun getNameCount(): Int
    fun getName(p0: Int): Path
    fun subpath(p0: Int, p1: Int): Path
    fun startsWith(p0: Path): Boolean
    fun startsWith(p0: String): Boolean
    fun endsWith(p0: Path): Boolean
    fun endsWith(p0: String): Boolean
    fun normalize(): Path
    fun resolve(p0: Path): Path
    fun resolve(p0: String): Path
    fun resolve(p0: Path, vararg p1: Path): Path
    fun resolve(p0: String, vararg p1: String): Path
    fun resolveSibling(p0: Path): Path
    fun resolveSibling(p0: String): Path
    fun relativize(p0: Path): Path
    fun toUri(): String
    fun toAbsolutePath(): Path
    fun toRealPath(vararg p0: LinkOption): Path
    fun toFile(): Any
    override fun register(p0: WatchService, p1: Array<WatchEvent.Kind<*>>, vararg p2: WatchEvent.Modifier): WatchKey
    override fun register(p0: WatchService, vararg p1: WatchEvent.Kind<*>): WatchKey
    override fun iterator(): kotlin.collections.Iterator<Path>
    override fun compareTo(other: Path): Int

    companion object {
        fun of(p0: String, vararg p1: String): Path = throw UnsupportedOperationException("NIO common stub")
        fun of(p0: String): Path = throw UnsupportedOperationException("NIO common stub")
    }
}

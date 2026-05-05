@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING", "RedundantVisibilityModifier", "unused")

package borg.trikeshed.userspace.nio.file

// Generated from Amazon Corretto JDK 25 java.base NIO public/protected API via javap.
// Declarations intentionally mirror JDK taxonomy and contain no implementations.
expect class FileSystems {
    companion object {
        fun getDefault(): borg.trikeshed.userspace.nio.file.FileSystem
        fun getFileSystem(p0: java.net.URI): borg.trikeshed.userspace.nio.file.FileSystem
        fun newFileSystem(p0: java.net.URI, p1: java.util.Map<String, *>): borg.trikeshed.userspace.nio.file.FileSystem
        fun newFileSystem(p0: java.net.URI, p1: java.util.Map<String, *>, p2: java.lang.ClassLoader): borg.trikeshed.userspace.nio.file.FileSystem
        fun newFileSystem(p0: borg.trikeshed.userspace.nio.file.Path, p1: java.lang.ClassLoader): borg.trikeshed.userspace.nio.file.FileSystem
        fun newFileSystem(p0: borg.trikeshed.userspace.nio.file.Path, p1: java.util.Map<String, *>): borg.trikeshed.userspace.nio.file.FileSystem
        fun newFileSystem(p0: borg.trikeshed.userspace.nio.file.Path): borg.trikeshed.userspace.nio.file.FileSystem
        fun newFileSystem(p0: borg.trikeshed.userspace.nio.file.Path, p1: java.util.Map<String, *>, p2: java.lang.ClassLoader): borg.trikeshed.userspace.nio.file.FileSystem
    }
}

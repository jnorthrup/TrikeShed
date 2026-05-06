@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING", "RedundantVisibilityModifier", "unused")

package borg.trikeshed.userspace.nio.file.attribute

// Generated from Amazon Corretto JDK 25 java.base NIO public/protected API via javap.
// Declarations intentionally mirror JDK taxonomy and contain no implementations.
public interface DosFileAttributeView : borg.trikeshed.userspace.nio.file.attribute.BasicFileAttributeView {
    fun name(): String = TODO("NIO common stub")
    fun readAttributes(): borg.trikeshed.userspace.nio.file.attribute.DosFileAttributes = TODO("NIO common stub")
    fun setReadOnly(p0: Boolean): Unit = TODO("NIO common stub")
    fun setHidden(p0: Boolean): Unit = TODO("NIO common stub")
    fun setSystem(p0: Boolean): Unit = TODO("NIO common stub")
    fun setArchive(p0: Boolean): Unit = TODO("NIO common stub")
}

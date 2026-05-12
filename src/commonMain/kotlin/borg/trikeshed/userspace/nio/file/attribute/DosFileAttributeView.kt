@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING", "RedundantVisibilityModifier", "unused")

package borg.trikeshed.userspace.nio.file.attribute

// Generated from Amazon Corretto JDK 25 java.base NIO public/protected API via javap.
// Declarations intentionally mirror JDK taxonomy and contain no implementations.
public interface DosFileAttributeView : BasicFileAttributeView {
    override fun name(): CharSequence = TODO("NIO common stub")
    override fun readAttributes(): DosFileAttributes = TODO("NIO common stub")
    fun setReadOnly(p0: Boolean): Unit = TODO("NIO common stub")
    fun setHidden(p0: Boolean): Unit = TODO("NIO common stub")
    fun setSystem(p0: Boolean): Unit = TODO("NIO common stub")
    fun setArchive(p0: Boolean): Unit = TODO("NIO common stub")
}

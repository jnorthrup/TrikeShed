@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING", "RedundantVisibilityModifier", "unused")

package borg.trikeshed.userspace.nio.file.attribute

// Generated from Amazon Corretto JDK 25 java.base NIO public/protected API via javap.
// Declarations intentionally mirror JDK taxonomy and contain no implementations.
public interface BasicFileAttributeView : FileAttributeView {
    override fun name():CharSequence= TODO("NIO common stub")
    fun readAttributes(): BasicFileAttributes = TODO("NIO common stub")
    fun setTimes(p0: FileTime, p1: FileTime, p2: FileTime): Unit = TODO("NIO common stub")
}

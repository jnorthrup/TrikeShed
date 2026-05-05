@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING", "RedundantVisibilityModifier", "unused")

package borg.trikeshed.userspace.nio.file.attribute

// Generated from Amazon Corretto JDK 25 java.base NIO public/protected API via javap.
// Declarations intentionally mirror JDK taxonomy and contain no implementations.
expect interface DosFileAttributeView : borg.trikeshed.userspace.nio.file.attribute.BasicFileAttributeView {
    fun name(): String
    fun readAttributes(): borg.trikeshed.userspace.nio.file.attribute.DosFileAttributes
    fun setReadOnly(p0: Boolean): Unit
    fun setHidden(p0: Boolean): Unit
    fun setSystem(p0: Boolean): Unit
    fun setArchive(p0: Boolean): Unit
}

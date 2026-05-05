@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING", "RedundantVisibilityModifier", "unused")

package borg.trikeshed.userspace.nio.file.attribute

// Generated from Amazon Corretto JDK 25 java.base NIO public/protected API via javap.
// Declarations intentionally mirror JDK taxonomy and contain no implementations.
public interface UserDefinedFileAttributeView : borg.trikeshed.userspace.nio.file.attribute.FileAttributeView {
    fun name(): String
    fun list(): java.util.List<String>
    fun size(p0: String): Int
    fun read(p0: String, p1: borg.trikeshed.userspace.nio.ByteBuffer): Int
    fun write(p0: String, p1: borg.trikeshed.userspace.nio.ByteBuffer): Int
    fun delete(p0: String): Unit
}

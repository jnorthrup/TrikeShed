@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING", "RedundantVisibilityModifier", "unused")

package borg.trikeshed.userspace.nio.file.attribute

// Generated from Amazon Corretto JDK 25 java.base NIO public/protected API via javap.
// Declarations intentionally mirror JDK taxonomy and contain no implementations.
public interface UserDefinedFileAttributeView : FileAttributeView {
    override fun name(): String = TODO("NIO common stub")
    fun list(): List<String> = TODO("NIO common stub")
    fun size(p0: String): Int = TODO("NIO common stub")
    fun read(p0: String, p1: borg.trikeshed.userspace.nio.ByteBuffer): Int = TODO("NIO common stub")
    fun write(p0: String, p1: borg.trikeshed.userspace.nio.ByteBuffer): Int = TODO("NIO common stub")
    fun delete(p0: String): Unit = TODO("NIO common stub")
}

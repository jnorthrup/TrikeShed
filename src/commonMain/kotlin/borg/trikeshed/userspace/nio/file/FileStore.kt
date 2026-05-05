@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING", "RedundantVisibilityModifier", "unused")

package borg.trikeshed.userspace.nio.file

// Generated from Amazon Corretto JDK 25 java.base NIO public/protected API via javap.
// Declarations intentionally mirror JDK taxonomy and contain no implementations.
public abstract class FileStore {
    protected constructor()
    fun name(): String
    fun type(): String
    fun isReadOnly(): Boolean
    fun getTotalSpace(): Long
    fun getUsableSpace(): Long
    fun getUnallocatedSpace(): Long
    fun getBlockSize(): Long
    fun supportsFileAttributeView(p0: java.lang.Class<out borg.trikeshed.userspace.nio.file.attribute.FileAttributeView>): Boolean
    fun supportsFileAttributeView(p0: String): Boolean
    fun <V : borg.trikeshed.userspace.nio.file.attribute.FileStoreAttributeView> getFileStoreAttributeView(p0: java.lang.Class<V>): V
    fun getAttribute(p0: String): Any
}

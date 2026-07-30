@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING", "RedundantVisibilityModifier", "unused")

package borg.trikeshed.userspace.nio.file

// Generated from Amazon Corretto JDK 25 java.base NIO public/protected API via javap.
// Declarations intentionally mirror JDK taxonomy and contain no implementations.
public abstract class FileStore {
    protected constructor()
    abstract fun name(): String
    abstract fun type(): String
    abstract fun isReadOnly(): Boolean
    abstract fun getTotalSpace(): Long
    abstract fun getUsableSpace(): Long
    abstract fun getUnallocatedSpace(): Long
    abstract fun getBlockSize(): Long
    abstract fun supportsFileAttributeView(p0: kotlin.reflect.KClass<out borg.trikeshed.userspace.nio.file.attribute.FileAttributeView>): Boolean
    abstract fun supportsFileAttributeView(p0: String): Boolean
    abstract fun <V : borg.trikeshed.userspace.nio.file.attribute.FileStoreAttributeView> getFileStoreAttributeView(p0: kotlin.reflect.KClass<V>): V
    abstract fun getAttribute(p0: String): Any
}

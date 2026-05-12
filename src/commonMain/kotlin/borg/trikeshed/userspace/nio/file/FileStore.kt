@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING", "RedundantVisibilityModifier", "unused")

package borg.trikeshed.userspace.nio.file

// Generated from Amazon Corretto JDK 25 java.base NIO public/protected API via javap.
// Declarations intentionally mirror JDK taxonomy and contain no implementations.
public abstract class FileStore {
    protected constructor()
    fun name():CharSequence= TODO("NIO common stub")
    fun type():CharSequence= TODO("NIO common stub")
    fun isReadOnly(): Boolean = TODO("NIO common stub")
    fun getTotalSpace(): Long = TODO("NIO common stub")
    fun getUsableSpace(): Long = TODO("NIO common stub")
    fun getUnallocatedSpace(): Long = TODO("NIO common stub")
    fun getBlockSize(): Long = TODO("NIO common stub")
    fun supportsFileAttributeView(p0: kotlin.reflect.KClass<out borg.trikeshed.userspace.nio.file.attribute.FileAttributeView>): Boolean = TODO("NIO common stub")
    fun supportsFileAttributeView(p0: String): Boolean = TODO("NIO common stub")
    fun <V : borg.trikeshed.userspace.nio.file.attribute.FileStoreAttributeView> getFileStoreAttributeView(p0: kotlin.reflect.KClass<V>): V = TODO("NIO common stub")
    fun getAttribute(p0: String): Any = TODO("NIO common stub")
}

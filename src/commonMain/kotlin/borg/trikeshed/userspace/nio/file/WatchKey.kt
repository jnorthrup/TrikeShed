@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING", "RedundantVisibilityModifier", "unused")

package borg.trikeshed.userspace.nio.file

// Generated from Amazon Corretto JDK 25 java.base NIO public/protected API via javap.
// Declarations intentionally mirror JDK taxonomy and contain no implementations.
public interface WatchKey {
    fun isValid(): Boolean = TODO("NIO common stub")
    fun pollEvents(): java.util.List<borg.trikeshed.userspace.nio.file.WatchEvent<*>> = TODO("NIO common stub")
    fun reset(): Boolean = TODO("NIO common stub")
    fun cancel(): Unit = TODO("NIO common stub")
    fun watchable(): borg.trikeshed.userspace.nio.file.Watchable = TODO("NIO common stub")
}

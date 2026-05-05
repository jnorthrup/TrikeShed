@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING", "RedundantVisibilityModifier", "unused")

package borg.trikeshed.userspace.nio.file

// Generated from Amazon Corretto JDK 25 java.base NIO public/protected API via javap.
// Declarations intentionally mirror JDK taxonomy and contain no implementations.
public interface WatchEvent<T> {
    fun kind(): borg.trikeshed.userspace.nio.file.WatchEvent.Kind<T>
    fun count(): Int
    fun context(): T

    public interface Kind<T> {
        fun name(): String
        fun type(): java.lang.Class<T>
    }

    public interface Modifier {
        fun name(): String
    }
}

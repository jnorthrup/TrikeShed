@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING", "RedundantVisibilityModifier", "unused")

package borg.trikeshed.userspace.nio.file

// Generated from Amazon Corretto JDK 25 java.base NIO public/protected API via javap.
// Declarations intentionally mirror JDK taxonomy and contain no implementations.
public interface WatchEvent<T : Any> {
    fun kind(): borg.trikeshed.userspace.nio.file.WatchEvent.Kind<T> = TODO("NIO common stub")
    fun count(): Int = TODO("NIO common stub")
    fun context(): T = TODO("NIO common stub")

    public interface Kind<T : Any> {
        fun name(): String = TODO("NIO common stub")
        fun type(): kotlin.reflect.KClass<T> = TODO("NIO common stub")
    }

    public interface Modifier {
        fun name(): String = TODO("NIO common stub")
    }
}

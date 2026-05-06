@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING", "RedundantVisibilityModifier", "unused")

package borg.trikeshed.userspace.nio.channels

// Generated from Amazon Corretto JDK 25 java.base NIO public/protected API via javap.
// Declarations intentionally mirror JDK taxonomy and contain no implementations.
public interface CompletionHandler<V, A> {
    fun completed(result: V, attachment: A): Unit = TODO("NIO common stub")
    fun failed(exc: Throwable, attachment: A): Unit = TODO("NIO common stub")
}

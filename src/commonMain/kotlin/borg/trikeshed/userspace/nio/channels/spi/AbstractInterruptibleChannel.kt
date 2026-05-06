@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING", "RedundantVisibilityModifier", "unused")

package borg.trikeshed.userspace.nio.channels.spi

// Generated from Amazon Corretto JDK 25 java.base NIO public/protected API via javap.
// Declarations intentionally mirror JDK taxonomy and contain no implementations.
public abstract class AbstractInterruptibleChannel {
    protected constructor()
    open fun close(): Unit = TODO("NIO common stub")
    protected abstract fun implCloseChannel(): Unit
    open fun isOpen(): Boolean = TODO("NIO common stub")
    protected abstract fun begin(): Unit
    protected abstract fun end(completed: Boolean): Unit
}

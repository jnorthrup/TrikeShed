@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING", "RedundantVisibilityModifier", "unused")

package borg.trikeshed.userspace.nio.channels.spi

// Generated from Amazon Corretto JDK 25 java.base NIO public/protected API via javap.
// Declarations intentionally mirror JDK taxonomy and contain no implementations.
expect abstract class AbstractInterruptibleChannel {
    protected constructor()
    fun close(): Unit
    protected fun implCloseChannel(): Unit
    fun isOpen(): Boolean
    protected fun begin(): Unit
    protected fun end(p0: Boolean): Unit
}

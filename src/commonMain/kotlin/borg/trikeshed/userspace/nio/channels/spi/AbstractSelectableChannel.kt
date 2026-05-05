@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING", "RedundantVisibilityModifier", "unused")

package borg.trikeshed.userspace.nio.channels.spi

// Generated from Amazon Corretto JDK 25 java.base NIO public/protected API via javap.
// Declarations intentionally mirror JDK taxonomy and contain no implementations.
public abstract class AbstractSelectableChannel : borg.trikeshed.userspace.nio.channels.SelectableChannel {
    protected constructor(p0: borg.trikeshed.userspace.nio.channels.spi.SelectorProvider)
    fun provider(): borg.trikeshed.userspace.nio.channels.spi.SelectorProvider
    fun isRegistered(): Boolean
    fun keyFor(p0: borg.trikeshed.userspace.nio.channels.Selector): borg.trikeshed.userspace.nio.channels.SelectionKey
    fun register(p0: borg.trikeshed.userspace.nio.channels.Selector, p1: Int, p2: Any): borg.trikeshed.userspace.nio.channels.SelectionKey
    protected fun implCloseChannel(): Unit
    protected fun implCloseSelectableChannel(): Unit
    fun isBlocking(): Boolean
    fun blockingLock(): Any
    fun configureBlocking(p0: Boolean): borg.trikeshed.userspace.nio.channels.SelectableChannel
    protected fun implConfigureBlocking(p0: Boolean): Unit
}

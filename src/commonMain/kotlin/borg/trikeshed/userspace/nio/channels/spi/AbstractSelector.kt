@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING", "RedundantVisibilityModifier", "unused")

package borg.trikeshed.userspace.nio.channels.spi

// Generated from Amazon Corretto JDK 25 java.base NIO public/protected API via javap.
// Declarations intentionally mirror JDK taxonomy and contain no implementations.
public abstract class AbstractSelector : borg.trikeshed.userspace.nio.channels.Selector {
    protected constructor(p0: borg.trikeshed.userspace.nio.channels.spi.SelectorProvider)
    fun close(): Unit = TODO("NIO common stub")
    protected fun implCloseSelector(): Unit
    fun isOpen(): Boolean = TODO("NIO common stub")
    fun provider(): borg.trikeshed.userspace.nio.channels.spi.SelectorProvider = TODO("NIO common stub")
    protected fun cancelledKeys(): java.util.Set<borg.trikeshed.userspace.nio.channels.SelectionKey>
    protected fun register(p0: borg.trikeshed.userspace.nio.channels.spi.AbstractSelectableChannel, p1: Int, p2: Any): borg.trikeshed.userspace.nio.channels.SelectionKey
    protected fun deregister(p0: borg.trikeshed.userspace.nio.channels.spi.AbstractSelectionKey): Unit
    protected fun begin(): Unit
    protected fun end(): Unit
}

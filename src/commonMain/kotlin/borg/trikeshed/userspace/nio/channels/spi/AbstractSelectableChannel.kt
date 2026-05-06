@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING", "RedundantVisibilityModifier", "unused")

package borg.trikeshed.userspace.nio.channels.spi

// Generated from Amazon Corretto JDK 25 java.base NIO public/protected API via javap.
// Declarations intentionally mirror JDK taxonomy and contain no implementations.
public abstract class AbstractSelectableChannel : borg.trikeshed.userspace.nio.channels.SelectableChannel {
    protected constructor(p0: borg.trikeshed.userspace.nio.channels.spi.SelectorProvider)
    fun provider(): borg.trikeshed.userspace.nio.channels.spi.SelectorProvider = TODO("NIO common stub")
    fun isRegistered(): Boolean = TODO("NIO common stub")
    fun keyFor(p0: borg.trikeshed.userspace.nio.channels.Selector): borg.trikeshed.userspace.nio.channels.SelectionKey = TODO("NIO common stub")
    fun register(p0: borg.trikeshed.userspace.nio.channels.Selector, p1: Int, p2: Any): borg.trikeshed.userspace.nio.channels.SelectionKey = TODO("NIO common stub")
    protected fun implCloseChannel(): Unit
    protected fun implCloseSelectableChannel(): Unit
    fun isBlocking(): Boolean = TODO("NIO common stub")
    fun blockingLock(): Any = TODO("NIO common stub")
    fun configureBlocking(p0: Boolean): borg.trikeshed.userspace.nio.channels.SelectableChannel = TODO("NIO common stub")
    protected fun implConfigureBlocking(p0: Boolean): Unit
}

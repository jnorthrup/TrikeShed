@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING", "RedundantVisibilityModifier", "unused")

package borg.trikeshed.userspace.nio.channels.spi

import borg.trikeshed.userspace.nio.channels.SelectableChannel
import borg.trikeshed.userspace.nio.channels.Selector
import borg.trikeshed.userspace.nio.channels.SelectionKey

// Generated from Amazon Corretto JDK 25 java.base NIO public/protected API via javap.
// Declarations intentionally mirror JDK taxonomy and contain no implementations.
public abstract class AbstractSelectableChannel : SelectableChannel {
    protected constructor(provider: SelectorProvider) : super()
    public abstract override fun provider(): SelectorProvider
    public abstract override fun isRegistered(): Boolean
    public abstract override fun keyFor(sel: Selector): SelectionKey
    public abstract override fun register(sel: Selector, ops: Int, att: Any): SelectionKey
    protected final override fun implCloseChannel(): Unit = implCloseSelectableChannel()
    protected abstract fun implCloseSelectableChannel(): Unit
    public abstract override fun isBlocking(): Boolean
    public abstract override fun blockingLock(): Any
    public abstract override fun configureBlocking(block: Boolean): SelectableChannel
    protected abstract fun implConfigureBlocking(block: Boolean): Unit
}

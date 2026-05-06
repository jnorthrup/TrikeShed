@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING", "RedundantVisibilityModifier", "unused")

package borg.trikeshed.userspace.nio.channels.spi

import borg.trikeshed.userspace.nio.channels.Selector
import borg.trikeshed.userspace.nio.channels.SelectionKey

// Generated from Amazon Corretto JDK 25 java.base NIO public/protected API via javap.
// Declarations intentionally mirror JDK taxonomy and contain no implementations.
public abstract class AbstractSelector : Selector {
    protected constructor(provider: SelectorProvider) : super()
    public abstract override fun close(): Unit
    protected abstract fun implCloseSelector(): Unit
    public abstract override fun isOpen(): Boolean
    public abstract override fun provider(): SelectorProvider
    protected abstract fun cancelledKeys(): Set<SelectionKey>
    protected abstract fun register(ch: AbstractSelectableChannel, ops: Int, att: Any): SelectionKey
    protected abstract fun deregister(key: AbstractSelectionKey): Unit
    protected abstract fun begin(): Unit
    protected abstract fun end(): Unit
}

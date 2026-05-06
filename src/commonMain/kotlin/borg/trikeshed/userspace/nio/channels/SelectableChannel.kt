@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING", "RedundantVisibilityModifier", "unused")

package borg.trikeshed.userspace.nio.channels

import borg.trikeshed.userspace.nio.channels.spi.AbstractInterruptibleChannel
import borg.trikeshed.userspace.nio.channels.spi.SelectorProvider
// Generated from Amazon Corretto JDK 25 java.base NIO public/protected API via javap.
// Declarations intentionally mirror JDK taxonomy and contain no implementations.
public abstract class SelectableChannel protected constructor() : AbstractInterruptibleChannel(), Channel {
    public abstract override fun close()
    public abstract override fun isOpen(): Boolean
    public abstract fun provider(): SelectorProvider
    public abstract fun validOps(): Int
    public abstract fun isRegistered(): Boolean
    public abstract fun keyFor(sel: Selector): SelectionKey
    public abstract fun register(sel: Selector, ops: Int, att: Any): SelectionKey
    public abstract fun register(sel: Selector, ops: Int): SelectionKey
    public abstract fun configureBlocking(block: Boolean): SelectableChannel
    public abstract fun isBlocking(): Boolean
    public abstract fun blockingLock(): Any
}

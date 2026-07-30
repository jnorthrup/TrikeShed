@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING", "RedundantVisibilityModifier", "unused")

package borg.trikeshed.userspace.nio.channels

import borg.trikeshed.userspace.nio.channels.spi.SelectorProvider
// Generated from Amazon Corretto JDK 25 java.base NIO public/protected API via javap.
// Declarations intentionally mirror JDK taxonomy and contain no implementations.
public abstract class Selector {
    protected constructor()
    abstract fun isOpen(): Boolean
    abstract fun provider(): SelectorProvider
    abstract fun keys(): Set<SelectionKey>
    abstract fun selectedKeys(): Set<SelectionKey>
    abstract fun selectNow(): Int
    abstract fun select(timeout: Long): Int
    abstract fun select(): Int
    abstract fun select(action: (SelectionKey) -> Unit, timeout: Long): Int
    abstract fun select(action: (SelectionKey) -> Unit): Int
    abstract fun selectNow(action: (SelectionKey) -> Unit): Int
    abstract fun wakeup(): Selector
    abstract fun close(): Unit

    companion object {
        fun `open`(): Selector = SelectorProvider.provider().openSelector()
    }
}

@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING", "RedundantVisibilityModifier", "unused")

package borg.trikeshed.userspace.nio.channels

import borg.trikeshed.userspace.nio.channels.spi.SelectorProvider
// Generated from Amazon Corretto JDK 25 java.base NIO public/protected API via javap.
// Declarations intentionally mirror JDK taxonomy and contain no implementations.
public abstract class Selector {
    protected constructor()
    // TODO
    abstract open fun isOpen(): Boolean
    // TODO
    abstract open fun provider(): SelectorProvider
    // TODO
    abstract open fun keys(): Set<SelectionKey>
    // TODO
    abstract open fun selectedKeys(): Set<SelectionKey>
    // TODO
    abstract open fun selectNow(): Int
    // TODO
    abstract open fun select(timeout: Long): Int
    // TODO
    abstract open fun select(): Int
    // TODO
    abstract open fun select(action: (SelectionKey) -> Unit, timeout: Long): Int
    // TODO
    abstract open fun select(action: (SelectionKey) -> Unit): Int
    // TODO
    abstract open fun selectNow(action: (SelectionKey) -> Unit): Int
    // TODO
    abstract open fun wakeup(): Selector
    // TODO
    abstract open fun close(): Unit

    companion object {
        fun `open`(): Selector = TODO("NIO common stub")
    }
}

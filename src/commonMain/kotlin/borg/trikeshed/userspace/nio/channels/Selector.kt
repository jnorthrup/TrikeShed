@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING", "RedundantVisibilityModifier", "unused")

package borg.trikeshed.userspace.nio.channels

import borg.trikeshed.userspace.nio.channels.spi.SelectorProvider
// Generated from Amazon Corretto JDK 25 java.base NIO public/protected API via javap.
// Declarations intentionally mirror JDK taxonomy and contain no implementations.
public abstract class Selector {
    protected constructor()
    open fun isOpen(): Boolean = TODO("NIO common stub")
    open fun provider(): SelectorProvider = TODO("NIO common stub")
    open fun keys(): Set<SelectionKey> = TODO("NIO common stub")
    open fun selectedKeys(): Set<SelectionKey> = TODO("NIO common stub")
    open fun selectNow(): Int = TODO("NIO common stub")
    open fun select(timeout: Long): Int = TODO("NIO common stub")
    open fun select(): Int = TODO("NIO common stub")
    open fun select(action: (SelectionKey) -> Unit, timeout: Long): Int = TODO("NIO common stub")
    open fun select(action: (SelectionKey) -> Unit): Int = TODO("NIO common stub")
    open fun selectNow(action: (SelectionKey) -> Unit): Int = TODO("NIO common stub")
    open fun wakeup(): Selector = TODO("NIO common stub")
    open fun close(): Unit = TODO("NIO common stub")

    companion object {
        fun `open`(): Selector = TODO("NIO common stub")
    }
}

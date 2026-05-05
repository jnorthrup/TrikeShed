@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING", "RedundantVisibilityModifier", "unused")

package borg.trikeshed.userspace.nio.channels

// Generated from Amazon Corretto JDK 25 java.base NIO public/protected API via javap.
// Declarations intentionally mirror JDK taxonomy and contain no implementations.
public abstract class Selector {
    protected constructor()
    fun isOpen(): Boolean
    fun provider(): borg.trikeshed.userspace.nio.channels.spi.SelectorProvider
    fun keys(): java.util.Set<borg.trikeshed.userspace.nio.channels.SelectionKey>
    fun selectedKeys(): java.util.Set<borg.trikeshed.userspace.nio.channels.SelectionKey>
    fun selectNow(): Int
    fun select(p0: Long): Int
    fun select(): Int
    fun select(p0: java.util.function.Consumer<borg.trikeshed.userspace.nio.channels.SelectionKey>, p1: Long): Int
    fun select(p0: java.util.function.Consumer<borg.trikeshed.userspace.nio.channels.SelectionKey>): Int
    fun selectNow(p0: java.util.function.Consumer<borg.trikeshed.userspace.nio.channels.SelectionKey>): Int
    fun wakeup(): borg.trikeshed.userspace.nio.channels.Selector
    fun close(): Unit
    companion object {
        fun `open`(): borg.trikeshed.userspace.nio.channels.Selector
    }
}

@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING", "RedundantVisibilityModifier", "unused")

package borg.trikeshed.userspace.nio.channels

// Generated from Amazon Corretto JDK 25 java.base NIO public/protected API via javap.
// Declarations intentionally mirror JDK taxonomy and contain no implementations.
public abstract class SelectableChannel : borg.trikeshed.userspace.nio.channels.spi.AbstractInterruptibleChannel, borg.trikeshed.userspace.nio.channels.Channel {
    protected constructor()
    fun provider(): borg.trikeshed.userspace.nio.channels.spi.SelectorProvider
    fun validOps(): Int
    fun isRegistered(): Boolean
    fun keyFor(p0: borg.trikeshed.userspace.nio.channels.Selector): borg.trikeshed.userspace.nio.channels.SelectionKey
    fun register(p0: borg.trikeshed.userspace.nio.channels.Selector, p1: Int, p2: Any): borg.trikeshed.userspace.nio.channels.SelectionKey
    fun register(p0: borg.trikeshed.userspace.nio.channels.Selector, p1: Int): borg.trikeshed.userspace.nio.channels.SelectionKey
    fun configureBlocking(p0: Boolean): borg.trikeshed.userspace.nio.channels.SelectableChannel
    fun isBlocking(): Boolean
    fun blockingLock(): Any
}

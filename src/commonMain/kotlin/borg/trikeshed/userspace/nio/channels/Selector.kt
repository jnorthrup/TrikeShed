@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING", "RedundantVisibilityModifier", "unused")

package borg.trikeshed.userspace.nio.channels

// Generated from Amazon Corretto JDK 25 java.base NIO public/protected API via javap.
// Declarations intentionally mirror JDK taxonomy and contain no implementations.
public abstract class Selector {
    protected constructor()
    fun isOpen(): Boolean = TODO("NIO common stub")
    fun provider(): borg.trikeshed.userspace.nio.channels.spi.SelectorProvider = TODO("NIO common stub")
    fun keys(): java.util.Set<borg.trikeshed.userspace.nio.channels.SelectionKey> = TODO("NIO common stub")
    fun selectedKeys(): java.util.Set<borg.trikeshed.userspace.nio.channels.SelectionKey> = TODO("NIO common stub")
    fun selectNow(): Int = TODO("NIO common stub")
    fun select(p0: Long): Int = TODO("NIO common stub")
    fun select(): Int = TODO("NIO common stub")
    fun select(p0: java.util.function.Consumer<borg.trikeshed.userspace.nio.channels.SelectionKey>, p1: Long): Int = TODO("NIO common stub")
    fun select(p0: java.util.function.Consumer<borg.trikeshed.userspace.nio.channels.SelectionKey>): Int = TODO("NIO common stub")
    fun selectNow(p0: java.util.function.Consumer<borg.trikeshed.userspace.nio.channels.SelectionKey>): Int = TODO("NIO common stub")
    fun wakeup(): borg.trikeshed.userspace.nio.channels.Selector = TODO("NIO common stub")
    fun close(): Unit = TODO("NIO common stub")
    companion object {
        fun `open`(): borg.trikeshed.userspace.nio.channels.Selector = TODO("NIO common stub")
    }
}

@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING", "RedundantVisibilityModifier", "unused")

package borg.trikeshed.userspace.nio.channels

// Generated from Amazon Corretto JDK 25 java.base NIO public/protected API via javap.
// Declarations intentionally mirror JDK taxonomy and contain no implementations.
expect abstract class SelectionKey {
    protected constructor()
    fun channel(): borg.trikeshed.userspace.nio.channels.SelectableChannel
    fun selector(): borg.trikeshed.userspace.nio.channels.Selector
    fun isValid(): Boolean
    fun cancel(): Unit
    fun interestOps(): Int
    fun interestOps(p0: Int): borg.trikeshed.userspace.nio.channels.SelectionKey
    fun interestOpsOr(p0: Int): Int
    fun interestOpsAnd(p0: Int): Int
    fun readyOps(): Int
    fun isReadable(): Boolean
    fun isWritable(): Boolean
    fun isConnectable(): Boolean
    fun isAcceptable(): Boolean
    fun attach(p0: Any): Any
    fun attachment(): Any
    companion object {
        val OP_READ: Int
        val OP_WRITE: Int
        val OP_CONNECT: Int
        val OP_ACCEPT: Int
    }
}

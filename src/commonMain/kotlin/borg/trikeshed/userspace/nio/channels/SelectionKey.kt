@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING", "RedundantVisibilityModifier", "unused")

package borg.trikeshed.userspace.nio.channels

// Generated from Amazon Corretto JDK 25 java.base NIO public/protected API via javap.
// Declarations intentionally mirror JDK taxonomy and contain no implementations.
public abstract class SelectionKey {
    protected constructor()
    abstract fun channel(): SelectableChannel
    abstract fun selector(): Selector
    abstract fun isValid(): Boolean
    abstract fun cancel(): Unit
    abstract fun interestOps(): Int
    abstract fun interestOps(ops: Int): SelectionKey
    abstract fun interestOpsOr(ops: Int): Int
    abstract fun interestOpsAnd(ops: Int): Int
    abstract fun readyOps(): Int
    abstract fun isReadable(): Boolean
    abstract fun isWritable(): Boolean
    abstract fun isConnectable(): Boolean
    abstract fun isAcceptable(): Boolean
    abstract fun attach(ob: Any): Any
    abstract fun attachment(): Any

    companion object {
        val OP_READ: Int = 1
        val OP_WRITE: Int = 4
        val OP_CONNECT: Int = 8
        val OP_ACCEPT: Int = 16
    }
}

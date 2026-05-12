@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING", "RedundantVisibilityModifier", "unused")

package borg.trikeshed.userspace.nio.channels

// Generated from Amazon Corretto JDK 25 java.base NIO public/protected API via javap.
// Declarations intentionally mirror JDK taxonomy and contain no implementations.
public abstract class SelectionKey {
    protected constructor()
    // TODO
    abstract open fun channel(): SelectableChannel
    // TODO
    abstract open fun selector(): Selector
    // TODO
    abstract open fun isValid(): Boolean
    // TODO
    abstract open fun cancel(): Unit
    // TODO
    abstract open fun interestOps(): Int
    // TODO
    abstract open fun interestOps(ops: Int): SelectionKey
    // TODO
    abstract open fun interestOpsOr(ops: Int): Int
    // TODO
    abstract open fun interestOpsAnd(ops: Int): Int
    // TODO
    abstract open fun readyOps(): Int
    // TODO
    abstract open fun isReadable(): Boolean
    // TODO
    abstract open fun isWritable(): Boolean
    // TODO
    abstract open fun isConnectable(): Boolean
    // TODO
    abstract open fun isAcceptable(): Boolean
    // TODO
    abstract open fun attach(ob: Any): Any
    // TODO
    abstract open fun attachment(): Any

    companion object {
        val OP_READ: Int = TODO("NIO common stub")
        val OP_WRITE: Int = TODO("NIO common stub")
        val OP_CONNECT: Int = TODO("NIO common stub")
        val OP_ACCEPT: Int = TODO("NIO common stub")
    }
}

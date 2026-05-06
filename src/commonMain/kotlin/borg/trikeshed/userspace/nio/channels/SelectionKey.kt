@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING", "RedundantVisibilityModifier", "unused")

package borg.trikeshed.userspace.nio.channels

// Generated from Amazon Corretto JDK 25 java.base NIO public/protected API via javap.
// Declarations intentionally mirror JDK taxonomy and contain no implementations.
public abstract class SelectionKey {
    protected constructor()
    open fun channel(): SelectableChannel = TODO("NIO common stub")
    open fun selector(): Selector = TODO("NIO common stub")
    open fun isValid(): Boolean = TODO("NIO common stub")
    open fun cancel(): Unit = TODO("NIO common stub")
    open fun interestOps(): Int = TODO("NIO common stub")
    open fun interestOps(ops: Int): SelectionKey = TODO("NIO common stub")
    open fun interestOpsOr(ops: Int): Int = TODO("NIO common stub")
    open fun interestOpsAnd(ops: Int): Int = TODO("NIO common stub")
    open fun readyOps(): Int = TODO("NIO common stub")
    open fun isReadable(): Boolean = TODO("NIO common stub")
    open fun isWritable(): Boolean = TODO("NIO common stub")
    open fun isConnectable(): Boolean = TODO("NIO common stub")
    open fun isAcceptable(): Boolean = TODO("NIO common stub")
    open fun attach(ob: Any): Any = TODO("NIO common stub")
    open fun attachment(): Any = TODO("NIO common stub")

    companion object {
        val OP_READ: Int = TODO("NIO common stub")
        val OP_WRITE: Int = TODO("NIO common stub")
        val OP_CONNECT: Int = TODO("NIO common stub")
        val OP_ACCEPT: Int = TODO("NIO common stub")
    }
}

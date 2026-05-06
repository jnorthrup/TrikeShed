@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING", "RedundantVisibilityModifier", "unused")

package borg.trikeshed.userspace.nio.channels

// Generated from Amazon Corretto JDK 25 java.base NIO public/protected API via javap.
// Declarations intentionally mirror JDK taxonomy and contain no implementations.
public abstract class SelectionKey {
    protected constructor()
    fun channel(): borg.trikeshed.userspace.nio.channels.SelectableChannel = TODO("NIO common stub")
    fun selector(): borg.trikeshed.userspace.nio.channels.Selector = TODO("NIO common stub")
    fun isValid(): Boolean = TODO("NIO common stub")
    fun cancel(): Unit = TODO("NIO common stub")
    fun interestOps(): Int = TODO("NIO common stub")
    fun interestOps(p0: Int): borg.trikeshed.userspace.nio.channels.SelectionKey = TODO("NIO common stub")
    fun interestOpsOr(p0: Int): Int = TODO("NIO common stub")
    fun interestOpsAnd(p0: Int): Int = TODO("NIO common stub")
    fun readyOps(): Int = TODO("NIO common stub")
    fun isReadable(): Boolean = TODO("NIO common stub")
    fun isWritable(): Boolean = TODO("NIO common stub")
    fun isConnectable(): Boolean = TODO("NIO common stub")
    fun isAcceptable(): Boolean = TODO("NIO common stub")
    fun attach(p0: Any): Any = TODO("NIO common stub")
    fun attachment(): Any = TODO("NIO common stub")
    companion object {
        val OP_READ: Int = TODO("NIO common stub")
        val OP_WRITE: Int = TODO("NIO common stub")
        val OP_CONNECT: Int = TODO("NIO common stub")
        val OP_ACCEPT: Int = TODO("NIO common stub")
    }
}

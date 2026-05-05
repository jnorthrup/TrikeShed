@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING", "RedundantVisibilityModifier", "unused")

package borg.trikeshed.userspace.nio.channels

// Generated from Amazon Corretto JDK 25 java.base NIO public/protected API via javap.
// Declarations intentionally mirror JDK taxonomy and contain no implementations.
public interface AsynchronousByteChannel : borg.trikeshed.userspace.nio.channels.AsynchronousChannel {
    fun <A> read(p0: borg.trikeshed.userspace.nio.ByteBuffer, p1: A, p2: borg.trikeshed.userspace.nio.channels.CompletionHandler<java.lang.Integer, in A>): Unit
    fun read(p0: borg.trikeshed.userspace.nio.ByteBuffer): java.util.concurrent.Future<java.lang.Integer>
    fun <A> write(p0: borg.trikeshed.userspace.nio.ByteBuffer, p1: A, p2: borg.trikeshed.userspace.nio.channels.CompletionHandler<java.lang.Integer, in A>): Unit
    fun write(p0: borg.trikeshed.userspace.nio.ByteBuffer): java.util.concurrent.Future<java.lang.Integer>
}

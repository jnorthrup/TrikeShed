@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING", "RedundantVisibilityModifier", "unused")

package borg.trikeshed.userspace.nio.channels

// Generated from Amazon Corretto JDK 25 java.base NIO public/protected API via javap.
// Declarations intentionally mirror JDK taxonomy and contain no implementations.
public abstract class AsynchronousFileChannel {
    protected constructor()
    fun size(): Long
    fun truncate(p0: Long): borg.trikeshed.userspace.nio.channels.AsynchronousFileChannel
    fun force(p0: Boolean): Unit
    fun <A> lock(p0: Long, p1: Long, p2: Boolean, p3: A, p4: borg.trikeshed.userspace.nio.channels.CompletionHandler<borg.trikeshed.userspace.nio.channels.FileLock, in A>): Unit
    fun <A> lock(p0: A, p1: borg.trikeshed.userspace.nio.channels.CompletionHandler<borg.trikeshed.userspace.nio.channels.FileLock, in A>): Unit
    fun lock(p0: Long, p1: Long, p2: Boolean): java.util.concurrent.Future<borg.trikeshed.userspace.nio.channels.FileLock>
    fun lock(): java.util.concurrent.Future<borg.trikeshed.userspace.nio.channels.FileLock>
    fun tryLock(p0: Long, p1: Long, p2: Boolean): borg.trikeshed.userspace.nio.channels.FileLock
    fun tryLock(): borg.trikeshed.userspace.nio.channels.FileLock
    fun <A> read(p0: borg.trikeshed.userspace.nio.ByteBuffer, p1: Long, p2: A, p3: borg.trikeshed.userspace.nio.channels.CompletionHandler<java.lang.Integer, in A>): Unit
    fun read(p0: borg.trikeshed.userspace.nio.ByteBuffer, p1: Long): java.util.concurrent.Future<java.lang.Integer>
    fun <A> write(p0: borg.trikeshed.userspace.nio.ByteBuffer, p1: Long, p2: A, p3: borg.trikeshed.userspace.nio.channels.CompletionHandler<java.lang.Integer, in A>): Unit
    fun write(p0: borg.trikeshed.userspace.nio.ByteBuffer, p1: Long): java.util.concurrent.Future<java.lang.Integer>
    companion object {
        fun `open`(p0: borg.trikeshed.userspace.nio.file.Path, p1: java.util.Set<out borg.trikeshed.userspace.nio.file.OpenOption>, p2: java.util.concurrent.ExecutorService, vararg p3: borg.trikeshed.userspace.nio.file.attribute.FileAttribute<*>): borg.trikeshed.userspace.nio.channels.AsynchronousFileChannel
        fun `open`(p0: borg.trikeshed.userspace.nio.file.Path, vararg p1: borg.trikeshed.userspace.nio.file.OpenOption): borg.trikeshed.userspace.nio.channels.AsynchronousFileChannel
    }
}

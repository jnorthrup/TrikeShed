@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING", "RedundantVisibilityModifier", "unused")

package borg.trikeshed.userspace.nio.channels

import borg.trikeshed.userspace.nio.ByteBuffer
import borg.trikeshed.lib.ByteSeries
import borg.trikeshed.userspace.nio.file.Path
import borg.trikeshed.userspace.nio.file.OpenOption
import borg.trikeshed.userspace.nio.file.attribute.FileAttribute

// Generated from Amazon Corretto JDK 25 java.base NIO public/protected API via javap.
// Declarations intentionally mirror JDK taxonomy and contain no implementations.
public abstract class AsynchronousFileChannel {
    protected constructor()
    abstract fun size(): Long
    abstract fun truncate(size: Long): AsynchronousFileChannel
    abstract fun force(metaData: Boolean): Unit
    abstract fun <A> lock(position: Long, size: Long, shared: Boolean, attachment: A, handler: CompletionHandler<FileLock, in A>): Unit
    abstract fun <A> lock(attachment: A, handler: CompletionHandler<FileLock, in A>): Unit
    abstract fun lock(position: Long, size: Long, shared: Boolean): FileLock
    abstract fun lock(): FileLock
    abstract fun tryLock(position: Long, size: Long, shared: Boolean): FileLock
    abstract fun tryLock(): FileLock
    abstract fun <A> read(dst: ByteBuffer, position: Long, attachment: A, handler: CompletionHandler<Int, in A>): Unit
    abstract fun read(dst: ByteBuffer, position: Long): Int
    abstract fun <A> write(src: ByteSeries, position: Long, attachment: A, handler: CompletionHandler<Int, in A>): Unit
    abstract fun write(src: ByteSeries, position: Long): Int
    companion object {
        fun `open`(path: Path, options: Set<OpenOption>, vararg attrs: FileAttribute<*>): AsynchronousFileChannel = TODO("NIO stub - no AsyncFileChannel")
        fun `open`(path: Path, vararg options: OpenOption): AsynchronousFileChannel = `open`(path, options.toSet())
    }
}

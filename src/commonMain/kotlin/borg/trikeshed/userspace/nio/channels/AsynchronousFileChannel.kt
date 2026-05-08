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
    fun size(): Long = TODO("NIO common stub")
    fun truncate(size: Long): AsynchronousFileChannel = TODO("NIO common stub")
    fun force(metaData: Boolean): Unit = TODO("NIO common stub")
    fun <A> lock(position: Long, size: Long, shared: Boolean, attachment: A, handler: CompletionHandler<FileLock, in A>): Unit = TODO("NIO common stub")
    fun <A> lock(attachment: A, handler: CompletionHandler<FileLock, in A>): Unit = TODO("NIO common stub")
    fun lock(position: Long, size: Long, shared: Boolean): FileLock = TODO("NIO common stub")
    fun lock(): FileLock = TODO("NIO common stub")
    fun tryLock(position: Long, size: Long, shared: Boolean): FileLock = TODO("NIO common stub")
    fun tryLock(): FileLock = TODO("NIO common stub")
    fun <A> read(dst: ByteBuffer, position: Long, attachment: A, handler: CompletionHandler<Int, in A>): Unit = TODO("NIO common stub")
    fun read(dst: ByteBuffer, position: Long): Int = TODO("NIO common stub")
    fun <A> write(src: ByteSeries, position: Long, attachment: A, handler: CompletionHandler<Int, in A>): Unit = TODO("NIO common stub")
    fun write(src: ByteSeries, position: Long): Int = TODO("NIO common stub")
    companion object {
        fun `open`(path: Path, options: Set<OpenOption>, vararg attrs: FileAttribute<*>): AsynchronousFileChannel = TODO("NIO common stub")
        fun `open`(path: Path, vararg options: OpenOption): AsynchronousFileChannel = TODO("NIO common stub")
    }
}

@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING", "RedundantVisibilityModifier", "unused")

package borg.trikeshed.userspace.nio.channels

import borg.trikeshed.userspace.ByteRegion
import borg.trikeshed.lib.ByteSeries
import borg.trikeshed.userspace.nio.file.Path
import borg.trikeshed.userspace.nio.file.OpenOption
import borg.trikeshed.userspace.nio.file.attribute.FileAttribute
import borg.trikeshed.userspace.nio.channels.spi.AbstractInterruptibleChannel

// Generated from Amazon Corretto JDK 25 java.base NIO public/protected API via javap.
// Declarations intentionally mirror JDK taxonomy and contain no implementations.
public abstract class FileChannel protected constructor() : AbstractInterruptibleChannel(), SeekableByteChannel, GatheringByteChannel, ScatteringByteChannel {
    public abstract override fun close()
    public abstract override fun isOpen(): Boolean
    public abstract override fun read(dst: ByteRegion): Int
    public abstract override fun read(dsts: Array<out ByteRegion>, offset: Int, length: Int): Long
    public abstract override fun read(dsts: Array<out ByteRegion>): Long
    public abstract override fun write(src: ByteSeries): Int
    public abstract override fun write(srcs: Array<out ByteSeries>, offset: Int, length: Int): Long
    public abstract override fun write(srcs: Array<out ByteSeries>): Long
    public abstract override fun position(): Long
    public abstract override fun position(newPosition: Long): FileChannel
    public abstract override fun size(): Long
    public abstract override fun truncate(size: Long): FileChannel
    fun force(metaData: Boolean): Unit = TODO("NIO common stub")
    fun transferTo(position: Long, count: Long, target: WritableByteChannel): Long = TODO("NIO common stub")
    fun transferFrom(src: ReadableByteChannel, position: Long, count: Long): Long = TODO("NIO common stub")
    fun read(dst: ByteRegion, position: Long): Int = TODO("NIO common stub")
    fun write(src: ByteSeries, position: Long): Int = TODO("NIO common stub")
    fun map(mode: FileChannel.MapMode, position: Long, size: Long): borg.trikeshed.userspace.nio.ByteBuffer = TODO("NIO common stub")
    fun lock(position: Long, size: Long, shared: Boolean): FileLock = TODO("NIO common stub")
    fun lock(): FileLock = TODO("NIO common stub")
    fun tryLock(position: Long, size: Long, shared: Boolean): FileLock = TODO("NIO common stub")
    fun tryLock(): FileLock = TODO("NIO common stub")

    companion object {
        fun `open`(path: Path, options: Set<OpenOption>, vararg attrs: FileAttribute<*>): FileChannel = TODO("NIO common stub")
        fun `open`(path: Path, vararg options: OpenOption): FileChannel = TODO("NIO common stub")
    }

    public open class MapMode {
        override fun toString(): String = TODO("NIO common stub")

        companion object {
            val READ_ONLY: FileChannel.MapMode = TODO("NIO common stub")
            val READ_WRITE: FileChannel.MapMode = TODO("NIO common stub")
            val PRIVATE: FileChannel.MapMode = TODO("NIO common stub")
        }
    }
}

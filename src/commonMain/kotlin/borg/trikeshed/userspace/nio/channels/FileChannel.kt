@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING", "RedundantVisibilityModifier", "unused", "NonAsciiCharacters")

package borg.trikeshed.userspace.nio.channels

import borg.trikeshed.userspace.nio.ByteBuffer
import borg.trikeshed.userspace.nio.IOException
import borg.trikeshed.userspace.nio.UringIOException
import borg.trikeshed.userspace.UringOp
import borg.trikeshed.userspace.UringOp.Companion.Submissions
import borg.trikeshed.userspace.nio.file.Path
import borg.trikeshed.userspace.nio.file.OpenOption
import borg.trikeshed.userspace.nio.file.StandardOpenOption
import borg.trikeshed.userspace.nio.file.attribute.FileAttribute
import borg.trikeshed.userspace.nio.file.attribute.PosixFilePermission
import borg.trikeshed.userspace.nio.channels.spi.AbstractInterruptibleChannel
import borg.trikeshed.userspace.nio.file.File

/**
 * FileChannel wired behind UringFacade.
 *
 * Open, read, write, sync and close route through FunctionalUringFacade.
 */
public abstract class FileChannel protected constructor() : AbstractInterruptibleChannel(), SeekableByteChannel, GatheringByteChannel, ScatteringByteChannel {
    public abstract override fun close()
    public abstract override fun isOpen(): Boolean
    public abstract override fun read(dst: ByteBuffer): Int
    public abstract override fun read(dsts: Array<out ByteBuffer>, offset: Int, length: Int): Long
    public abstract override fun read(dsts: Array<out ByteBuffer>): Long
    public abstract override fun write(src: ByteBuffer): Int
    public abstract override fun write(srcs: Array<out ByteBuffer>, offset: Int, length: Int): Long
    public abstract override fun write(srcs: Array<out ByteBuffer>): Long
    public abstract override fun position(): Long
    public abstract override fun position(newPosition: Long): FileChannel
    public abstract override fun size(): Long
    public abstract override fun truncate(size: Long): FileChannel
    public abstract fun force(metaData: Boolean)
    public abstract fun transferTo(position: Long, count: Long, target: WritableByteChannel): Long
    public abstract fun transferFrom(src: ReadableByteChannel, position: Long, count: Long): Long
    public abstract fun read(dst: ByteBuffer, position: Long): Int
    public abstract fun write(src: ByteBuffer, position: Long): Int
    public abstract fun map(mode: MapMode, position: Long, size: Long): borg.trikeshed.userspace.MemoryMapping
    public abstract fun lock(position: Long, size: Long, shared: Boolean): FileLock
    public abstract fun lock(): FileLock
    public abstract fun tryLock(position: Long, size: Long, shared: Boolean): FileLock?
    public abstract fun tryLock(): FileLock?

    companion object {
        fun open(path: Path, options: Set<OpenOption>, vararg attrs: FileAttribute<*>): FileChannel =
            open(path.toString(), options, *attrs)

        fun open(path: String, options: Set<OpenOption>, vararg attrs: FileAttribute<*>): FileChannel {
            return open(path, options, permissions = creationPermissions(attrs)) { UringChannels.open() }
        }
        fun open(path: Path, vararg options: OpenOption): FileChannel = open(path, options.toSet())
        fun open(path: String, vararg options: OpenOption): FileChannel = open(path, options.toSet())

        internal fun open(
            path: String,
            options: Set<OpenOption>,
            permissions: Int = 438,
            channelFactory: () -> UringChannel,
        ): FileChannel {
            val supported = setOf(StandardOpenOption.READ, StandardOpenOption.WRITE, StandardOpenOption.CREATE,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.TRUNCATE_EXISTING)
            require(options.all { it in supported }) { "Unsupported file open option" }
            val write = StandardOpenOption.WRITE in options
            require(write || options.none {
                it == StandardOpenOption.CREATE || it == StandardOpenOption.CREATE_NEW || it == StandardOpenOption.TRUNCATE_EXISTING
            }) { "Creation and truncation require WRITE" }

            var flags = if (write) { if (StandardOpenOption.READ in options) 2 else 1 } else 0
            if (StandardOpenOption.CREATE in options || StandardOpenOption.CREATE_NEW in options) flags = flags or 64
            if (StandardOpenOption.CREATE_NEW in options) flags = flags or 128
            if (StandardOpenOption.TRUNCATE_EXISTING in options) flags = flags or 512
            require(permissions in 0..511)
            val submission = Submissions.openat(path, flags, 0).copy(operationFlags = permissions)
            val channel = channelFactory()
            try {
                channel.enqueue(submission)
                channel.submit()
                val completion = channel.wait(1).singleOrNull()
                if (completion == null || completion.userData != submission.userData) throw IOException("Invalid OPENAT completion: $path")
                if (completion.res < 0) throw UringIOException(UringOp.OPENAT, completion.res, path)
                val file = File.fromFd(completion.res)
                if (!file.isOpen()) throw IOException("OPENAT returned a closed descriptor: ${completion.res}")
                return UringFileChannel(file, channel, readable = !write || StandardOpenOption.READ in options, writable = write)
            } catch (failure: Throwable) {
                runCatching { channel.closeNow() }.exceptionOrNull()?.let { if (it !== failure) failure.addSuppressed(it) }
                throw failure
            }
        }

        private fun creationPermissions(attrs: Array<out FileAttribute<*>>): Int {
            require(attrs.size <= 1 && attrs.all { it.name() == "posix:permissions" }) { "Unsupported file attributes" }
            if (attrs.isEmpty()) return 438
            val values = attrs.single().value() as? Set<*> ?: error("POSIX permissions must be a set")
            require(values.all { it is PosixFilePermission })
            return values.fold(0) { mask, permission -> mask or (1 shl (8 - (permission as PosixFilePermission).ordinal)) }
        }
    }

    public open class MapMode {
        override fun toString(): String = "MapMode"
        companion object {
            val READ_ONLY: FileChannel.MapMode = object : MapMode() {}
            val READ_WRITE: FileChannel.MapMode = object : MapMode() {}
            val PRIVATE: FileChannel.MapMode = object : MapMode() {}
        }
    }
}

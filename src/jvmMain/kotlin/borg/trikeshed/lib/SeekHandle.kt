package borg.trikeshed.lib

import borg.trikeshed.userspace.ByteRegion
import borg.trikeshed.lib.ByteSeries
import borg.trikeshed.lib.SeekHandle

/** JVM stub. Route through NIO FileChannel. */
actual fun platformSeekHandle(): SeekHandle = object : SeekHandle {
    override fun open(filename: CharSequence, readOnly: Boolean): Long = -1
    override fun close(handle: Long) {}
    override fun pread(handle: Long, dst: ByteRegion, fileOffset: Long): Int = -1
    override fun pwrite(handle: Long, src: ByteSeries, fileOffset: Long): Int = -1
    override fun size(handle: Long): Long = -1
    override fun read(handle: Long, dst: ByteRegion): Int = -1
    override fun write(handle: Long, src: ByteSeries): Int = -1
    override fun seek(handle: Long, position: Long): Long = -1
}

actual fun ioUringHandle(): SeekHandle? = null

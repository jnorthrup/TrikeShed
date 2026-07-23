package borg.trikeshed.couch.wal

import java.nio.channels.FileChannel
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * JVM Panama-based mmap Write-Ahead Log implementation.
 */
actual class AppendWal actual constructor(path: String) {
    private val channel = FileChannel.open(
        Paths.get(path),
        StandardOpenOption.CREATE,
        StandardOpenOption.READ,
        StandardOpenOption.WRITE
    )
    private var offset = channel.size()

    // We keep a single confined arena for mapping slices
    private val arena = Arena.ofShared()

    actual suspend fun append(payload: ByteArray): Long = withContext(Dispatchers.IO) {
        val currentOffset = offset
        val len = payload.size.toLong()

        // Map exactly the region we need to write
        val segment = channel.map(FileChannel.MapMode.READ_WRITE, currentOffset, len, arena)

        // Copy data into the mapped segment
        val src = MemorySegment.ofArray(payload)
        MemorySegment.copy(src, 0L, segment, 0L, len)

        // Force the segment to write to disk
        segment.force()

        offset += len
        currentOffset
    }

    actual fun close() {
        arena.close()
        channel.close()
    }
}

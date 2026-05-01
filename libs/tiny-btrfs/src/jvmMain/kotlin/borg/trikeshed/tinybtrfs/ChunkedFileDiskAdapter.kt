package borg.trikeshed.tinybtrfs

import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong
import java.io.Closeable

/**
 * ChunkedFileDiskAdapter: single-file chunk allocator for JVM platforms.
 *
 * - Node ids are returned as "off:<offset>" strings.
 * - Chunks are fixed-size (chunkSize). allocateNode() reserves the next chunk
 *   (or reuses a freed chunk). writeNode/readNode operate at the chunk offset.
 * - Allocation strategy: on-demand (append) or preallocate (set file length).
 *
 * This meets the "calloc disk chunks as needed" requirement without mounting
 * block devices. Keep in mind free-list is in-memory for simplicity; a
 * production allocator should persist metadata.
 */
class ChunkedFileDiskAdapter(
   val file: File,
   val chunkSize: Int = 4096,
    preallocateSize: Long = 0L,
    allocationStrategy: String = "on_demand",
) : DiskAdapter, Closeable {

    val metaFile = File(file.path + ".meta")
    val raf: RandomAccessFile
    val nextOffset: AtomicLong
    val freeList = ConcurrentLinkedQueue<Long>()

    init {
        file.parentFile?.mkdirs()
        if (!file.exists()) file.createNewFile()
        if (preallocateSize > 0L && allocationStrategy == "preallocate_upfront") {
            RandomAccessFile(file, "rw").use { it.setLength(preallocateSize) }
        }

        raf = RandomAccessFile(file, "rw")
        nextOffset = AtomicLong(raf.length())

        if (metaFile.exists()) {
            val lines = metaFile.readLines()
            lines.forEach {
                if (it.isNotBlank()) freeList.add(it.toLong())
            }
        }
    }

   fun parseOffset(nodeId: String): Long {
        if (!nodeId.startsWith("off:")) throw IllegalArgumentException("invalid node id: $nodeId")
        return nodeId.substringAfter("off:").toLong()
    }

    override fun readNode(nodeId: String): ByteArray? {
        val offset = parseOffset(nodeId)
        synchronized(raf) {
            val fileLen = raf.length()
            if (offset >= fileLen) return null
            raf.seek(offset)
            val available = ((fileLen - offset).coerceAtMost(chunkSize.toLong())).toInt()
            val buf = ByteArray(available)
            raf.readFully(buf)
            return buf
        }
    }

    override fun writeNode(nodeId: String, bytes: ByteArray) {
        if (bytes.size > chunkSize) throw IllegalArgumentException("bytes exceed chunkSize: ${bytes.size} > $chunkSize")
        val offset = parseOffset(nodeId)
        synchronized(raf) {
            raf.seek(offset)
            raf.write(bytes)
            // Optionally zero-pad remainder of chunk to keep deterministic size; omitted for speed.
        }
    }

    override fun allocateNode(): String {
        val freed = freeList.poll()
        if (freed != null) return "off:$freed"
        val off = nextOffset.getAndAdd(chunkSize.toLong())
        synchronized(raf) {
            val needed = off + chunkSize
            if (raf.length() < needed) raf.setLength(needed)
        }
        return "off:$off"
    }

    override fun freeNode(nodeId: String) {
        val off = parseOffset(nodeId)
        freeList.add(off)
    }

    override fun close() {
        raf.close()
        saveMeta()
    }

    private fun saveMeta() {
        metaFile.writeText(freeList.joinToString("\n"))
    }
}

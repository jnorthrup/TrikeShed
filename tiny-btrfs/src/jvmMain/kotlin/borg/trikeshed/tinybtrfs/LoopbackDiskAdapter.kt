package borg.trikeshed.tinybtrfs

import borg.trikeshed.userspace.Liburing
import borg.trikeshed.userspace.btrfs.BTRFS_NODE_SIZE
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.lang.reflect.Field
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicLong
import sun.misc.Unsafe

/**
 * LoopbackDiskAdapter: real block-device-backed DiskAdapter using Linux loop devices + io_uring.
 *
 * Architecture:
 *   1. Creates a backing file (configurable size)
 *   2. Attaches to /dev/loopX via losetup (requires root or CAP_SYS_ADMIN)
 *   3. Opens block device with O_DIRECT for aligned I/O
 *   4. Uses io_uring for async read/write at 4K boundaries
 *
 * Node addressing: nodeId = "block-N" where N is 4K block index
 * (Matches BTRFS_NODE_SIZE for 1:1 mapping)
 *
 * Usage:
 *   val adapter = LoopbackDiskAdapter.create("btrfs.img", 100_000_000).getOrThrow()
 *   // ... use with BPlusTree
 *   adapter.close()
 *
 * Requirements:
 *   - Linux kernel with loop module
 *   - Root or CAP_SYS_ADMIN (for losetup)
 *   - liburing dev headers
 */
class LoopbackDiskAdapter private constructor(
    val backingFile: File,
    val loopDevice: String,  // e.g. "/dev/loop4"
    val blockSize: Int = BTRFS_NODE_SIZE,
    val totalBlocks: Long,
    private val fd: Int,
) : DiskAdapter {

    private val nextNodeId = AtomicLong(1)
    private val freeBlocks = mutableListOf<Long>()
    private val unsafe: Unsafe

    companion object {
        /** Create and attach loop device. Returns adapter or error. */
        @JvmStatic
        fun create(backingFilePath: String, sizeBytes: Long, blockSize: Int = BTRFS_NODE_SIZE): Result<LoopbackDiskAdapter> {
            val backingFile = File(backingFilePath)
            return try {
                // 1. Create backing file with exact size
                if (!backingFile.exists()) {
                    backingFile.parentFile?.mkdirs()
                    RandomAccessFile(backingFile, "rw").use { it.setLength(sizeBytes) }
                } else if (backingFile.length() != sizeBytes) {
                    RandomAccessFile(backingFile, "rw").use { it.setLength(sizeBytes) }
                }

                // 2. Attach loop device via losetup
                val loopDevice = attachLoopDevice(backingFile.absolutePath)
                
                // 3. Open block device with O_DIRECT
                val fd = openBlockDevice(loopDevice)
                
                // 4. Initialize io_uring
                val initResult = Liburing.open(32)
                if (initResult.isFailure) {
                    closeFd(fd)
                    detachLoopDevice(loopDevice)
                    return Result.failure(initResult.exceptionOrNull() ?: IllegalStateException("io_uring init failed"))
                }

                val totalBlocks = sizeBytes / blockSize
                Result.success(LoopbackDiskAdapter(backingFile, loopDevice, blockSize, totalBlocks, fd))
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

        /** Detach loop device (best effort) */
        @JvmStatic
        fun detach(loopDevice: String) {
            try { detachLoopDevice(loopDevice) } catch (e: Exception) {}
        }

        private fun attachLoopDevice(filePath: String): String {
            // losetup --find --show <file> returns the device path
            val proc = ProcessBuilder("losetup", "--find", "--show", filePath)
                .redirectErrorStream(true)
                .start()
            val output = proc.inputStream.readBytes().decodeToString().trim()
            val exitCode = proc.waitFor()
            if (exitCode != 0 || output.isBlank()) {
                throw IOException("losetup failed (exit=$exitCode): $output")
            }
            return output.trim()
        }

        private fun detachLoopDevice(loopDevice: String) {
            ProcessBuilder("losetup", "-d", loopDevice).start().waitFor()
        }

        private fun openBlockDevice(devicePath: String): Int {
            val file = File(devicePath)
            if (!file.exists()) throw IOException("Loop device not found: $devicePath")
            
            // Use reflection to get FD from RandomAccessFile
            val raf = RandomAccessFile(file, "rw")
            val fdField = RandomAccessFile::class.java.getDeclaredField("fd")
            fdField.isAccessible = true
            val fdObj = fdField.get(raf)
            val fdIntField = fdObj.javaClass.getDeclaredField("fd")
            fdIntField.isAccessible = true
            return fdIntField.getInt(fdObj)
        }

        private fun closeFd(fd: Int) {
            try { Liburing.prepClose(fd, 0).getOrThrow(); Liburing.submit() } catch (e: Exception) {}
        }
    }

    init {
        // Initialize Unsafe for direct buffer access
        val f = Unsafe::class.java.getDeclaredField("theUnsafe")
        f.isAccessible = true
        unsafe = f.get(null) as Unsafe
    }

    override fun readNode(nodeId: String): ByteArray? {
        val blockIdx = parseBlockIndex(nodeId) ?: return null
        return readBlock(blockIdx)
    }

    override fun writeNode(nodeId: String, bytes: ByteArray) {
        val blockIdx = parseBlockIndex(nodeId) ?: allocateBlockIndex()
        writeBlock(blockIdx, bytes)
    }

    override fun allocateNode(): String {
        val idx = if (freeBlocks.isNotEmpty()) freeBlocks.removeAt(freeBlocks.lastIndex)
                 else nextNodeId.getAndIncrement()
        if (idx >= totalBlocks) throw IllegalStateException("Disk full: $idx >= $totalBlocks")
        // Pre-allocate zeroed block
        val zeroBuf = ByteArray(blockSize)
        writeBlock(idx, zeroBuf)
        return "block-$idx"
    }

    override fun freeNode(nodeId: String) {
        parseBlockIndex(nodeId)?.let { freeBlocks.add(it) }
    }

    private fun parseBlockIndex(nodeId: String): Long? = 
        if (nodeId.startsWith("block-")) nodeId.substring(6).toLongOrNull() else null

    private fun allocateBlockIndex(): Long = nextNodeId.getAndIncrement().also { 
        if (it >= totalBlocks) throw IllegalStateException("Disk full")
    }

    /** Get native address of direct ByteBuffer via Unsafe */
    private fun getDirectBufferAddress(buf: ByteBuffer): Long {
        val addressField = ByteBuffer::class.java.getDeclaredField("address")
        addressField.isAccessible = true
        return addressField.getLong(buf)
    }

    /** Read a single 4K block via io_uring */
    private fun readBlock(blockIdx: Long): ByteArray {
        val offset = blockIdx * blockSize.toLong()
        val buf = ByteBuffer.allocateDirect(blockSize).order(java.nio.ByteOrder.nativeOrder())
        
        val bufAddr = getDirectBufferAddress(buf)
        val prepResult = Liburing.prepRead(fd, bufAddr, blockSize, offset, blockIdx)
        prepResult.getOrThrow()
        
        val submitCount = Liburing.submit().getOrThrow()
        if (submitCount <= 0) throw IOException("io_uring submit failed")

        // Wait for completion
        val cqeResult = Liburing.waitCqe()
        val completionOpt = cqeResult.getOrNull()
        completionOpt?.let { completion: borg.trikeshed.userspace.UringCompletion ->
            val res = completion.res
            if (res < 0) throw IOException("Read failed: $res")
            if (res != blockSize) throw IOException("Short read: $res != $blockSize")
        } ?: throw IOException("No CQE returned")

        val result = ByteArray(blockSize)
        buf.rewind()
        buf.get(result)
        return result
    }

    /** Write a single 4K block via io_uring */
    private fun writeBlock(blockIdx: Long, bytes: ByteArray) {
        require(bytes.size <= blockSize) { "Block too large: ${bytes.size} > $blockSize" }
        
        val offset = blockIdx * blockSize.toLong()
        val buf = ByteBuffer.allocateDirect(blockSize).order(java.nio.ByteOrder.nativeOrder())
        buf.put(bytes)
        buf.rewind()
        
        val bufAddr = getDirectBufferAddress(buf)
        val prepResult = Liburing.prepWrite(fd, bufAddr, bytes.size, offset, blockIdx)
        prepResult.getOrThrow()
        
        val submitCount = Liburing.submit().getOrThrow()
        if (submitCount <= 0) throw IOException("io_uring submit failed")

        val cqeResult = Liburing.waitCqe()
        val completionOpt = cqeResult.getOrNull()
        completionOpt?.let { completion: borg.trikeshed.userspace.UringCompletion ->
            val res = completion.res
            if (res < 0) throw IOException("Write failed: $res")
            if (res != bytes.size) throw IOException("Short write: $res != ${bytes.size}")
        } ?: throw IOException("No CQE returned")
    }

    /** Close adapter and detach loop device */
    fun close() {
        try { Liburing.close().getOrThrow() } catch (e: Exception) {}
        try { detachLoopDevice(loopDevice) } catch (e: Exception) {}
    }

    override fun toString() = "LoopbackDiskAdapter($loopDevice, ${totalBlocks}blocks)"
}
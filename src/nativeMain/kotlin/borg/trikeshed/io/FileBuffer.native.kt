package borg.trikeshed.io

/**
 * an openable and closeable mmap file.
 *
 *  get has no side effects but put has undefined effects on size and sync
 */
actual class FileBuffer actual constructor(
    filename: String,
    initialOffset: Long,
    blkSize: Long,
    readOnly: Boolean,
) : LongSeries<Byte> {
    private var fd: Int = -1
    private var mapped: Long = 0L
    private var mappedSize: Long = 0L
    
    override actual val a: Long get() = size()
    override actual val b: (Long) -> Byte get() = { i -> get(i) }
    actual val filename: String = filename
    actual val initialOffset: Long = initialOffset
    actual val blkSize: Long = blkSize
    actual val readOnly: Boolean = readOnly

    actual fun close() {
        if (mapped != 0L) {
            munmap(mapped, mappedSize)
            mapped = 0L
        }
        if (fd >= 0) {
            close(fd)
            fd = -1
        }
    }

    actual fun open() {
        if (fd < 0) {
            fd = open(filename, if (readOnly) O_RDONLY else O_RDWR)
            if (fd < 0) throw IOException("Failed to open $filename")
            
            val fileSize = lseek(fd, 0, SEEK_END)
            lseek(fd, 0, SEEK_SET)
            
            mappedSize = if (blkSize < 0) fileSize else blkSize
            val prot = if (readOnly) PROT_READ else (PROT_READ or PROT_WRITE)
            
            mapped = mmap(
                null,
                mappedSize,
                prot,
                MAP_SHARED,
                fd,
                initialOffset
            )
            
            if (mapped == -1L) {
                close(fd)
                fd = -1
                throw IOException("Failed to mmap $filename")
            }
        }
    }

    actual fun isOpen(): Boolean = fd >= 0

    actual fun size(): Long = if (isOpen()) mappedSize else 0L

    actual fun get(index: Long): Byte {
        if (!isOpen()) throw IOException("Buffer not open")
        if (index < 0 || index >= mappedSize) throw IndexOutOfBoundsException()
        return getByte(mapped + index)
    }

    actual fun put(index: Long, value: Byte) {
        if (!isOpen()) throw IOException("Buffer not open") 
        if (readOnly) throw IOException("Buffer is read-only")
        if (index < 0 || index >= mappedSize) throw IndexOutOfBoundsException()
        putByte(mapped + index, value)
    }
    
    private external fun open(path: String, flags: Int): Int
    private external fun close(fd: Int): Int
    private external fun lseek(fd: Int, offset: Long, whence: Int): Long
    private external fun mmap(addr: Any?, length: Long, prot: Int, flags: Int, fd: Int, offset: Long): Long
    private external fun munmap(addr: Long, length: Long): Int
    private external fun getByte(addr: Long): Byte
    private external fun putByte(addr: Long, value: Byte)
    
    private companion object {
        const val O_RDONLY = 0
        const val O_RDWR = 2
        const val SEEK_SET = 0
        const val SEEK_END = 2
        const val PROT_READ = 1
        const val PROT_WRITE = 2
        const val MAP_SHARED = 1
    }
}

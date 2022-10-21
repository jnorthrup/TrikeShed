package borg.trikeshed.native

import platform.posix.SEEK_SET
import platform.posix.__off_t
import platform.posix.stat
import simple.PosixStatMode.*

interface HasDescriptor : HasPosixErr {
    val fd: Int

    /**
     * [manpage](https://www.man7.org/linux/man-pages/man2/read.2.html)
     */
    fun read(buf: ByteArray) = read64(buf).toUInt()
    fun read64(buf: ByteArray): ULong

    /**
     * [manpage](https://www.man7.org/linux/man-pages/man2/write.2.html)
     */
    fun write(buf: ByteArray) = write64(buf).toUInt()
    fun write64(buf: ByteArray): ULong

    /**
     * [manpage](https://www.man7.org/linux/man-pages/man2/seek.2.html)
     */
    fun seek(offset: __off_t, whence: Int = SEEK_SET): ULong

    /**
     * [manpage](https://www.man7.org/linux/man-pages/man2/close.2.html)
     */
    fun close(): Int

    var st_: stat?
    val st: stat

    val isDir get() = S_ISDIR(st.st_mode)
    val isChr get() = S_ISCHR(st.st_mode)
    val isBlk get() = S_ISBLK(st.st_mode)
    val isReg get() = S_ISREG(st.st_mode)
    val isFifo get() = S_ISFIFO(st.st_mode)
    val isLnk get() = S_ISLNK(st.st_mode)

}


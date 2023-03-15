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
    fun read(buf: ByteArray): UInt = read64(buf).toUInt()
    fun read64(buf: ByteArray): ULong

    /**
     * [manpage](https://www.man7.org/linux/man-pages/man2/write.2.html)
     */
    fun write(buf: ByteArray): UInt = write64(buf).toUInt()
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

    val isDir: Boolean get() = S_ISDIR(st.st_mode)
    val isChr: Boolean get() = S_ISCHR(st.st_mode)
    val isBlk: Boolean get() = S_ISBLK(st.st_mode)
    val isReg: Boolean get() = S_ISREG(st.st_mode)
    val isFifo: Boolean get() = S_ISFIFO(st.st_mode)
    val isLnk: Boolean get() = S_ISLNK(st.st_mode)

}


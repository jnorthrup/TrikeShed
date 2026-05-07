@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package borg.trikeshed

import borg.trikeshed.userspace.Liburing
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.posix.EINVAL
import platform.posix.ESPIPE
import platform.posix.close
import platform.posix.errno
import platform.posix.pread
import platform.posix.pwrite
import platform.posix.read
import platform.posix.write

internal object PosixUringIO {
    private var openAttemptedEntries: Int = 0
    private var uringAvailable: Boolean = false
    private var nextUserData: Long = 1

    fun isAvailable(entries: Int = 256): Boolean = ensureOpen(entries)

    fun readAt(fd: Int, bytes: ByteArray, start: Int, length: Int, offset: Long, entries: Int = 256): Int {
        if (length <= 0) return 0
        val uringResult = submit(entries) { address, userData ->
            Liburing.prepRead(fd, address, length, offset, userData)
        }(bytes, start)
        if (uringResult != Int.MIN_VALUE) return uringResult
        val direct = bytes.usePinned { pinned ->
            pread(fd, pinned.addressOf(start), length.toULong(), offset).toInt()
        }
        if (direct >= 0) return direct
        return if (errno == ESPIPE || errno == EINVAL) {
            bytes.usePinned { pinned ->
                read(fd, pinned.addressOf(start), length.toULong()).toInt()
            }
        } else direct
    }

    fun writeAt(fd: Int, bytes: ByteArray, start: Int, length: Int, offset: Long, entries: Int = 256): Int {
        if (length <= 0) return 0
        val uringResult = submit(entries) { address, userData ->
            Liburing.prepWrite(fd, address, length, offset, userData)
        }(bytes, start)
        if (uringResult != Int.MIN_VALUE) return uringResult
        val direct = bytes.usePinned { pinned ->
            pwrite(fd, pinned.addressOf(start), length.toULong(), offset).toInt()
        }
        if (direct >= 0) return direct
        return if (errno == ESPIPE || errno == EINVAL) {
            bytes.usePinned { pinned ->
                write(fd, pinned.addressOf(start), length.toULong()).toInt()
            }
        } else direct
    }

    fun closeFd(fd: Int, entries: Int = 256): Int {
        if (fd < 0) return -1
        val userData = nextUserData++
        if (ensureOpen(entries) && Liburing.prepClose(fd, userData).isSuccess) {
            val submitted = Liburing.submit().getOrNull()
            if (submitted != null && submitted > 0) {
                val completion = Liburing.waitCqe().getOrNull()
                if (completion != null) return completion.res
            }
        }
        return close(fd)
    }

    private fun ensureOpen(entries: Int): Boolean {
        if (entries <= openAttemptedEntries) return uringAvailable
        openAttemptedEntries = entries
        uringAvailable = Liburing.open(entries, 0).isSuccess
        return uringAvailable
    }

    private fun submit(
        entries: Int,
        prep: (address: Long, userData: Long) -> Result<Unit>,
    ): (ByteArray, Int) -> Int = { bytes, start ->
        if (!ensureOpen(entries)) {
            Int.MIN_VALUE
        } else {
            bytes.usePinned { pinned ->
                val userData = nextUserData++
                val address = pinned.addressOf(start).rawValue.toLong()
                if (prep(address, userData).isFailure) {
                    Int.MIN_VALUE
                } else {
                    val submitted = Liburing.submit().getOrNull()
                    if (submitted == null || submitted <= 0) {
                        Int.MIN_VALUE
                    } else {
                        Liburing.waitCqe().getOrNull()?.res ?: Int.MIN_VALUE
                    }
                }
            }
        }
    }
}

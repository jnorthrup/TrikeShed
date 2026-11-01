package borg.trikeshed.couch.isam

import borg.trikeshed.userspace.nio.posix.trikeshed_crc32c
import borg.trikeshed.userspace.nio.posix.trikeshed_crc32c_hw
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned

/** Whether this CPU has the CRC32c instruction (posix_spawn.def asks cpuid or the auxiliary vector once). */
@OptIn(ExperimentalForeignApi::class)
val CRC32C_HARDWARE: Boolean = trikeshed_crc32c_hw() != 0

/** The CPU's CRC32c instruction, eight bytes per step, through one C call; the table on a CPU without it. */
@OptIn(ExperimentalForeignApi::class)
actual fun crc32c(bytes: ByteArray, offset: Int, length: Int): Int {
    if (offset < 0 || length < 0 || offset > bytes.size - length) throw IndexOutOfBoundsException("crc32c($offset, $length) of ${bytes.size} bytes")
    if (length == 0 || !CRC32C_HARDWARE) return Crc32C.software(bytes, offset, length)
    return bytes.usePinned { trikeshed_crc32c(it.addressOf(offset), length).toInt() }
}

@file:OptIn(ExperimentalForeignApi::class)

package borg.trikeshed.userspace.nio.platform.spi

import kotlinx.cinterop.*
import platform.linux.SYS_getrandom
import platform.posix.*

/** getrandom(2) through syscall(2): the glibc 2.19 sysroot of linuxX64 has no getrandom wrapper. */
actual fun platformGetRandom(bytes: ByteArray) {
    if (bytes.isEmpty()) return
    bytes.usePinned { pinned ->
        var at = 0
        while (at < bytes.size) {
            val count = syscall(SYS_getrandom.convert(), pinned.addressOf(at), (bytes.size - at).convert<size_t>(), 0u)
            if (count < 0) check(errno == EINTR) { "getrandom failed, errno ${errno}" } else at += count.toInt()
        }
    }
}

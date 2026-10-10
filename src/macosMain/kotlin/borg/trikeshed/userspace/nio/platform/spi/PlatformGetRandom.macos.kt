@file:OptIn(ExperimentalForeignApi::class)

package borg.trikeshed.userspace.nio.platform.spi

import borg.trikeshed.userspace.nio.posix.getentropy
import kotlinx.cinterop.*
import platform.posix.*

/** getentropy(2) fills at most 256 bytes per call. */
actual fun platformGetRandom(bytes: ByteArray) {
    if (bytes.isEmpty()) return
    bytes.usePinned { pinned ->
        var at = 0
        while (at < bytes.size) {
            val count = minOf(256, bytes.size - at)
            check(getentropy(pinned.addressOf(at), count.convert()) == 0) { "getentropy failed, errno ${errno}" }
            at += count
        }
    }
}

package borg.trikeshed.userspace.nio.platform.spi

import org.khronos.webgl.Int8Array

/** A Kotlin/JS ByteArray is an Int8Array; getRandomValues fills at most 65536 bytes per call. */
actual fun platformGetRandom(bytes: ByteArray) {
    val view = bytes.unsafeCast<Int8Array>()
    var at = 0
    while (at < bytes.size) {
        val end = minOf(bytes.size, at + 65536)
        js("globalThis").crypto.getRandomValues(view.subarray(at, end))
        at = end
    }
}

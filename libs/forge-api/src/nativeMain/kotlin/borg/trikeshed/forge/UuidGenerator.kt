package borg.trikeshed.forge

import kotlinx.cinterop.*
import platform.posix.*

actual fun UuidGenerator.generate(): String {
    // Use /dev/urandom on POSIX systems
    val fd = open("/dev/urandom", O_RDONLY)
    if (fd < 0) {
        // Fallback to random
        return fallback()
    }
    val buffer = ByteArray(16)
    var read = 0
    while (read < 16) {
        val r = read(fd, buffer + read, 16 - read)
        if (r <= 0) break
        read += r
    }
    close(fd)
    if (read != 16) return fallback()
    
    // Set version (4) and variant bits
    buffer[6] = (buffer[6] and 0x0f) or 0x40
    buffer[8] = (buffer[8] and 0x3f) or 0x80
    
    return buildString {
        for (i in 0 until 16) {
            if (i == 4 || i == 6 || i == 8 || i == 10) append('-')
            append(String.format("%02x", buffer[i].toInt() and 0xFF))
        }
    }
}

private fun fallback(): String {
    val random = java.util.Random(System.nanoTime())
    val bytes = ByteArray(16)
    random.nextBytes(bytes)
    bytes[6] = (bytes[6] and 0x0f) or 0x40
    bytes[8] = (bytes[8] and 0x3f) or 0x80
    return buildString {
        for (i in 0 until 16) {
            if (i == 4 || i == 6 || i == 8 || i == 10) append('-')
            append(String.format("%02x", bytes[i].toInt() and 0xFF))
        }
    }
}
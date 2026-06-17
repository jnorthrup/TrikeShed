package borg.trikeshed.forge.platform

import kotlinx.coroutines.Dispatchers
import kotlin.random.Random

actual object PlatformUtils {
    actual fun currentTimeMillis(): Long {
        // JS Date.now() returns milliseconds since epoch
        return org.w3c.dom.Date.now().toLong()
    }

    actual fun randomUuid(): String {
        // Simple random UUID v4 compatible string generation
        // Not cryptographically secure, but fine for internal IDs
        val random = Random.Default
        val uuid = StringBuilder(36)
        val hexChars = "0123456789abcdef"
        
        for (i in 0..35) {
            if (i == 8 || i == 13 || i == 18 || i == 23) {
                uuid.append('-')
            } else if (i == 14) {
                uuid.append('4') // Version 4
            } else if (i == 19) {
                uuid.append(hexChars[random.nextInt(4) + 8]) // Variant 10xx (8, 9, a, b)
            } else {
                uuid.append(hexChars[random.nextInt(16)])
            }
        }
        return uuid.toString()
    }

    actual val ioDispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.Default
}
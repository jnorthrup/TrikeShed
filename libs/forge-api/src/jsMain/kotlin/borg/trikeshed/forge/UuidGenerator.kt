package borg.trikeshed.forge

import kotlin.js.json

actual fun UuidGenerator.generate(): String {
    val global = js("globalThis")
    val crypto = global["crypto"]
    return if (crypto != null && crypto.randomUUID != null) {
        crypto.randomUUID() as String
    } else {
        // Fallback: generate random UUID v4
        val random = js("crypto.getRandomValues") ?: js("msCrypto.getRandomValues")
        if (random != null) {
            val arr = js("new Uint8Array(16)") as dynamic
            random(arr)
            // Set version (4) and variant bits
            arr[6] = (arr[6] & 0x0f) | 0x40
            arr[8] = (arr[8] & 0x3f) | 0x80
            return buildString {
                for (i in 0 until 16) {
                    if (i == 4 || i == 6 || i == 8 || i == 10) append('-')
                    append(String.format("%02x", arr[i]))
                }
            }
        } else {
            // Last resort: Math.random()
            val random = (Math.random() * 0x100000000).toInt()
            return "${random.toString(16).padStart(8, '0')}-${(Math.random() * 0x10000).toInt().toString(16).padStart(4, '0')}-4${(Math.random() * 0x1000).toInt().toString(16).padStart(3, '0')}-${(8 | (Math.random() * 4).toInt()).toString(16)}${(Math.random() * 0x1000).toInt().toString(16).padStart(3, '0')}-${(Math.random() * 0x100000000000000).toLong().toString(16).padStart(12, '0')}"
        }
    }
}
@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package borg.trikeshed.userspace.nio.platform.spi

@JsFun("(n) => { const a = new Uint8Array(n); globalThis.crypto.getRandomValues(a); return a; }")
external fun getRandomValues(count: Int): JsAny

@JsFun("(a, i) => a[i]")
external fun getRandomValuesItem(values: JsAny, index: Int): Int

/** getRandomValues fills at most 65536 bytes per call; wasm reads the Uint8Array back byte by byte. */
actual fun platformGetRandom(bytes: ByteArray) {
    var at = 0
    while (at < bytes.size) {
        val count = minOf(65536, bytes.size - at)
        val values = getRandomValues(count)
        for (i in 0 until count) bytes[at + i] = getRandomValuesItem(values, i).toByte()
        at += count
    }
}

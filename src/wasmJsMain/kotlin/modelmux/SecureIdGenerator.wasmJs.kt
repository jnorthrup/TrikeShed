package modelmux

// wasmJs has no kotlinx.browser/org.khronos.webgl; reach WebCrypto through JS interop.
// globalThis.crypto is present in browsers and Node ≥ 19 — stays a CSPRNG, never Math.random.
@OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
@JsFun("(n) => { const a = new Uint8Array(n); globalThis.crypto.getRandomValues(a); return Array.from(a).map(b => b.toString(16).padStart(2, '0')).join(''); }")
private external fun jsSecureRandomHex(byteLength: Int): String

actual val defaultSecureIdGenerator: SecureIdGenerator = object : SecureIdGenerator {
    override fun generateHexId(prefix: String, byteLength: Int): String =
        "$prefix-" + jsSecureRandomHex(byteLength)
}

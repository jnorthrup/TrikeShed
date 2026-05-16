package borg.trikeshed.userspace.nio.tls

@Suppress("UNUSED")
actual fun createTlsEngine(settings: TlsSettings): TlsEngine {
    error("TLS not supported on JS/WASM")
}
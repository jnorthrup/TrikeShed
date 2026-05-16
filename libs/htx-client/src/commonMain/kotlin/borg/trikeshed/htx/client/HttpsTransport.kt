package borg.trikeshed.htx.client

/**
 * Platform-specific HTTPS handler factory.
 * Implement in jvmMain/wasmJsMain/etc.
 */
expect fun createHttpsHandler(): HtxRequestHandler

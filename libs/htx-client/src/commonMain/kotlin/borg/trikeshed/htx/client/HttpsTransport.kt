package borg.trikeshed.htx.client

/**
 * Platform HTTPS transport handler.
 *
 * Each platform provides an [actual] implementation using its native HTTP client:
 * - JVM: [java.net.http.HttpClient]
 * - JS/WASM: `fetch()` API
 * - POSIX: stub (returns 501)
 */
expect fun createHttpsHandler(): HtxRequestHandler

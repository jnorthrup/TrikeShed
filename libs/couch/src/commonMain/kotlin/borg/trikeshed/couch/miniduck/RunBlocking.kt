package borg.trikeshed.couch.miniduck

/**
 * Platform-agnostic declaration for a small helper that allows calling suspend APIs
 * from synchronous code paths in tests and legacy APIs. Each platform provides an
 * `actual` implementation.
 */
expect fun <T> runBlockingCommon(block: suspend () -> T): T

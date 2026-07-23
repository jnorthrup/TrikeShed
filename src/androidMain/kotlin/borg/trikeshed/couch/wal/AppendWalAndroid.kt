package borg.trikeshed.couch.wal

/**
 * Android stub for Write-Ahead Log.
 */
actual class AppendWal actual constructor(path: String) {
    actual suspend fun append(payload: ByteArray): Long {
        throw UnsupportedOperationException("AppendWal not implemented on Android")
    }

    actual fun close() {
        throw UnsupportedOperationException("AppendWal not implemented on Android")
    }
}

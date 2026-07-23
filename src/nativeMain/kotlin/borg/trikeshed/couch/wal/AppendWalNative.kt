package borg.trikeshed.couch.wal

/**
 * Native stub for Write-Ahead Log.
 */
actual class AppendWal actual constructor(path: String) {
    actual suspend fun append(payload: ByteArray): Long {
        throw UnsupportedOperationException("AppendWal not implemented on Native")
    }

    actual fun close() {
        throw UnsupportedOperationException("AppendWal not implemented on Native")
    }
}

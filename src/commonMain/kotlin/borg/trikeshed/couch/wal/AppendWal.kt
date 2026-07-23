package borg.trikeshed.couch.wal

/**
 * Multiplatform Write-Ahead Log abstraction.
 */
expect class AppendWal {
    constructor(path: String)
    suspend fun append(payload: ByteArray): Long
    fun close()
}

package borg.trikeshed.job

/**
 * Optional, fail-closed CAS capability. Implementations must limit allocation while reading,
 * verify the returned bytes against [cid], and distinguish a missing object from an oversized one.
 * A store that does not implement this interface cannot safely be used for bounded reads.
 * Implementations may throw [UnsupportedOperationException] if their backing filesystem
 * lacks the required capability. A bounded allocation does not imply an I/O deadline.
 */
interface BoundedCasReader {
    fun getBounded(cid: ContentId, maxBytes: Int): ByteArray?
}

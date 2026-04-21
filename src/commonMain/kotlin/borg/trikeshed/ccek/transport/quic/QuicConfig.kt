package borg.trikeshed.ccek.transport.quic

/**
 * QUIC connection configuration — mirrors literbike `quic_config::QuicConfig`.
 *
 * All fields have sensible defaults that match the Rust reference implementation.
 */
data class QuicConfig(
    /** Application-Layer Protocol Negotiation tokens, e.g. `listOf("h3".encodeToByteArray())`. */
    val alpn: List<ByteArray> = listOf("h3".encodeToByteArray()),
    /** Maximum idle timeout in milliseconds before the connection is silently closed. */
    val maxIdleTimeoutMs: ULong = 30_000uL,
    /** Maximum UDP datagram payload size (path MTU minus IP/UDP headers). */
    val maxUdpPayloadSize: UInt = 1350u,
    /** Enable Generic Segmentation Offload when the platform supports it. */
    val enableGso: Boolean = true,
    /** Enable Explicit Congestion Notification (ECN) markings. */
    val enableEcn: Boolean = true,
) {
    companion object {
        /** Convenience factory — returns the default configuration. */
        fun default(): QuicConfig = QuicConfig()
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is QuicConfig) return false
        if (alpn.size != other.alpn.size) return false
        for (i in alpn.indices) {
            if (!alpn[i].contentEquals(other.alpn[i])) return false
        }
        return maxIdleTimeoutMs == other.maxIdleTimeoutMs &&
                maxUdpPayloadSize == other.maxUdpPayloadSize &&
                enableGso == other.enableGso &&
                enableEcn == other.enableEcn
    }

    override fun hashCode(): Int {
        var result = alpn.fold(1) { acc, bytes -> 31 * acc + bytes.contentHashCode() }
        result = 31 * result + maxIdleTimeoutMs.hashCode()
        result = 31 * result + maxUdpPayloadSize.hashCode()
        result = 31 * result + enableGso.hashCode()
        result = 31 * result + enableEcn.hashCode()
        return result
    }
}

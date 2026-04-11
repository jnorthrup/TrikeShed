package borg.literbike.ccek.quic

// ============================================================================
// QUIC Session Cache -- ported from quic_session_cache.rs
// ============================================================================

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Session cache entry for QUIC session resumption */
data class SessionEntry(
    val serverName: String,
    val sessionTicket: List<UByte>,
    val alpn: List<UByte>? = null,
    val zeroRttParams: List<UByte>? = null,
    val insertedAt: Long = Clocks.System.now(),
    val ttl: Duration = 3600.seconds
) {
    val isExpired: Boolean
        get() = (Clocks.System.now() - insertedAt) > ttl.inWholeMilliseconds
}

/** QUIC session cache trait */
interface QuicSessionCache {
    fun put(key: String, value: SessionEntry)
    fun get(key: String): SessionEntry?
}

/** Default in-memory session cache implementation */
class DefaultQuicSessionCache : QuicSessionCache {
    private val map = mutableMapOf<String, SessionEntry>()

    fun evictExpired() {
        map.entries.removeIf { (_, v) -> v.isExpired }
    }

    override fun put(key: String, value: SessionEntry) {
        map[key] = value
    }

    override fun get(key: String): SessionEntry? {
        return map[key]?.takeIf { !it.isExpired }
    }
}

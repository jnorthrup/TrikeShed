package borg.literbike.adapters

/**
 * Adapters module - protocol adapter registry.
 * Ported from literbike/src/adapters/mod.rs.
 */

// Re-export adapter names
val sshAdapterName: String = "ssh::SshProtocolAdapter"
val httpAdapterName: String = "http::HttpProtocolAdapter"
val quicAdapterName: String = "quic::QuicProtocolAdapter"

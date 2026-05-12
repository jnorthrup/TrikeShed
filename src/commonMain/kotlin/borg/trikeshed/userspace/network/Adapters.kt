package borg.trikeshed.userspace.network

import borg.trikeshed.lib.ByteSeries
import borg.trikeshed.userspace.ByteRegion

/**
 * Protocol session surface — sits above the transport/handle layer.
 * Implementations carry protocol role, lifecycle, and framing; not raw fd semantics.
 * Previously called NetworkAdapter; renamed to avoid collision with kernel.SocketSyscalls.
 */

enum class AdapterType {
    Http, Https, Quic, Ssh, WebSocket, Raw
}

interface SessionChannel {
    fun adapterType(): AdapterType
    fun remoteAddr():CharSequencefun isConnected(): Boolean
    fun close(): Result<Unit>
    fun read(dst: ByteRegion): Int
    fun write(src: ByteSeries): Int
}

@Deprecated("Use SessionChannel", ReplaceWith("SessionChannel"))
typealias NetworkAdapter = SessionChannel

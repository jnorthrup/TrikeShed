package borg.trikeshed.reactor

import borg.trikeshed.lib.Series
import borg.trikeshed.lib.j

data class ProtocolPriority(val protocol: Protocol, val priority: Int)

data class ReactorConfig(
    val priorities: Series<ProtocolPriority>,
    val defaultMaxBlockBytes: Int = 16_384,
    val maxOpenSessions: Int = 64,
) {
    companion object {
        fun default(): ReactorConfig = ReactorConfig(
            priorities = 7 j { i ->
                when (i) {
                    0 -> ProtocolPriority(Protocol.Http, 1)
                    1 -> ProtocolPriority(Protocol.Socks5, 1)
                    2 -> ProtocolPriority(Protocol.Tls, 2)
                    3 -> ProtocolPriority(Protocol.Dns, 3)
                    4 -> ProtocolPriority(Protocol.Json, 4)
                    5 -> ProtocolPriority(Protocol.Http2, 1)
                    6 -> ProtocolPriority(Protocol.WebSocket, 2)
                    else -> error("index $i")
                }
            },
            defaultMaxBlockBytes = 16_384,
            maxOpenSessions = 64,
        )
    }
}

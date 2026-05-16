package borg.trikeshed.openapi

/**
 * Transport extensions parsed from x-trikeshed-* annotations in an OpenAPI spec.
 *
 * These tell the generator to produce non-HTTP client code:
 *   - WebSocket: subscription/read channels with persistent connections
 *   - QUIC: reliable/unreliable streams with multiplexing
 *   - SCTP: multi-stream associations with per-stream operation routing
 */

/** Transport mode for a generated operation. */
enum class TransportMode {
    HTTPS, WEBSOCKET, QUIC, SCTP;

    companion object {
        fun fromExtension(value: CharSequence?): TransportMode = when (value?.toString()?.lowercase()) {
            "ws", "websocket", "wss" -> WEBSOCKET
            "quic", "h3" -> QUIC
            "sctp" -> SCTP
            else -> HTTPS
        }
    }
}

// ── WebSocket channel extension ──────────────────────────────────────────────

/** Parsed x-trikeshed-channel from a WebSocket operation. */
data class WsChannelExtension(
    val type: WsChannelType,
    val subscribeMessage: Map<CharSequence, Any?>? = null,
)

enum class WsChannelType { SUBSCRIBE, READ, WRITE, CLOSE }

// ── QUIC stream extension ────────────────────────────────────────────────────

/** Parsed x-trikeshed-stream from a QUIC operation. */
data class QuicStreamExtension(
    val mode: QuicStreamMode,
    val reliability: QuicReliability,
)

enum class QuicStreamMode { BIDIRECTIONAL, UNIDIRECTIONAL }
enum class QuicReliability { RELIABLE, UNRELIABLE }

// ── SCTP multi-stream extension ──────────────────────────────────────────────

/** Parsed x-trikeshed-sctp from an SCTP operation. */
data class SctpExtension(
    val streams: List<SctpStreamMapping>,
)

data class SctpStreamMapping(
    val streamId: Int,
    val operationId: CharSequence,
    val direction: SctpStreamDirection,
)

enum class SctpStreamDirection { CLIENT_TO_SERVER, SERVER_TO_CLIENT, BIDIRECTIONAL }

// ── Transport-annotated operation ────────────────────────────────────────────

/** An operation with transport metadata attached. */
data class TransportAnnotatedOperation(
    val operation: ResolvedOperation,
    val transportMode: TransportMode,
    val wsChannel: WsChannelExtension? = null,
    val quicStream: QuicStreamExtension? = null,
    val sctp: SctpExtension? = null,
) {
    val isPersistent: Boolean get() = transportMode != TransportMode.HTTPS
}

/**
 * Parse transport extensions from a resolved document.
 * Walks each operation and extracts x-trikeshed-* annotations from the raw root.
 */
fun ResolvedOpenApiDocument.parseTransportAnnotations(): List<TransportAnnotatedOperation> {
    val paths = rawRoot["paths"]?.asMap() ?: emptyMap()
    val results = buildList<TransportAnnotatedOperation>()

    for (op in operations) {
        // Walk raw paths to find this operation's annotations
        val pathNode = paths[op.path]?.asMap() ?: continue
        val opNode = pathNode[op.method]?.asMap() ?: continue

        val transportMode = TransportMode.fromExtension(
            opNode["x-trikeshed-transport"]?.asStr()
        )

        val wsChannel = opNode["x-trikeshed-channel"]?.asMap()?.let { ch ->
            WsChannelExtension(
                type = when (ch["type"]?.asStr()) {
                    "subscribe" -> WsChannelType.SUBSCRIBE
                    "read" -> WsChannelType.READ
                    "write" -> WsChannelType.WRITE
                    "close" -> WsChannelType.CLOSE
                    else -> WsChannelType.SUBSCRIBE
                },
                subscribeMessage = @Suppress("UNCHECKED_CAST") (ch["message"] as? Map<CharSequence, Any?>),
            )
        }

        val quicStream = opNode["x-trikeshed-stream"]?.asMap()?.let { qs ->
            QuicStreamExtension(
                mode = when (qs["mode"]?.asStr()) {
                    "unidirectional" -> QuicStreamMode.UNIDIRECTIONAL
                    else -> QuicStreamMode.BIDIRECTIONAL
                },
                reliability = when (qs["reliability"]?.asStr()) {
                    "unreliable" -> QuicReliability.UNRELIABLE
                    else -> QuicReliability.RELIABLE
                },
            )
        }

        @Suppress("UNCHECKED_CAST")
        val sctp: SctpExtension? = opNode["x-trikeshed-sctp"]?.asMap()?.let { sc ->
            val streams = (sc["streams"] as? List<Map<CharSequence, Any?>>)?.mapNotNull { s ->
                SctpStreamMapping(
                    streamId = (s["streamId"] as? Number)?.toInt() ?: return@mapNotNull null,
                    operationId = s["operationId"] as? CharSequence ?: return@mapNotNull null,
                    direction = when (s["direction"]?.asStr()) {
                        "client_to_server" -> SctpStreamDirection.CLIENT_TO_SERVER
                        "server_to_client" -> SctpStreamDirection.SERVER_TO_CLIENT
                        "bidirectional" -> SctpStreamDirection.BIDIRECTIONAL
                        else -> SctpStreamDirection.BIDIRECTIONAL
                    },
                )
            } ?: emptyList()
            SctpExtension(streams)
        }

        results.add(
            TransportAnnotatedOperation(op, transportMode, wsChannel, quicStream, sctp)
        )
    }

    return results.toList()
}

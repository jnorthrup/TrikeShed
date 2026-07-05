package borg.trikeshed.ipfs

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * DHT Wire Protocol — messages exchanged between DHT nodes over UDP.
 * 
 * Each message is length-prefixed (4 bytes big-endian) + JSON payload.
 * This allows UDP datagrams to be parsed without framing ambiguity.
 */
@Serializable
sealed class DhtMessage {
    @Serializable
    @SerialName("announce_provider")
    data class AnnounceProvider(
        val cid: CID,
        val address: String,
        val ttlSec: Long = 3600,
    ) : DhtMessage()

    @Serializable
    @SerialName("find_providers")
    data class FindProviders(
        val cid: CID,
        val requestId: String, // for response correlation
    ) : DhtMessage()

    @Serializable
    @SerialName("providers")
    data class Providers(
        val cid: CID,
        val addresses: List<String>,
        val requestId: String,
    ) : DhtMessage()

    @Serializable
    @SerialName("ping")
    data class Ping(
        val requestId: String,
    ) : DhtMessage()

    @Serializable
    @SerialName("pong")
    data class Pong(
        val requestId: String,
    ) : DhtMessage()

    @Serializable
    @SerialName("find_node")
    data class FindNode(
        val target: CID,
        val requestId: String,
    ) : DhtMessage()

    @Serializable
    @SerialName("neighbors")
    data class Neighbors(
        val nodes: List<NodeInfo>,
        val requestId: String,
    ) : DhtMessage()

    @Serializable
    @SerialName("find_value")
    data class FindValue(
        val key: CID,
        val requestId: String,
    ) : DhtMessage()

    @Serializable
    @SerialName("value")
    data class Value(
        val value: ByteArray,
        val requestId: String,
    ) : DhtMessage()

    @Serializable
    data class NodeInfo(
        val id: CID,
        val address: String,
    )

    // ── Encoding / Decoding ──────────────────────────────────────────────
    
    companion object {
        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = false
        }

        /** Encode message to length-prefixed byte array (4 bytes BE length + JSON) */
        fun encode(message: DhtMessage): ByteArray {
            val json = json.encodeToString(message)
            val payload = json.toByteArray()
            val out = ByteArray(4 + payload.size)
            // Big-endian length prefix
            out[0] = (payload.size shr 24 and 0xFF).toByte()
            out[1] = (payload.size shr 16 and 0xFF).toByte()
            out[2] = (payload.size shr 8 and 0xFF).toByte()
            out[3] = (payload.size and 0xFF).toByte()
            payload.copyInto(out, 4)
            return out
        }

        /** Decode length-prefixed byte array to DhtMessage */
        fun decode(data: ByteArray): DhtMessage {
            if (data.size < 4) throw IllegalArgumentException("Data too short for length prefix")
            val length = ((data[0].toInt() and 0xFF) shl 24) or
                        ((data[1].toInt() and 0xFF) shl 16) or
                        ((data[2].toInt() and 0xFF) shl 8) or
                        (data[3].toInt() and 0xFF)
            if (length < 0 || length > data.size - 4) {
                throw IllegalArgumentException("Invalid length prefix: $length")
            }
            val jsonString = data.copyOfRange(4, 4 + length).decodeToString()
            return json.decodeFromString<DhtMessage>(jsonString)
        }
    }
}
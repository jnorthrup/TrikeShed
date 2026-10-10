package borg.trikeshed.loom

import borg.trikeshed.ipns.*
import borg.trikeshed.job.sha256
import borg.trikeshed.lib.*
import kotlin.test.*

/** auth.rs against LoomVectors */
class LoomAuthTest {
    val crypto: IpnsCrypto = Ed25519

    val producer = members()[4]
    val replicaA = members()[1]
    val authority = members()[0]

    @Test
    fun messagesSignToTheCapturedBytes() {
        val request = Message.request_at(
            "producer", "replica-a", "POST", "/v1/push", LoomVectors.SEGMENT.hexToByteArray(),
            LoomVectors.SEED_PRODUCER.hexToByteArray(), LoomVectors.T j LoomVectors.NONCE_1.hexToByteArray(), crypto,
        )
        assertEquals(LoomVectors.MESSAGE_REQUEST, request.to_bytes().toHexString())
        val receipt = Receipt.issue_at(
            "replica-a", LoomVectors.SEGMENT_ID.hexToByteArray(), LoomVectors.NONCE_1.hexToByteArray(), false,
            LoomVectors.SEED_REPLICA_A.hexToByteArray(), LoomVectors.T + 1uL, crypto,
        )
        assertEquals(LoomVectors.RECEIPT_PUSH, receipt.to_bytes().toHexString())
        val response = Message.response_at(request, receipt.to_bytes(), LoomVectors.SEED_REPLICA_A.hexToByteArray(), LoomVectors.T + 1uL, crypto)
        assertEquals(LoomVectors.MESSAGE_RESPONSE, response.to_bytes().toHexString())
        val get = Message.request_at(
            "authority", "replica-b", "GET", "/v1/status", ByteArray(0), LoomVectors.SEED_AUTHORITY.hexToByteArray(),
            LoomVectors.T + 2uL j LoomVectors.NONCE_2.hexToByteArray(), crypto,
        )
        assertEquals(LoomVectors.MESSAGE_GET, get.to_bytes().toHexString())
    }

    @Test
    fun capturedMessagesDecodeAndVerify() {
        val request = Message.from_bytes(LoomVectors.MESSAGE_REQUEST.hexToByteArray())
        assertEquals(LoomVectors.MESSAGE_REQUEST, request.to_bytes().toHexString())
        request.verify_request_at(producer, "replica-a", LoomVectors.T, crypto)
        val response = Message.from_bytes(LoomVectors.MESSAGE_RESPONSE.hexToByteArray())
        assertEquals(LoomVectors.MESSAGE_RESPONSE, response.to_bytes().toHexString())
        response.verify_response_at(request, replicaA, LoomVectors.T + 1uL, crypto)
        Message.from_bytes(LoomVectors.MESSAGE_GET.hexToByteArray()).verify_request_at(authority, "replica-b", LoomVectors.T + 2uL, crypto)
        val receipt = Receipt.from_bytes(response.payload)
        assertEquals(LoomVectors.RECEIPT_PUSH, receipt.to_bytes().toHexString())
        receipt.verify_fresh_at(replicaA, LoomVectors.SEGMENT_ID.hexToByteArray(), LoomVectors.NONCE_1.hexToByteArray(), LoomVectors.T + 1uL, crypto)
    }

    @Test
    fun theLargestPayloadRoundTrips() {
        val request = Message.request_at(
            "producer", "replica-a", "POST", "/v1/push", ByteArray(MAX_WIRE - 2048),
            LoomVectors.SEED_PRODUCER.hexToByteArray(), LoomVectors.T j LoomVectors.NONCE_1.hexToByteArray(), crypto,
        )
        val bytes = request.to_bytes()
        assertEquals(LoomVectors.MESSAGE_PAYLOAD_MAX_SHA256, sha256(bytes).toHexString())
        assertContentEquals(bytes, Message.from_bytes(bytes).to_bytes())
    }

    @Test
    fun messageRejectionsAgree() {
        val request = Message.from_bytes(LoomVectors.MESSAGE_REQUEST.hexToByteArray())
        val response = Message.from_bytes(LoomVectors.MESSAGE_RESPONSE.hexToByteArray())
        val t = LoomVectors.T
        fun Message.copy(source: String = this.source, method: String = this.method, path: String = this.path, nonce: ByteArray = this.nonce, payload: ByteArray = this.payload) =
            Message(source, this.target, method, path, this.time, nonce, payload, this.response, this.signature)
        val wrongVersion = request.to_bytes().also { bytes ->
            val tag = "loom-request/v1".encodeToByteArray()
            val at = (0..bytes.size - tag.size).first { i -> tag.indices.all { bytes[i + it] == tag[it] } }
            bytes[at + tag.size - 1] = '2'.code.toByte()
        }
        val cases: Map<String, () -> Unit> = mapOf(
            "bad_path" to { Message.from_bytes(request.copy(path = "/v2/push").to_bytes()) },
            "bad_method" to { Message.from_bytes(request.copy(method = "PUT").to_bytes()) },
            "long_path" to { Message.from_bytes(request.copy(path = "/v1/" + "p".repeat(61)).to_bytes()) },
            "bad_source" to { Message.from_bytes(request.copy(source = "pro ducer").to_bytes()) },
            "wrong_version" to { Message.from_bytes(wrongVersion) },
            "payload_over" to {
                Message.request_at(
                    "producer", "replica-a", "POST", "/v1/push", ByteArray(MAX_WIRE - 2047),
                    LoomVectors.SEED_PRODUCER.hexToByteArray(), t j LoomVectors.NONCE_1.hexToByteArray(), crypto,
                )
            },
            "stale" to { request.verify_request_at(producer, "replica-a", t + 31uL, crypto) },
            "future" to { request.verify_request_at(producer, "replica-a", t - 31uL, crypto) },
            "edge_of_skew" to { request.verify_request_at(producer, "replica-a", t + 30uL, crypto) },
            "response_as_request" to { response.verify_request_at(replicaA, "producer", t + 1uL, crypto) },
            "tampered" to { request.copy(payload = "tampered".encodeToByteArray()).verify_request_at(producer, "replica-a", t, crypto) },
            "wrong_target" to { request.verify_request_at(producer, "replica-b", t, crypto) },
            "wrong_peer" to { request.verify_request_at(replicaA, "replica-a", t, crypto) },
            "response_other_nonce" to { response.verify_response_at(request.copy(nonce = LoomVectors.NONCE_2.hexToByteArray()), replicaA, t + 1uL, crypto) },
            "response_to_response" to { Message.response_at(response, ByteArray(0), LoomVectors.SEED_PRODUCER.hexToByteArray(), t + 2uL, crypto) },
            "clock_range" to { request.verify_request_at(producer, "replica-a", 1uL shl 63, crypto) },
        )
        for ((name, expected) in LoomVectors.messageErrors.view) assertEquals(expected, outcome(cases.getValue(name)), name)
    }
}

/** "ok", or the message of the error the wire layer raised. */
fun outcome(case: () -> Unit): String? = try {
    case()
    "ok"
} catch (e: IllegalStateException) {
    e.message
}

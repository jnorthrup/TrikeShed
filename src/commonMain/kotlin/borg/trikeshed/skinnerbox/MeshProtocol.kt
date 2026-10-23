package borg.trikeshed.skinnerbox

import borg.trikeshed.collections.associative.Cbor
import borg.trikeshed.collections.associative.Item
import borg.trikeshed.context.nuid.Subnet
import borg.trikeshed.context.nuid.contains
import borg.trikeshed.htx.HtxScheme
import borg.trikeshed.htx.parseHtxRequest
import borg.trikeshed.ipns.Ed25519
import borg.trikeshed.ipns.IpnsCrypto
import borg.trikeshed.ipns.IpnsKeyPair
import borg.trikeshed.job.ContentId
import borg.trikeshed.lib.*

/** Enrolled identity and placement. The key, rather than a supplied name, determines identity. */
class MeshPeer(publicKey: ByteArray, val failureDomain: String, val origin: String, subnet: Subnet) {
    private val keyBytes = publicKey.also { require(it.size == 32) { "Mesh Ed25519 key length" } }.copyOf()
    val publicKey: ByteArray get() = keyBytes.copyOf()
    val id: String = ContentId.of(keyBytes).hex
    val subnet: Subnet = meshSubnet(subnet.toString())

    init {
        require(keyBytes.size == 32 && !Ed25519.isWeak(keyBytes)) { "Mesh Ed25519 key" }
        require(failureDomain.length in 1..128 && failureDomain.all { it.isLetterOrDigit() || it in "._-" }) {
            "Mesh failure domain"
        }
        require(origin.length <= 512 && ORIGIN.matches(origin)) { "Mesh origin" }
        val target = parseHtxRequest(origin).target
        require(target.port in 1..65535 && target.requestPath == "/") { "Mesh origin port/path" }
        require(target.scheme == HtxScheme.HTTPS || target.host in setOf("127.0.0.1", "localhost", "[::1]")) {
            "Mesh origin requires HTTPS or loopback HTTP"
        }
    }

    companion object {
        private val ORIGIN = Regex("https?://(?:[A-Za-z0-9.-]+|\\[[0-9a-fA-F:]+])(?::[0-9]{1,5})?")
    }
}

/** Immutable operation identity. A caller's mutation cannot change bytes already admitted. */
class MeshTask(val namespace: String, val verb: String, payload: ByteArray) {
    private val bytes = payload.also { require(it.size <= MAX_PAYLOAD_BYTES) { "Mesh task payload limit" } }.copyOf()
    val payload: ByteArray get() = bytes.copyOf()
    val payloadSize: Int get() = bytes.size
    val subnet: Subnet = meshSubnet(namespace)
    val id: ContentId

    init {
        require(verb.length in 1..64 && verb.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it in "._-" }) {
            "Mesh verb"
        }
        require(bytes.size <= MAX_PAYLOAD_BYTES) { "Mesh task payload limit" }
        id = ContentId.of(Cbor.encode(Item.Arr(s_[Item.Str("skinnerbox-task/v1"), Item.Str(namespace), Item.Str(verb), Item.Bin(bytes)])))
    }

    companion object { const val MAX_PAYLOAD_BYTES: Int = 128 * 1024 }
}

internal fun meshSubnet(text: String): Subnet {
    require(text.length in 1..256) { "Mesh namespace length" }
    val parts = text.split('.')
    require(parts.size <= 16 && parts.all { part ->
        part.length in 1..64 && part.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it in "_-" }
    }) { "Mesh namespace grammar" }
    return Subnet.parse(text)
}

/** Fixed-schema canonical CBOR; these envelopes are deliberately distinct from legacy Skinnerbox domains. */
class MeshEnvelope private constructor(
    val response: Boolean,
    val source: String,
    val target: String,
    val timeMillis: Long,
    nonce: ByteArray,
    val namespace: String,
    val verb: String,
    val taskId: ContentId,
    payload: ByteArray,
    signature: ByteArray,
) {
    private val challenge = nonce.copyOf()
    private val body = payload.copyOf()
    private val proof = signature.copyOf()
    val nonce: ByteArray get() = challenge.copyOf()
    val payload: ByteArray get() = body.copyOf()

    private fun unsigned(): Item = Item.Arr(s_[
        Item.Str(if (response) "skinnerbox-response/v1" else "skinnerbox-request/v1"),
        Item.Str(source), Item.Str(target), Item.Num(timeMillis), Item.Bin(challenge),
        Item.Str(namespace), Item.Str(verb), Item.Str(taskId.value), Item.Bin(body),
    ])

    fun bytes(): ByteArray = Cbor.encode(Item.Arr(s_[unsigned(), Item.Bin(proof)]))

    private fun verify(peer: MeshPeer, nowMillis: Long, crypto: IpnsCrypto) {
        require(nowMillis in CLOCK_SKEW_MILLIS..Long.MAX_VALUE - CLOCK_SKEW_MILLIS) { "Mesh clock" }
        require(timeMillis in nowMillis - CLOCK_SKEW_MILLIS..nowMillis + CLOCK_SKEW_MILLIS) { "Mesh envelope expired" }
        require(source == peer.id && crypto.verify(peer.publicKey, Cbor.encode(unsigned()), proof)) { "Mesh signature" }
    }

    fun verifyRequest(peer: MeshPeer, local: MeshPeer, nowMillis: Long, crypto: IpnsCrypto = Ed25519): MeshTask {
        verify(peer, nowMillis, crypto)
        require(!response && target == local.id) { "Mesh request binding" }
        val task = MeshTask(namespace, verb, body)
        require(task.id == taskId) { "Mesh task content identity" }
        require((peer.subnet contains task.subnet) && (local.subnet contains task.subnet)) { "Mesh namespace grant" }
        return task
    }

    fun verifyResponse(request: MeshEnvelope, peer: MeshPeer, nowMillis: Long, crypto: IpnsCrypto = Ed25519) {
        verify(peer, nowMillis, crypto)
        require(response && !request.response && target == request.source && source == request.target &&
            namespace == request.namespace && verb == request.verb && taskId == request.taskId &&
            challenge.contentEquals(request.challenge)) { "Mesh response binding" }
    }

    companion object {
        const val PATH: String = "/v1/skinnerbox/exchange"
        const val CLOCK_SKEW_MILLIS: Long = 30_000
        const val MAX_WIRE_BYTES: Int = MeshTask.MAX_PAYLOAD_BYTES + 4096

        fun request(local: MeshPeer, target: MeshPeer, task: MeshTask, identity: IpnsKeyPair,
            nowMillis: Long, nonce: ByteArray, crypto: IpnsCrypto = Ed25519): MeshEnvelope {
            require(identity.publicKey.contentEquals(local.publicKey)) { "Mesh local identity mismatch" }
            require((local.subnet contains task.subnet) && (target.subnet contains task.subnet)) { "Mesh namespace grant" }
            require(nonce.size == 32) { "Mesh nonce length" }
            val envelope = MeshEnvelope(false, local.id, target.id, nowMillis, nonce, task.namespace, task.verb,
                task.id, task.payload, ByteArray(64))
            return envelope.signed(identity, crypto)
        }

        fun response(request: MeshEnvelope, payload: ByteArray, identity: IpnsKeyPair, nowMillis: Long,
            crypto: IpnsCrypto = Ed25519): MeshEnvelope {
            require(!request.response && payload.size <= MeshTask.MAX_PAYLOAD_BYTES) { "Mesh response payload" }
            require(ContentId.of(identity.publicKey).hex == request.target) { "Mesh response identity" }
            return MeshEnvelope(true, request.target, request.source, nowMillis, request.challenge,
                request.namespace, request.verb, request.taskId, payload, ByteArray(64)).signed(identity, crypto)
        }

        fun decode(bytes: ByteArray): MeshEnvelope {
            require(bytes.size in 1..MAX_WIRE_BYTES) { "Mesh envelope bytes" }
            val reader = Cbor.Reader(bytes, 0, maxDepth = 3, maxItems = 16)
            val item = try { reader.readItem() }
            catch (failure: IllegalStateException) { throw IllegalArgumentException("Mesh CBOR schema", failure) }
            catch (failure: IndexOutOfBoundsException) { throw IllegalArgumentException("Mesh truncated CBOR", failure) }
            require(reader.pos == bytes.size && Cbor.encode(item).contentEquals(bytes)) { "Mesh canonical envelope" }
            val outer = item as? Item.Arr ?: throw IllegalArgumentException("Mesh envelope array")
            require(outer.size == 2) { "Mesh envelope fields" }
            val fields = outer[0] as? Item.Arr ?: throw IllegalArgumentException("Mesh envelope fields")
            require(fields.size == 9) { "Mesh envelope fields" }
            fun text(index: Int): String = (fields[index] as? Item.Str)?.value ?: throw IllegalArgumentException("Mesh envelope text")
            val response = when (text(0)) {
                "skinnerbox-request/v1" -> false
                "skinnerbox-response/v1" -> true
                else -> throw IllegalArgumentException("Mesh envelope version")
            }
            val source = text(1)
            val target = text(2)
            require(listOf(source, target).all { it.length == 64 && it.all { c -> c in '0'..'9' || c in 'a'..'f' } }) { "Mesh peer identity" }
            val time = (fields[3] as? Item.Num)?.value ?: throw IllegalArgumentException("Mesh envelope time")
            require(time >= 0) { "Mesh envelope time" }
            val nonce = (fields[4] as? Item.Bin)?.value ?: throw IllegalArgumentException("Mesh envelope nonce")
            val payload = (fields[8] as? Item.Bin)?.value ?: throw IllegalArgumentException("Mesh envelope payload")
            val signature = (outer[1] as? Item.Bin)?.value ?: throw IllegalArgumentException("Mesh envelope signature")
            require(nonce.size == 32 && signature.size == 64 && payload.size <= MeshTask.MAX_PAYLOAD_BYTES) { "Mesh envelope bounds" }
            val namespace = text(5)
            val verb = text(6)
            // Validate schema even for replies, whose body is not the request's content.
            MeshTask(namespace, verb, byteArrayOf())
            return MeshEnvelope(response, source, target, time, nonce, namespace, verb, ContentId(text(7)), payload, signature)
        }
    }

    private fun signed(identity: IpnsKeyPair, crypto: IpnsCrypto): MeshEnvelope = MeshEnvelope(
        response, source, target, timeMillis, challenge, namespace, verb, taskId, body,
        crypto.sign(identity.privateKey, Cbor.encode(unsigned())),
    )
}

/** A verified signed reply, not a claim that its handler has persisted anything. */
class MeshReply internal constructor(val peer: MeshPeer, val taskId: ContentId, payload: ByteArray, wire: ByteArray) {
    private val body = payload.copyOf()
    private val signed = wire.copyOf()
    val payload: ByteArray get() = body.copyOf()
    val signedBytes: ByteArray get() = signed.copyOf()
}

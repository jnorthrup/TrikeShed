package borg.trikeshed.loom

import borg.trikeshed.collections.associative.*
import borg.trikeshed.ipns.IpnsCrypto
import borg.trikeshed.lib.*
import borg.trikeshed.userspace.nio.platform.spi.*

/** cocaine-rats crates/loom-mesh/src/auth.rs: identity per message, Ed25519 over canonical CBOR. */
const val CLOCK_SKEW: ULong = 30uL

fun ULong.abs_diff(other: ULong): ULong = if (this > other) this - other else other - this

/** 32 bytes from the OS CSPRNG. */
fun nonce(): ByteArray = ByteArray(32).also(::platformGetRandom)

/** A signed request or response. Time and nonce come from the trusted runtime, never from wire input. */
class Message(
    val source: String,
    val target: String,
    val method: String,
    val path: String,
    val time: ULong,
    val nonce: ByteArray,
    val payload: ByteArray,
    val response: Boolean,
    var signature: ByteArray = ByteArray(64),
) {
    fun unsigned(): Item = itemArrayOf(
        Item.Str(if (response) "loom-response/v1" else "loom-request/v1"),
        Item.Str(source),
        Item.Str(target),
        Item.Str(method),
        Item.Str(path),
        Item.Num(time.toLong()),
        Item.Bin(nonce),
        Item.Bin(payload),
    )

    fun sign(key: ByteArray, crypto: IpnsCrypto) {
        validate()
        signature = crypto.sign(key, Cbor.encode(unsigned()))
    }

    fun validate() {
        label(source)
        label(target)
        if ((method != "GET" && method != "POST") || path.encodeToByteArray().size > 64 || !path.startsWith("/v1/") ||
            time > Long.MAX_VALUE.toULong() || payload.size > MAX_WIRE - 2048
        ) error("message bounds")
    }

    fun to_bytes(): ByteArray = Cbor.encode(itemArrayOf(unsigned(), Item.Bin(signature)))

    fun verify_request_at(peer: Member, target: String, now: ULong, crypto: IpnsCrypto) {
        verify_at(peer.key(), false, now, crypto)
        if (source != peer.id || this.target != target) error("request binding")
    }

    fun verify_response_at(request: Message, peer: Member, now: ULong, crypto: IpnsCrypto) {
        verify_at(peer.key(), true, now, crypto)
        if (request.response || source != peer.id || target != request.source || method != request.method ||
            path != request.path || !nonce.contentEquals(request.nonce)
        ) error("response binding")
    }

    fun verify_at(key: ByteArray, response: Boolean, now: ULong, crypto: IpnsCrypto) {
        trusted_time(now)
        validate()
        if (this.response != response || now.abs_diff(time) > CLOCK_SKEW) error("message freshness")
        if (!crypto.verify(key, Cbor.encode(unsigned()), signature)) error("message signature")
    }

    fun verify(key: ByteArray, response: Boolean, crypto: IpnsCrypto) = verify_at(key, response, now(), crypto)

    fun verify_response(request: Message, peer: Member, crypto: IpnsCrypto) = verify_response_at(request, peer, now(), crypto)

    companion object {
        fun request_at(
            source: String,
            target: String,
            method: String,
            path: String,
            payload: ByteArray,
            key: ByteArray,
            time_nonce: Join<ULong, ByteArray>,
            crypto: IpnsCrypto,
        ): Message = Message(source, target, method, path, time_nonce.a, time_nonce.b, payload, false)
            .also { it.sign(key, crypto) }

        fun response_at(request: Message, payload: ByteArray, key: ByteArray, time: ULong, crypto: IpnsCrypto): Message {
            request.validate()
            if (request.response) error("response binding")
            return Message(request.target, request.source, request.method, request.path, time, request.nonce, payload, true)
                .also { it.sign(key, crypto) }
        }

        fun request(source: String, target: String, method: String, path: String, payload: ByteArray, key: ByteArray, crypto: IpnsCrypto): Message =
            request_at(source, target, method, path, payload, key, now() j nonce(), crypto)

        fun response(request: Message, payload: ByteArray, key: ByteArray, crypto: IpnsCrypto): Message =
            response_at(request, payload, key, now(), crypto)

        fun from_bytes(data: ByteArray): Message {
            val outer = array(decode(data), 2)
            val a = array(outer[0], 8)
            val response = when (text(a[0])) {
                "loom-response/v1" -> true
                "loom-request/v1" -> false
                else -> error("message version")
            }
            return Message(
                text(a[1]), text(a[2]), text(a[3]), text(a[4]), number(a[5]), fixed(a[6], 32), bytes(a[7]),
                response, fixed(outer[1], 64),
            ).also { it.validate() }
        }
    }
}

/** A replica's (or archive's) signed statement that it holds segment [id], bound to a challenge [nonce]. */
class Receipt(
    val signer: String,
    val id: Id,
    val nonce: ByteArray,
    val time: ULong,
    val archival: Boolean,
    var signature: ByteArray = ByteArray(64),
) {
    fun unsigned(): Item = itemArrayOf(
        Item.Str("loom-receipt/v1"),
        Item.Str(signer),
        Item.Bin(id),
        Item.Bin(nonce),
        Item.Num(time.toLong()),
        Item.Num(if (archival) 1 else 0),
    )

    fun to_bytes(): ByteArray = Cbor.encode(itemArrayOf(unsigned(), Item.Bin(signature)))

    /** [now] must come from the trusted execution runtime. */
    fun verify_fresh_at(peer: Member, id: Id, nonce: ByteArray, now: ULong, crypto: IpnsCrypto) {
        trusted_time(now)
        verify(peer, id, crypto)
        if (!this.nonce.contentEquals(nonce) || now.abs_diff(time) > CLOCK_SKEW) error("receipt freshness")
    }

    fun verify(peer: Member, id: Id, crypto: IpnsCrypto) {
        if (signer != peer.id || !this.id.contentEquals(id) || time > Long.MAX_VALUE.toULong() ||
            !peer.has(if (archival) Role.Archive else Role.Replica)
        ) error("receipt binding or role")
        if (!crypto.verify(peer.key(), Cbor.encode(unsigned()), signature)) error("receipt signature")
    }

    fun verify_fresh(peer: Member, id: Id, nonce: ByteArray, crypto: IpnsCrypto) = verify_fresh_at(peer, id, nonce, now(), crypto)

    companion object {
        fun issue_at(signer: String, id: Id, nonce: ByteArray, archival: Boolean, key: ByteArray, time: ULong, crypto: IpnsCrypto): Receipt {
            label(signer)
            trusted_time(time)
            return Receipt(signer, id, nonce, time, archival).also { it.signature = crypto.sign(key, Cbor.encode(it.unsigned())) }
        }

        /** Encoding-only template; the zero signature is deliberately not evidence. */
        fun capacity_template(): Receipt =
            Receipt("a".repeat(64), ByteArray(32), ByteArray(32), Long.MAX_VALUE.toULong(), true)

        fun from_bytes(data: ByteArray): Receipt {
            val outer = array(decode(data), 2)
            val a = array(outer[0], 6)
            tag(a[0], "loom-receipt/v1")
            val archival = when (number(a[5])) {
                0uL -> false
                1uL -> true
                else -> error("receipt kind")
            }
            return Receipt(text(a[1]), fixed(a[2], 32), fixed(a[3], 32), number(a[4]), archival, fixed(outer[1], 64))
                .also { label(it.signer) }
        }
    }
}

package borg.trikeshed.reactor

import borg.trikeshed.ipns.*
import borg.trikeshed.job.*
import borg.trikeshed.lib.*
import borg.trikeshed.userspace.nio.platform.spi.*

/** RFC 8446 6 AlertDescription. */
enum class AlertDescription(val code: Int) {
    close_notify(0), unexpected_message(10), bad_record_mac(20), record_overflow(22), handshake_failure(40),
    bad_certificate(42), unsupported_certificate(43), certificate_revoked(44), certificate_expired(45),
    certificate_unknown(46), illegal_parameter(47), unknown_ca(48), access_denied(49), decode_error(50), decrypt_error(51),
    protocol_version(70), insufficient_security(71), internal_error(80), inappropriate_fallback(86), user_canceled(90),
    missing_extension(109), unsupported_extension(110), unrecognized_name(112), bad_certificate_status_response(113),
    unknown_psk_identity(115), certificate_required(116), no_application_protocol(120),
}

/** A fatal alert: [received] from the peer, or raised here with its [cause] (a [CertificateException] for a refused certificate). */
class TlsAlert(val description: Int, val received: Boolean = false, cause: Throwable? = null) :
    Exception("${if (received) "received" else "raised"} ${AlertDescription.entries.firstOrNull { it.code == description }?.name ?: description}", cause) {
    constructor(description: AlertDescription, cause: Throwable? = null) : this(description.code, false, cause)
}

/** rustls' alert for a webpki error (pki_error, then CertificateError into AlertDescription). */
val CertificateError.alert: AlertDescription
    get() = when (this) {
        CertificateError.BadDer, CertificateError.BadDerTime, CertificateError.CertNotValidForName -> AlertDescription.bad_certificate
        CertificateError.CertExpired, CertificateError.CertNotValidYet, CertificateError.InvalidCertValidity -> AlertDescription.certificate_expired
        CertificateError.UnknownIssuer -> AlertDescription.unknown_ca
        CertificateError.InvalidSignatureForPublicKey, CertificateError.UnsupportedSignatureAlgorithm,
        CertificateError.UnsupportedSignatureAlgorithmForPublicKey -> AlertDescription.decrypt_error
        CertificateError.RequiredEkuNotFound -> AlertDescription.unsupported_certificate
        else -> AlertDescription.certificate_unknown
    }

/**
 * RFC 8446 4.2.3 SignatureScheme as rustls 0.23's ring provider offers them, in its order, each with the webpki algorithm
 * that verifies a TLS 1.3 CertificateVerify under it; the rsa_pkcs1 schemes name certificate signatures only.
 */
enum class SignatureScheme(val code: Int, val algorithm: SignatureVerificationAlgorithm?) {
    ecdsa_secp384r1_sha384(0x0503, SignatureVerificationAlgorithm.ECDSA_P384_SHA384),
    ecdsa_secp256r1_sha256(0x0403, SignatureVerificationAlgorithm.ECDSA_P256_SHA256),
    ed25519(0x0807, SignatureVerificationAlgorithm.ED25519),
    rsa_pss_rsae_sha512(0x0806, SignatureVerificationAlgorithm.RSA_PSS_2048_8192_SHA512_LEGACY_KEY),
    rsa_pss_rsae_sha384(0x0805, SignatureVerificationAlgorithm.RSA_PSS_2048_8192_SHA384_LEGACY_KEY),
    rsa_pss_rsae_sha256(0x0804, SignatureVerificationAlgorithm.RSA_PSS_2048_8192_SHA256_LEGACY_KEY),
    rsa_pkcs1_sha512(0x0601, null),
    rsa_pkcs1_sha384(0x0501, null),
    rsa_pkcs1_sha256(0x0401, null),
}

/** RFC 8446 B.4 CipherSuite, null for the TLS 1.2 suites. */
val TlsCipherSuite.codePoint: Int?
    get() = when (this) {
        TlsCipherSuite.TLS_AES_128_GCM_SHA256 -> 0x1301
        TlsCipherSuite.TLS_AES_256_GCM_SHA384 -> 0x1302
        TlsCipherSuite.TLS_CHACHA20_POLY1305_SHA256 -> 0x1303
        else -> null
    }

/** RFC 8446 4.2.7 NamedGroup, null for secp521r1 (no curve here). */
val TlsSupportedGroup.codePoint: Int?
    get() = when (this) {
        TlsSupportedGroup.X25519 -> 0x001D
        TlsSupportedGroup.P256 -> 0x0017
        TlsSupportedGroup.P384 -> 0x0018
        TlsSupportedGroup.P521 -> null
    }

/** RFC 7301 ProtocolName from the IANA ALPN registry. */
val TlsApplicationProtocol.protocolName: String
    get() = when (this) {
        TlsApplicationProtocol.H2 -> "h2"
        TlsApplicationProtocol.HTTP_1_1 -> "http/1.1"
    }

val TlsCipherSuite.hashLength: Int get() = if (this == TlsCipherSuite.TLS_AES_256_GCM_SHA384) 48 else 32

val TlsCipherSuite.keyLength: Int get() = if (this == TlsCipherSuite.TLS_AES_128_GCM_SHA256) 16 else 32

/** The suite's Hash over [messages]. */
fun TlsCipherSuite.hash(messages: ByteArray): ByteArray = if (hashLength == 48) Sha384Pure.digest(messages) else Sha256Pure.digest(messages)

fun TlsCipherSuite.hmac(key: ByteArray, text: ByteArray): ByteArray = if (hashLength == 48) hmacSha384(key, text) else hmacSha256(key, text)

/** RFC 8446 7.1 HKDF-Expand-Label(Secret, Label, Context, Length) with the "tls13 " label prefix. */
fun TlsCipherSuite.hkdfExpandLabel(secret: ByteArray, label: String, context: ByteArray, length: Int): ByteArray {
    val fullLabel = ("tls13 $label").encodeToByteArray()
    val info = byteArrayOf((length shr 8).toByte(), length.toByte(), fullLabel.size.toByte()) + fullLabel + context.size.toByte() + context
    return hkdfExpand(secret, info, length) { key, text -> hmac(key, text) }
}

/** RFC 8446 7.1 Derive-Secret(Secret, Label, Messages). */
fun TlsCipherSuite.deriveSecret(secret: ByteArray, label: String, messages: ByteArray): ByteArray =
    hkdfExpandLabel(secret, label, hash(messages), hashLength)

/** RFC 8446 5.2-5.3 record protection under one traffic secret: write_key and write_iv, the nonce write_iv XOR the 64-bit record sequence number. */
class RecordProtection(val suite: TlsCipherSuite, secret: ByteArray) {
    val key = suite.hkdfExpandLabel(secret, "key", ByteArray(0), suite.keyLength)
    val iv = suite.hkdfExpandLabel(secret, "iv", ByteArray(0), 12)
    val gcm = if (suite == TlsCipherSuite.TLS_CHACHA20_POLY1305_SHA256) null else AesGcm(key)
    var sequence = 0L

    fun nonce(): ByteArray {
        val nonce = iv.copyOf()
        for (i in 0 until 8) nonce[4 + i] = (nonce[4 + i].toInt() xor (sequence ushr (56 - 8 * i)).toInt()).toByte()
        sequence++
        return nonce
    }

    /** TLSCiphertext carrying TLSInnerPlaintext { [content], [type] } without padding. */
    fun seal(type: Int, content: ByteArray): ByteArray {
        val inner = content + type.toByte()
        val length = inner.size + 16
        val header = byteArrayOf(Tls13Client.ContentType.application_data.toByte(), 3, 3, (length shr 8).toByte(), length.toByte())
        val nonce = nonce()
        return header + if (gcm != null) gcm.encrypt(nonce, header, inner) else ChaCha20Poly1305.encrypt(key, nonce, header, inner)
    }

    /** TLSInnerPlaintext of encrypted_record under the record [header], null when it does not authenticate. */
    fun open(header: ByteArray, encrypted: ByteArray): ByteArray? {
        val nonce = nonce()
        return if (gcm != null) gcm.decrypt(nonce, header, encrypted) else ChaCha20Poly1305.decrypt(key, nonce, header, encrypted)
    }
}

/** RFC 8446 4.2.8 KeyShareEntry for [group]: an ephemeral key pair, key_exchange as 4.2.8.2 encodes it. */
class KeyShare(val group: TlsSupportedGroup) {
    val curve = when (group) {
        TlsSupportedGroup.P256 -> WeierstrassCurve.P256
        TlsSupportedGroup.P384 -> WeierstrassCurve.P384
        else -> null
    }
    val privateKey: ByteArray = curve?.privateKey() ?: ByteArray(32).also { platformGetRandom(it) }
    val keyExchange: ByteArray = curve?.publicKey(privateKey) ?: x25519(privateKey, X25519_BASE_POINT)

    /** RFC 8446 7.4: the ECDH x-coordinate, or the X25519 output refused when all zero; null for an invalid peer share. */
    fun sharedSecret(peer: ByteArray): ByteArray? {
        if (curve != null) return curve.ecdh(privateKey, peer)
        if (peer.size != 32) return null
        val shared = x25519(privateKey, peer)
        return if (shared.fold(0) { acc, b -> acc or b.toInt() } == 0) null else shared
    }
}

/**
 * RFC 8446 TLS 1.3 client connection: the full (EC)DHE handshake (no PSK, no 0-RTT) over [groups] with one key share and
 * HelloRetryRequest, the AEAD [cipherSuites], the server authenticated by [verifyServerCertificate] against [anchors]
 * ([serverName] checked when [verifyName]) and by CertificateVerify under one of [signatureSchemes] (fewer than all
 * offers every scheme for certificates in signature_algorithms_cert), an empty client Certificate when one is
 * requested, then NewSessionTicket (ignored) and KeyUpdate. Records go out unpadded.
 */
class Tls13Client(
    val serverName: String,
    val verifyName: Boolean,
    val anchors: Series<TrustAnchor>,
    val cipherSuites: List<TlsCipherSuite>,
    val groups: List<TlsSupportedGroup>,
    val applicationProtocols: List<String>,
    val time: () -> Long,
    val signatureSchemes: List<SignatureScheme> = SignatureScheme.entries,
) {
    /** RFC 8446 A.1 client states. */
    enum class State { START, WAIT_SH, WAIT_EE, WAIT_CERT_CR, WAIT_CERT, WAIT_CV, WAIT_FINISHED, CONNECTED }

    /** RFC 8446 B.1 ContentType. */
    object ContentType {
        const val change_cipher_spec = 20
        const val alert = 21
        const val handshake = 22
        const val application_data = 23
    }

    /** RFC 8446 B.3 HandshakeType. */
    object HandshakeType {
        const val client_hello = 1
        const val server_hello = 2
        const val new_session_ticket = 4
        const val encrypted_extensions = 8
        const val certificate = 11
        const val certificate_request = 13
        const val certificate_verify = 15
        const val finished = 20
        const val key_update = 24
        const val message_hash = 254
    }

    /** RFC 8446 4.2 ExtensionType. */
    object ExtensionType {
        const val server_name = 0
        const val supported_groups = 10
        const val signature_algorithms = 13
        const val application_layer_protocol_negotiation = 16
        const val supported_versions = 43
        const val cookie = 44
        const val signature_algorithms_cert = 50
        const val key_share = 51
    }

    /** RFC 8446 3 presentation-language reader; running past [limit] is a decode_error. */
    class Reader(val bytes: ByteArray, var at: Int = 0, val limit: Int = bytes.size) {
        val remaining: Int get() = limit - at

        fun u8(): Int {
            if (at >= limit) throw TlsAlert(AlertDescription.decode_error)
            return bytes[at++].toInt() and 0xFF
        }

        fun u16(): Int = (u8() shl 8) or u8()

        fun u24(): Int = (u8() shl 16) or u16()

        fun bytes(n: Int): ByteArray {
            if (n > remaining) throw TlsAlert(AlertDescription.decode_error)
            return bytes.copyOfRange(at, at + n).also { at += n }
        }

        fun vector8(): ByteArray = bytes(u8())

        fun vector16(): ByteArray = bytes(u16())

        fun vector24(): ByteArray = bytes(u24())

        fun reader(n: Int): Reader {
            if (n > remaining) throw TlsAlert(AlertDescription.decode_error)
            return Reader(bytes, at, at + n).also { at += n }
        }

        fun end() {
            if (at != limit) throw TlsAlert(AlertDescription.decode_error)
        }
    }

    companion object {
        /** RFC 8446 4.1.3: SHA-256 of "HelloRetryRequest". */
        val HELLO_RETRY_REQUEST = "cf21ad74e59a6111be1d8c021e65b891c2a211167abb8c5e079e09e2c8a8339c".hexToByteArray()

        /** rustls' MAX_HANDSHAKE_SIZE. */
        const val MAX_HANDSHAKE_SIZE = 0xffff

        fun u16(v: Int) = byteArrayOf((v shr 8).toByte(), v.toByte())

        fun u24(v: Int) = byteArrayOf((v shr 16).toByte(), (v shr 8).toByte(), v.toByte())

        fun vector8(b: ByteArray) = byteArrayOf(b.size.toByte()) + b

        fun vector16(b: ByteArray) = u16(b.size) + b

        fun concat(parts: List<ByteArray>): ByteArray {
            val out = ByteArray(parts.sumOf { it.size })
            var at = 0
            for (part in parts) {
                part.copyInto(out, at)
                at += part.size
            }
            return out
        }

        /** Handshake { msg_type, length, body }. */
        fun handshakeMessage(type: Int, body: ByteArray) = byteArrayOf(type.toByte()) + u24(body.size) + body

        /** TLSPlaintext; [version] is 0x0301 for an initial ClientHello (5.1). */
        fun record(type: Int, fragment: ByteArray, version: Int = 0x0303) = byteArrayOf(type.toByte(), (version shr 8).toByte(), version.toByte()) + u16(fragment.size) + fragment

        fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
            if (a.size != b.size) return false
            var difference = 0
            for (i in a.indices) difference = difference or (a[i].toInt() xor b[i].toInt())
            return difference == 0
        }
    }

    var state = State.START
    val random = ByteArray(32).also { platformGetRandom(it) }
    var keyShare = KeyShare(groups.first())
    var offered = emptySet<Int>()
    var transcript = ByteArray(0)
    var suite: TlsCipherSuite? = null
    var retried = false
    var handshakeSecret = ByteArray(0)
    var clientHandshakeTrafficSecret = ByteArray(0)
    var serverHandshakeTrafficSecret = ByteArray(0)
    var clientApplicationTrafficSecret = ByteArray(0)
    var serverApplicationTrafficSecret = ByteArray(0)
    var read: RecordProtection? = null
    var write: RecordProtection? = null
    var certificateRequestContext: ByteArray? = null
    var endEntity: X509Certificate? = null
    var applicationProtocol: String? = null
    /** A close_notify arrived: what follows is ignored (6.1). */
    var closed = false
    /** Handshake bytes awaiting the rest of their message. */
    var handshakeMessages = ByteArray(0)
    /** Records to send, drained by the caller. */
    val outgoing = ArrayList<ByteArray>()
    /** Application data received, drained by the caller. */
    val applicationData = ArrayList<ByteArray>()

    init {
        require(cipherSuites.isNotEmpty() && cipherSuites.all { it.codePoint != null }) { "TLS 1.3 cipher suites" }
        require(groups.isNotEmpty() && groups.all { it.codePoint != null }) { "TLS 1.3 key exchange groups" }
    }

    /** The first flight: ClientHello in a TLSPlaintext record. */
    fun clientHello(): ByteArray {
        check(state == State.START) { "ClientHello already sent" }
        val hello = clientHelloMessage(null)
        transcript = hello
        state = State.WAIT_SH
        return record(ContentType.handshake, hello, 0x0301)
    }

    fun clientHelloMessage(cookie: ByteArray?): ByteArray {
        val extensions = LinkedHashMap<Int, ByteArray>()
        if (ipAddress(serverName) == null) extensions[ExtensionType.server_name] = vector16(byteArrayOf(0) + vector16(serverName.removeSuffix(".").encodeToByteArray()))
        extensions[ExtensionType.supported_versions] = vector8(u16(0x0304))
        extensions[ExtensionType.supported_groups] = vector16(concat(groups.map { u16(it.codePoint!!) }))
        extensions[ExtensionType.signature_algorithms] = vector16(concat(signatureSchemes.map { u16(it.code) }))
        if (signatureSchemes != SignatureScheme.entries)
            extensions[ExtensionType.signature_algorithms_cert] = vector16(concat(SignatureScheme.entries.map { u16(it.code) }))
        extensions[ExtensionType.key_share] = vector16(u16(keyShare.group.codePoint!!) + vector16(keyShare.keyExchange))
        if (applicationProtocols.isNotEmpty())
            extensions[ExtensionType.application_layer_protocol_negotiation] = vector16(concat(applicationProtocols.map { vector8(it.encodeToByteArray()) }))
        if (cookie != null) extensions[ExtensionType.cookie] = vector16(cookie)
        offered = extensions.keys.toSet()
        val body = u16(0x0303) + random + vector8(ByteArray(0)) + vector16(concat(cipherSuites.map { u16(it.codePoint!!) })) +
            vector8(byteArrayOf(0)) + vector16(concat(extensions.map { (type, data) -> u16(type) + vector16(data) }))
        return handshakeMessage(HandshakeType.client_hello, body)
    }

    /** Consumes the whole records at the front of [input] and returns how many bytes they span. */
    fun receive(input: ByteArray): Int {
        var at = 0
        while (input.size - at >= 5) {
            val length = ((input[at + 3].toInt() and 0xFF) shl 8) or (input[at + 4].toInt() and 0xFF)
            if (length > 16384 + 256) throw TlsAlert(AlertDescription.record_overflow)
            if (input.size - at - 5 < length) break
            val header = input.copyOfRange(at, at + 5)
            val fragment = input.copyOfRange(at + 5, at + 5 + length)
            at += 5 + length
            if (!closed) receiveRecord(header, fragment)
        }
        return at
    }

    fun receiveRecord(header: ByteArray, fragment: ByteArray) {
        val type = header[0].toInt() and 0xFF
        if (type == ContentType.change_cipher_spec) {
            // 5: one unprotected 0x01 between the first ClientHello and the server Finished is dropped
            if (fragment.size == 1 && fragment[0] == 1.toByte() && state != State.START && state != State.CONNECTED) return
            throw TlsAlert(AlertDescription.unexpected_message)
        }
        val protection = read
        val contentType: Int
        val content: ByteArray
        if (protection == null) {
            if (type != ContentType.handshake && type != ContentType.alert) throw TlsAlert(AlertDescription.unexpected_message)
            if (fragment.size > 16384) throw TlsAlert(AlertDescription.record_overflow)
            contentType = type
            content = fragment
        } else {
            if (type != ContentType.application_data) throw TlsAlert(AlertDescription.unexpected_message)
            val inner = protection.open(header, fragment) ?: throw TlsAlert(AlertDescription.bad_record_mac)
            var end = inner.size
            while (end > 0 && inner[end - 1] == 0.toByte()) end--
            if (end == 0) throw TlsAlert(AlertDescription.unexpected_message)
            if (end - 1 > 16384) throw TlsAlert(AlertDescription.record_overflow)
            contentType = inner[end - 1].toInt() and 0xFF
            content = inner.copyOf(end - 1)
        }
        when (contentType) {
            ContentType.alert -> {
                if (handshakeMessages.isNotEmpty()) throw TlsAlert(AlertDescription.unexpected_message)
                if (content.size != 2) throw TlsAlert(AlertDescription.decode_error)
                val description = content[1].toInt() and 0xFF
                if (description == AlertDescription.close_notify.code) closed = true
                else throw TlsAlert(description, received = true)
            }
            ContentType.handshake -> {
                if (content.isEmpty()) throw TlsAlert(AlertDescription.unexpected_message)
                handshakeMessages += content
                while (handshakeMessages.size >= 4) {
                    val length = ((handshakeMessages[1].toInt() and 0xFF) shl 16) or ((handshakeMessages[2].toInt() and 0xFF) shl 8) or (handshakeMessages[3].toInt() and 0xFF)
                    if (length > MAX_HANDSHAKE_SIZE) throw TlsAlert(AlertDescription.decode_error)
                    if (handshakeMessages.size < 4 + length) break
                    val message = handshakeMessages.copyOf(4 + length)
                    handshakeMessages = handshakeMessages.copyOfRange(4 + length, handshakeMessages.size)
                    handshake(message)
                }
            }
            ContentType.application_data -> {
                if (state != State.CONNECTED || handshakeMessages.isNotEmpty()) throw TlsAlert(AlertDescription.unexpected_message)
                if (content.isNotEmpty()) applicationData += content
            }
            else -> throw TlsAlert(AlertDescription.unexpected_message)
        }
    }

    fun handshake(message: ByteArray) {
        val type = message[0].toInt() and 0xFF
        val body = Reader(message, 4)
        val expected = when (state) {
            State.WAIT_SH -> type == HandshakeType.server_hello
            State.WAIT_EE -> type == HandshakeType.encrypted_extensions
            State.WAIT_CERT_CR -> type == HandshakeType.certificate_request || type == HandshakeType.certificate
            State.WAIT_CERT -> type == HandshakeType.certificate
            State.WAIT_CV -> type == HandshakeType.certificate_verify
            State.WAIT_FINISHED -> type == HandshakeType.finished
            State.CONNECTED -> type == HandshakeType.new_session_ticket || type == HandshakeType.key_update
            State.START -> false
        }
        if (!expected) throw TlsAlert(AlertDescription.unexpected_message)
        when (type) {
            HandshakeType.server_hello -> serverHello(message, body)
            HandshakeType.encrypted_extensions -> encryptedExtensions(message, body)
            HandshakeType.certificate_request -> certificateRequest(message, body)
            HandshakeType.certificate -> certificate(message, body)
            HandshakeType.certificate_verify -> certificateVerify(message, body)
            HandshakeType.finished -> finished(message, body)
            HandshakeType.new_session_ticket -> newSessionTicket(body)
            HandshakeType.key_update -> keyUpdate(body)
        }
    }

    /** An extensions block; a repeated type is an illegal_parameter. */
    fun extensions(body: Reader): Map<Int, ByteArray> {
        val list = body.reader(body.u16())
        val extensions = LinkedHashMap<Int, ByteArray>()
        while (list.remaining > 0) {
            val type = list.u16()
            if (extensions.put(type, list.vector16()) != null) throw TlsAlert(AlertDescription.illegal_parameter)
        }
        return extensions
    }

    /** 4.2: an extension the ClientHello did not offer is unsupported_extension; an offered one this message may not carry, illegal_parameter. */
    fun permit(extensions: Map<Int, ByteArray>, allowed: Set<Int>, unsolicited: Set<Int> = emptySet()) {
        for (type in extensions.keys) {
            if (type in unsolicited) continue
            if (type !in offered) throw TlsAlert(AlertDescription.unsupported_extension)
            if (type !in allowed) throw TlsAlert(AlertDescription.illegal_parameter)
        }
    }

    /** 5.1: a message preceding a key change ends its record. */
    fun keyChange() {
        if (handshakeMessages.isNotEmpty()) throw TlsAlert(AlertDescription.unexpected_message)
    }

    fun serverHello(message: ByteArray, body: Reader) {
        if (body.u16() != 0x0303) throw TlsAlert(AlertDescription.protocol_version)
        val serverRandom = body.bytes(32)
        if (body.vector8().isNotEmpty()) throw TlsAlert(AlertDescription.illegal_parameter)
        val code = body.u16()
        val selected = cipherSuites.firstOrNull { it.codePoint == code } ?: throw TlsAlert(AlertDescription.illegal_parameter)
        if (body.u8() != 0) throw TlsAlert(AlertDescription.illegal_parameter)
        // a ServerHello without extensions negotiates TLS 1.2 or below
        if (body.remaining == 0) throw TlsAlert(AlertDescription.protocol_version)
        val extensions = extensions(body)
        body.end()
        val version = extensions[ExtensionType.supported_versions] ?: throw TlsAlert(AlertDescription.protocol_version)
        if (!version.contentEquals(u16(0x0304))) throw TlsAlert(AlertDescription.illegal_parameter)
        if (serverRandom.contentEquals(HELLO_RETRY_REQUEST)) return helloRetryRequest(message, selected, extensions)
        if (retried && selected != suite) throw TlsAlert(AlertDescription.illegal_parameter)
        permit(extensions, setOf(ExtensionType.supported_versions, ExtensionType.key_share))
        val share = Reader(extensions[ExtensionType.key_share] ?: throw TlsAlert(AlertDescription.missing_extension))
        val group = share.u16()
        val keyExchange = share.vector16()
        share.end()
        if (group != keyShare.group.codePoint) throw TlsAlert(AlertDescription.illegal_parameter)
        val shared = keyShare.sharedSecret(keyExchange) ?: throw TlsAlert(AlertDescription.illegal_parameter)
        suite = selected
        transcript += message
        val zeros = ByteArray(selected.hashLength)
        val earlySecret = hkdfExtract(zeros, zeros) { key, text -> selected.hmac(key, text) }
        handshakeSecret = hkdfExtract(selected.deriveSecret(earlySecret, "derived", ByteArray(0)), shared) { key, text -> selected.hmac(key, text) }
        clientHandshakeTrafficSecret = selected.deriveSecret(handshakeSecret, "c hs traffic", transcript)
        serverHandshakeTrafficSecret = selected.deriveSecret(handshakeSecret, "s hs traffic", transcript)
        read = RecordProtection(selected, serverHandshakeTrafficSecret)
        keyChange()
        state = State.WAIT_EE
    }

    /** 4.1.4: the requested group's share (and any cookie) in a second ClientHello, ClientHello1 replaced in the transcript by message_hash. */
    fun helloRetryRequest(message: ByteArray, selected: TlsCipherSuite, extensions: Map<Int, ByteArray>) {
        if (retried) throw TlsAlert(AlertDescription.unexpected_message)
        retried = true
        permit(extensions, setOf(ExtensionType.supported_versions, ExtensionType.key_share), setOf(ExtensionType.cookie))
        val requested = extensions[ExtensionType.key_share]?.let { data -> Reader(data).run { u16().also { end() } } }
        val cookie = extensions[ExtensionType.cookie]?.let { data -> Reader(data).run { vector16().also { end() } } }
        if (requested == null && cookie == null) throw TlsAlert(AlertDescription.illegal_parameter)
        if (requested != null) {
            val group = groups.firstOrNull { it.codePoint == requested } ?: throw TlsAlert(AlertDescription.illegal_parameter)
            if (group == keyShare.group) throw TlsAlert(AlertDescription.illegal_parameter)
            keyShare = KeyShare(group)
        }
        suite = selected
        val hello1 = selected.hash(transcript)
        transcript = handshakeMessage(HandshakeType.message_hash, hello1) + message
        val hello = clientHelloMessage(cookie)
        transcript += hello
        outgoing += record(ContentType.handshake, hello)
    }

    fun encryptedExtensions(message: ByteArray, body: Reader) {
        val extensions = extensions(body)
        body.end()
        permit(extensions, setOf(ExtensionType.server_name, ExtensionType.supported_groups, ExtensionType.application_layer_protocol_negotiation))
        extensions[ExtensionType.server_name]?.let { if (it.isNotEmpty()) throw TlsAlert(AlertDescription.decode_error) }
        extensions[ExtensionType.application_layer_protocol_negotiation]?.let { data ->
            val list = Reader(data).run { reader(u16()).also { end() } }
            val name = list.vector8().decodeToString()
            list.end()
            if (name !in applicationProtocols) throw TlsAlert(AlertDescription.illegal_parameter)
            applicationProtocol = name
        }
        transcript += message
        state = State.WAIT_CERT_CR
    }

    fun certificateRequest(message: ByteArray, body: Reader) {
        val context = body.vector8()
        val extensions = extensions(body)
        body.end()
        if (ExtensionType.signature_algorithms !in extensions) throw TlsAlert(AlertDescription.missing_extension)
        certificateRequestContext = context
        transcript += message
        state = State.WAIT_CERT
    }

    fun certificate(message: ByteArray, body: Reader) {
        if (body.vector8().isNotEmpty()) throw TlsAlert(AlertDescription.illegal_parameter)
        val list = body.reader(body.u24())
        body.end()
        val chain = ArrayList<ByteArray>()
        while (list.remaining > 0) {
            val data = list.vector24()
            if (data.isEmpty()) throw TlsAlert(AlertDescription.decode_error)
            // CertificateEntry extensions answer ClientHello ones; none were offered
            if (list.vector16().isNotEmpty()) throw TlsAlert(AlertDescription.unsupported_extension)
            chain += data
        }
        if (chain.isEmpty()) throw TlsAlert(AlertDescription.decode_error)
        endEntity = try {
            verifyServerCertificate(chain, anchors, if (verifyName) serverName.removeSuffix(".") else null, time())
        } catch (e: CertificateException) {
            throw TlsAlert(e.error.alert, e)
        }
        transcript += message
        state = State.WAIT_CV
    }

    fun certificateVerify(message: ByteArray, body: Reader) {
        val code = body.u16()
        val signature = body.vector16()
        body.end()
        val algorithm = signatureSchemes.firstOrNull { it.code == code }?.algorithm ?: throw TlsAlert(AlertDescription.illegal_parameter)
        val content = ByteArray(64) { 0x20 } + "TLS 1.3, server CertificateVerify".encodeToByteArray() + 0 + suite!!.hash(transcript)
        try {
            verifySignature(algorithm, endEntity!!.subjectPublicKeyInfo, content, signature)
        } catch (e: CertificateException) {
            throw TlsAlert(e.error.alert, e)
        }
        transcript += message
        state = State.WAIT_FINISHED
    }

    fun finished(message: ByteArray, body: Reader) {
        val selected = suite!!
        val verifyData = body.bytes(selected.hashLength)
        body.end()
        val serverFinishedKey = selected.hkdfExpandLabel(serverHandshakeTrafficSecret, "finished", ByteArray(0), selected.hashLength)
        if (!constantTimeEquals(selected.hmac(serverFinishedKey, selected.hash(transcript)), verifyData)) throw TlsAlert(AlertDescription.decrypt_error)
        transcript += message
        val masterSecret = hkdfExtract(selected.deriveSecret(handshakeSecret, "derived", ByteArray(0)), ByteArray(selected.hashLength)) { key, text -> selected.hmac(key, text) }
        clientApplicationTrafficSecret = selected.deriveSecret(masterSecret, "c ap traffic", transcript)
        serverApplicationTrafficSecret = selected.deriveSecret(masterSecret, "s ap traffic", transcript)
        read = RecordProtection(selected, serverApplicationTrafficSecret)
        keyChange()
        val flight = ArrayList<ByteArray>()
        certificateRequestContext?.let { context ->
            val certificate = handshakeMessage(HandshakeType.certificate, vector8(context) + u24(0))
            transcript += certificate
            flight += certificate
        }
        val clientFinishedKey = selected.hkdfExpandLabel(clientHandshakeTrafficSecret, "finished", ByteArray(0), selected.hashLength)
        val clientFinished = handshakeMessage(HandshakeType.finished, selected.hmac(clientFinishedKey, selected.hash(transcript)))
        transcript += clientFinished
        flight += clientFinished
        outgoing += RecordProtection(selected, clientHandshakeTrafficSecret).seal(ContentType.handshake, concat(flight))
        write = RecordProtection(selected, clientApplicationTrafficSecret)
        state = State.CONNECTED
    }

    fun newSessionTicket(body: Reader) {
        body.bytes(8)
        body.vector8()
        if (body.vector16().isEmpty()) throw TlsAlert(AlertDescription.decode_error)
        body.vector16()
        body.end()
    }

    /** 4.6.3: the next server traffic secret, and when update_requested ours after a KeyUpdate under the current one. */
    fun keyUpdate(body: Reader) {
        val request = body.u8()
        body.end()
        if (request > 1) throw TlsAlert(AlertDescription.illegal_parameter)
        val selected = suite!!
        serverApplicationTrafficSecret = selected.hkdfExpandLabel(serverApplicationTrafficSecret, "traffic upd", ByteArray(0), selected.hashLength)
        read = RecordProtection(selected, serverApplicationTrafficSecret)
        keyChange()
        if (request == 1) {
            outgoing += write!!.seal(ContentType.handshake, handshakeMessage(HandshakeType.key_update, byteArrayOf(0)))
            clientApplicationTrafficSecret = selected.hkdfExpandLabel(clientApplicationTrafficSecret, "traffic upd", ByteArray(0), selected.hashLength)
            write = RecordProtection(selected, clientApplicationTrafficSecret)
        }
    }

    /** Application data in records of at most 2^14 octets. */
    fun seal(plaintext: ByteArray): ByteArray {
        check(state == State.CONNECTED) { "TLS 1.3 handshake not complete" }
        val records = ArrayList<ByteArray>()
        var at = 0
        while (at < plaintext.size) {
            val end = minOf(plaintext.size, at + 16384)
            records += write!!.seal(ContentType.application_data, plaintext.copyOfRange(at, end))
            at = end
        }
        return concat(records)
    }

    /** close_notify under the current write protection; nothing before the ClientHello. */
    fun closeNotify(): ByteArray = when {
        state == State.START -> ByteArray(0)
        write != null -> write!!.seal(ContentType.alert, byteArrayOf(1, 0))
        else -> record(ContentType.alert, byteArrayOf(1, 0))
    }
}

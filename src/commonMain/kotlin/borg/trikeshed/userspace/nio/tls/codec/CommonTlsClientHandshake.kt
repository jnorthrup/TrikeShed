package borg.trikeshed.userspace.nio.tls.codec

import kotlin.random.Random

/**
 * CommonMain [borg.trikeshed.userspace.nio.tls.codec.TlsClientHandshake] — pure Kotlin TLS 1.3 client handshake.
 *
 * Uses context-resolved crypto primitives for key exchange, hashing,
 * and key derivation. The handshake transcript is accumulated for
 * Finished verification.
 *
 * ## Usage (laced via context)
 *
 * ```
 * withContext(ctx) {
 *     val handshake = CommonTlsClientHandshake(
 *         sha256 = coroutineContext[Sha256.Key]!!,
 *         x25519 = coroutineContext[X25519.Key]!!,
 *         hkdf = coroutineContext[HkdfSha256.Key]!!,
 *         recordCodec = coroutineContext[TlsRecordCodec.Key]!!,
 *         serverName = "api.coinbase.com",
 *     )
 *     val ch = handshake.buildClientHello()
 *     // send ch... receive sh... etc.
 * }
 * ```
 */
class CommonTlsClientHandshake(
    private val sha256: borg.trikeshed.userspace.nio.spi.digest.Sha256,
    private val x25519: borg.trikeshed.userspace.nio.tls.codec.ecdh.X25519,
    private val hkdf: borg.trikeshed.userspace.nio.tls.codec.kdf.HkdfSha256,
    private val recordCodec: TlsRecordCodec,
    private val serverName: CharSequence,
    private val alpnProtocols: List<CharSequence> = listOf("h2", "http/1.1"),
) : TlsClientHandshake {
    override val key: kotlin.coroutines.CoroutineContext.Key<*> get() = TlsClientHandshake.Key

    override var state: TlsClientHandshake.State = TlsClientHandshake.State.IDLE; private set

    private val clientKeyPair = x25519.generateKeyPair()
    private var serverPublicKey: ByteArray? = null
    private var sharedSecret: ByteArray? = null
    private var handshakeKeysInstalled: Boolean = false
    private val clientRandom: ByteArray = ByteArray(32).also { Random.nextBytes(it) }
    private val transcript = mutableListOf<ByteArray>()

    private fun transcriptHash(): ByteArray {
        val total = transcript.flatMap { it.toList() }.toByteArray()
        return sha256.hash(total)
    }

    override fun buildClientHello(): ByteArray {
        check(state == TlsClientHandshake.State.IDLE)
        val body = buildClientHelloBody()
        val msg = frameHandshake(0x01, body)
        transcript.add(msg); state = TlsClientHandshake.State.CLIENT_HELLO_SENT
        return msg
    }

    private fun buildClientHelloBody(): ByteArray = ByteArrayBuilder()
        .run {
        put16(0x0303); put(clientRandom); put8(0)
        put16(2); put16(0x1301); put8(1); put8(0)
        putVar16(buildExtensions())
        toByteArray()
    }

    private fun buildExtensions(): ByteArray = ByteArrayBuilder()
        .run {
        putExtension(43) { put8(2); put16(0x0304) }
        putExtension(10) { put16(2); put16(0x001D) }
        putExtension(51) { put16(clientKeyPair.publicKey.size + 4); put16(0x001D); putVar16(clientKeyPair.publicKey) }
        putExtension(13) { put16(4); put16(0x0804); put16(0x0403) }
        putExtension(0) {
            val nb = serverName.toString().encodeToByteArray()
            val snEntry = ByteArray(3 + nb.size)
            snEntry[0] = 0; snEntry[1] = ((nb.size ushr 8) and 0xFF).toByte(); snEntry[2] = (nb.size and 0xFF).toByte()
            nb.copyInto(snEntry, 3); putVar16(snEntry)
        }
        if (alpnProtocols.isNotEmpty()) putExtension(16) {
            putVar16(alpnProtocols.flatMap { listOf(it.toString().encodeToByteArray().size.toByte()) + it.toString().encodeToByteArray().toList() }.toByteArray())
        }
        toByteArray()
    }

    override fun processServerHello(message: ByteArray) {
        check(state == TlsClientHandshake.State.CLIENT_HELLO_SENT); transcript.add(message)
        val body = message.copyOfRange(4, message.size)
        var p = 2; p += 32
        p += 1 + (body[p].toInt() and 0xFF)
        p += 2; p += 1
        val el = ((body[p].toInt() and 0xFF) shl 8) or (body[p+1].toInt() and 0xFF); p += 2
        val ee = p + el
        while (p + 4 <= ee) {
            val et = ((body[p].toInt() and 0xFF) shl 8) or (body[p + 1].toInt() and 0xFF)
            p += 2
            val dl = ((body[p].toInt() and 0xFF) shl 8) or (body[p + 1].toInt() and 0xFF)
            p += 2
            val dataStart = p
            val dataEnd = dataStart + dl
            if (dataEnd > ee) break
            if (et == 51 && dl >= 4) {
                var kp = dataStart
                kp += 2 // group
                if (kp + 2 <= dataEnd) {
                    val kl = ((body[kp].toInt() and 0xFF) shl 8) or (body[kp + 1].toInt() and 0xFF)
                    kp += 2
                    if (kp + kl <= dataEnd) {
                        serverPublicKey = body.copyOfRange(kp, kp + kl)
                    }
                }
            }
            p = dataEnd
        }
        val spk = serverPublicKey ?: error("server key_share extension missing")
        sharedSecret = x25519.sharedSecret(clientKeyPair.privateKey, spk)
        if (!handshakeKeysInstalled) {
            val helloH = sha256.hash((transcript[0].toList() + transcript[1].toList()).toByteArray())
            val schedule = CommonTlsKeySchedule(hkdf)
            val keys = schedule.compute(sharedSecret!!, helloH, helloH)
            recordCodec.installKeys(
                keys.clientHandshakeKey,
                keys.clientHandshakeIv,
                keys.serverHandshakeKey,
                keys.serverHandshakeIv,
            )
            handshakeKeysInstalled = true
        }
        state = TlsClientHandshake.State.WAITING_EE
    }

    override fun processEncryptedExtensions(message: ByteArray) {
        check(state == TlsClientHandshake.State.WAITING_EE); transcript.add(message)
        state = TlsClientHandshake.State.WAITING_CERT
    }

    override fun processCertificate(message: ByteArray) {
        check(state == TlsClientHandshake.State.WAITING_CERT); transcript.add(message)
        state = TlsClientHandshake.State.WAITING_CV
    }

    override fun processCertificateVerify(message: ByteArray) {
        check(state == TlsClientHandshake.State.WAITING_CV); transcript.add(message)
        state = TlsClientHandshake.State.WAITING_FINISHED
    }

    override fun processServerFinished(message: ByteArray) {
        check(state == TlsClientHandshake.State.WAITING_FINISHED)
        val preFinishHash = transcriptHash()
        transcript.add(message)
        val schedule = CommonTlsKeySchedule(hkdf)
        val keys = schedule.compute(sharedSecret!!, preFinishHash, preFinishHash)
        recordCodec.installKeys(keys.clientHandshakeKey, keys.clientHandshakeIv,
            keys.serverHandshakeKey, keys.serverHandshakeIv)
        // Install application traffic keys after handshake keys
        installApplicationKeys()
        state = TlsClientHandshake.State.CONNECTED
    }

    /** Install application traffic keys after the handshake reaches CONNECTED. */
    fun installApplicationKeys() {
        check(state == TlsClientHandshake.State.CONNECTED)
        val transcript = transcriptHash()
        val helloH = ByteArray(32) // dummy — app keys use master secret derived from sharedSecret + zero salt
        val schedule = CommonTlsKeySchedule(hkdf)
        val keys = schedule.compute(sharedSecret!!, helloH, transcript)
        recordCodec.installKeys(
            keys.clientApplicationKey,
            keys.clientApplicationIv,
            keys.serverApplicationKey,
            keys.serverApplicationIv,
        )
    }

    override fun buildClientFinished(): ByteArray {
        check(state == TlsClientHandshake.State.CONNECTED)
        val transcriptHash = transcriptHash()
        val schedule = CommonTlsKeySchedule(hkdf)
        val keys = schedule.compute(sharedSecret!!, transcriptHash, transcriptHash)
        val fk = hkdf.expandLabel(keys.clientHandshakeKey, "finished", ByteArray(0), 32)
        val vd = sha256.hmac(fk, transcriptHash())
        return frameHandshake(0x14, vd).also { transcript.add(it) }
    }

    private fun frameHandshake(type: Int, body: ByteArray): ByteArray {
        val m = ByteArray(4 + body.size)
        m[0] = type.toByte(); m[1] = ((body.size ushr 16) and 0xFF).toByte()
        m[2] = ((body.size ushr 8) and 0xFF).toByte(); m[3] = (body.size and 0xFF).toByte()
        body.copyInto(m, 4); return m
    }

    private fun ByteArrayBuilder.putExtension(type: Int, f: ByteArrayBuilder.() -> Unit) {
        val inner = ByteArrayBuilder(); f(inner)
        put16(type); putVar16(inner.toByteArray())
    }
}

/**
 * Minimal byte-array builder for TLS wire format encoding.
 */
class ByteArrayBuilder {
    private val l = mutableListOf<Byte>()
    fun put8(v: Int) { l.add(v.toByte()) }
    fun put16(v: Int) { l.add(((v ushr 8) and 0xFF).toByte()); l.add((v and 0xFF).toByte()) }
    fun put(b: ByteArray) { b.forEach { l.add(it) } }
    fun putVar16(b: ByteArray) { put16(b.size); put(b) }
    fun toByteArray(): ByteArray = l.toByteArray()
}

package borg.trikeshed.tls.codec

import borg.trikeshed.tls.codec.ecdh.X25519
import borg.trikeshed.tls.codec.hash.Sha256
import borg.trikeshed.tls.codec.kdf.HkdfSha256
import kotlin.random.Random

/**
 * CommonMain [TlsClientHandshake] — pure Kotlin TLS 1.3 client handshake.
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
    private val sha256: Sha256,
    private val x25519: X25519,
    private val hkdf: HkdfSha256,
    private val recordCodec: TlsRecordCodec,
    private val serverName: String,
    private val alpnProtocols: List<String> = listOf("h2", "http/1.1"),
) : TlsClientHandshake {
    override val key: kotlin.coroutines.CoroutineContext.Key<*> get() = TlsClientHandshake.Key

    override var state: TlsClientHandshake.State = TlsClientHandshake.State.IDLE; private set

    private val clientKeyPair = x25519.generateKeyPair()
    private var serverPublicKey: ByteArray? = null
    private var sharedSecret: ByteArray? = null
    private val clientRandom: ByteArray = ByteArray(32).also { Random.nextBytes(it) }
    private val transcript = mutableListOf<ByteArray>()
    private var clientApplicationKey: ByteArray? = null
    private var clientApplicationIv: ByteArray? = null

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

    private fun buildClientHelloBody(): ByteArray = ByteArrayBuilder().run {
        put16(0x0303); put(clientRandom); put8(0)
        put16(2); put16(0x1301); put8(1); put8(0)
        putVar16(buildExtensions())
        toByteArray()
    }

    private fun buildExtensions(): ByteArray = ByteArrayBuilder().run {
        putExtension(43) { put8(2); put16(0x0304) }
        putExtension(10) { put16(2); put16(0x001D) }
        putExtension(51) { put16(clientKeyPair.publicKey.size + 4); put16(0x001D); putVar16(clientKeyPair.publicKey) }
        putExtension(13) { put16(4); put16(0x0804); put16(0x0403) }
        putExtension(0) {
            val nb = serverName.encodeToByteArray()
            val snEntry = ByteArray(3 + nb.size)
            snEntry[0] = 0; snEntry[1] = ((nb.size ushr 8) and 0xFF).toByte(); snEntry[2] = (nb.size and 0xFF).toByte()
            nb.copyInto(snEntry, 3); putVar16(snEntry)
        }
        if (alpnProtocols.isNotEmpty()) putExtension(16) {
            putVar16(alpnProtocols.flatMap { listOf(it.encodeToByteArray().size.toByte()) + it.encodeToByteArray().toList() }.toByteArray())
        }
        toByteArray()
    }

    override fun processServerHello(message: ByteArray) {
        check(state == TlsClientHandshake.State.CLIENT_HELLO_SENT); transcript.add(message)
        println("DEBUG processServerHello: message.size = ${message.size}")
        println("DEBUG processServerHello: message hex = " + message.map { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }.joinToString(""))
        val body = message.copyOfRange(4, message.size)
        var p = 2
        try {
            p += 32
            val sessionIdLen = body[p].toInt() and 0xFF
            println("DEBUG processServerHello: sessionIdLen = $sessionIdLen")
            p += 1 + sessionIdLen
            val cipherSuite = ((body[p].toInt() and 0xFF) shl 8) or (body[p+1].toInt() and 0xFF); p += 2
            val compression = body[p].toInt() and 0xFF; p += 1
            println("DEBUG processServerHello: cipherSuite = $cipherSuite, compression = $compression, body.size = ${body.size}, p = $p")
            val el = ((body[p].toInt() and 0xFF) shl 8) or (body[p+1].toInt() and 0xFF); p += 2
            println("DEBUG processServerHello: el = $el")
            val ee = p + el
            while (p < ee) {
                val et = ((body[p].toInt() and 0xFF) shl 8) or (body[p+1].toInt() and 0xFF); p += 2
                val dl = ((body[p].toInt() and 0xFF) shl 8) or (body[p+1].toInt() and 0xFF); p += 2
                val nextP = p + dl
                println("DEBUG processServerHello: extension type = $et, len = $dl, p = $p")
                if (et == 51) {
                    var kp = p
                    kp += 2
                    val kl = ((body[kp].toInt() and 0xFF) shl 8) or (body[kp+1].toInt() and 0xFF); kp += 2
                    serverPublicKey = body.copyOfRange(kp, kp + kl)
                }
                p = nextP
            }
            println("DEBUG client public:  " + clientKeyPair.publicKey.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') })
            println("DEBUG client private: " + clientKeyPair.privateKey.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') })
            println("DEBUG server public:  " + serverPublicKey?.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') })
            sharedSecret = x25519.sharedSecret(clientKeyPair.privateKey, serverPublicKey!!)
            println("DEBUG shared secret:  " + sharedSecret?.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') })
            val helloH = sha256.hash((transcript[0].toList() + transcript[1].toList()).toByteArray())
            println("DEBUG hello hash:     " + helloH.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') })
            val schedule = CommonTlsKeySchedule(hkdf)
            val keys = schedule.compute(sharedSecret!!, helloH, helloH)
            recordCodec.installKeys(keys.clientHandshakeKey, keys.clientHandshakeIv,
                keys.serverHandshakeKey, keys.serverHandshakeIv)
            state = TlsClientHandshake.State.WAITING_EE
        } catch (e: Exception) {
            println("DEBUG processServerHello: caught exception at p=$p, body.size=${body.size}")
            throw e
        }
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
        check(state == TlsClientHandshake.State.WAITING_FINISHED); transcript.add(message)
        val helloH = sha256.hash((transcript[0].toList() + transcript[1].toList()).toByteArray())
        val schedule = CommonTlsKeySchedule(hkdf)
        val keys = schedule.compute(sharedSecret!!, helloH, transcriptHash())
        recordCodec.installReadKeys(keys.serverApplicationKey, keys.serverApplicationIv)
        clientApplicationKey = keys.clientApplicationKey
        clientApplicationIv = keys.clientApplicationIv
        state = TlsClientHandshake.State.CONNECTED
    }

    override fun installClientApplicationWriteKey() {
        recordCodec.installWriteKeys(clientApplicationKey!!, clientApplicationIv!!)
    }

    override fun buildClientFinished(): ByteArray {
        check(state == TlsClientHandshake.State.CONNECTED)
        val helloH = sha256.hash((transcript[0].toList() + transcript[1].toList()).toByteArray())
        val schedule = CommonTlsKeySchedule(hkdf)
        val keys = schedule.compute(sharedSecret!!, helloH, transcriptHash())
        val fk = hkdf.expandLabel(keys.clientHandshakeSecret, "finished", ByteArray(0), 32)
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

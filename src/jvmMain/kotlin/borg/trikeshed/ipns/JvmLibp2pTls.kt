package borg.trikeshed.ipns

import borg.trikeshed.reactor.*
import org.bouncycastle.asn1.*
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.*
import java.io.ByteArrayInputStream
import java.math.BigInteger
import java.net.Socket
import java.security.*
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.Date
import javax.net.ssl.*

/** In-memory libp2p certificate and TLS engine policy. No socket, file, keystore-file or DNS effects. */
class JvmLibp2pTls(private val identity: IpnsKeyPair, private val crypto: IpnsCrypto) {
    private data class Credential(val key: KeyPair, val certificate: X509Certificate)
    private fun issue(): Credential {
        val key = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        return Credential(key, certificate(identity, crypto, key))
    }
    private var credential = issue()
    @Synchronized private fun current(): Credential {
        if (credential.certificate.notAfter.time - System.currentTimeMillis() < 3600000) credential = issue()
        return credential
    }
    val certificate: X509Certificate get() = current().certificate

    fun backend(expectedPeerId: ByteArray): TlsCodecBackend {
        require(expectedPeerId.size in 4..128)
        val expected = expectedPeerId.copyOf()
        val material = current()
        val keyStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
            setKeyEntry("libp2p", material.key.private, charArrayOf(), arrayOf(material.certificate))
        }
        val keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(keyStore, charArrayOf()) }
        val trust = object : X509ExtendedTrustManager() {
            private fun verify(chain: Array<out X509Certificate>?) {
                try {
                    require(chain?.size == 1) { "libp2p requires exactly one certificate" }
                    verifyCertificate(chain.single(), expected, crypto)
                } catch (failure: Exception) { throw CertificateException("libp2p identity validation failed", failure) }
            }
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = verify(chain)
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = verify(chain)
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) = verify(chain)
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) = verify(chain)
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) = verify(chain)
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) = verify(chain)
        }
        val context = SSLContext.getInstance("TLSv1.3").apply { init(keys.keyManagers, arrayOf(trust), SecureRandom()) }
        var engine: SSLEngine? = null
        val codec = JvmTlsCodecBackend { _, _ ->
            context.createSSLEngine().apply {
                useClientMode = true
                enabledProtocols = arrayOf("TLSv1.3")
                sslParameters = sslParameters.apply {
                    applicationProtocols = arrayOf("libp2p")
                    serverNames = emptyList() // The libp2p TLS v1 protocol forbids client SNI.
                    endpointIdentificationAlgorithm = null
                }
            }.also { engine = it }
        }
        fun verified(result: TlsCodecResult): TlsCodecResult {
            if (result.state.lifecycle == TlsConnectionState.OPEN) {
                check(engine?.session?.protocol == "TLSv1.3" && engine?.applicationProtocol == "libp2p") { "libp2p TLS version or ALPN differs" }
            }
            return result
        }
        return object : TlsCodecBackend by codec {
            override suspend fun handshake(config: TlsConfig, state: TlsFlowState): TlsCodecResult = verified(codec.handshake(config, state))
            override suspend fun downstream(config: TlsConfig, state: TlsFlowState, payload: TlsPayload): TlsCodecResult = verified(codec.downstream(config, state, payload))
        }
    }

    companion object {
        const val EXTENSION = "1.3.6.1.4.1.53594.1.1"
        private val PREFIX = "libp2p-tls-handshake:".toByteArray(Charsets.US_ASCII)

        private fun certificate(identity: IpnsKeyPair, crypto: IpnsCrypto, key: KeyPair): X509Certificate {
            val algorithm = AlgorithmIdentifier(ASN1ObjectIdentifier("1.2.840.10045.4.3.2"))
            val signed = DERSequence(arrayOf<ASN1Encodable>(
                DEROctetString(IpnsEncoding.publicKey(identity.publicKey)),
                DEROctetString(crypto.sign(identity.privateKey, PREFIX + key.public.encoded)),
            ))
            val now = System.currentTimeMillis()
            val generator = V3TBSCertificateGenerator().apply {
                setSerialNumber(ASN1Integer(BigInteger(128, SecureRandom()).setBit(127)))
                setSignature(algorithm)
                setIssuer(X500Name("CN=libp2p")); setSubject(X500Name("CN=libp2p"))
                setStartDate(Time(Date(now - 60000))); setEndDate(Time(Date(now + 86400000)))
                setSubjectPublicKeyInfo(SubjectPublicKeyInfo.getInstance(key.public.encoded))
                setExtensions(Extensions(arrayOf(Extension(ASN1ObjectIdentifier(EXTENSION), false, signed.encoded))))
            }
            val tbs = generator.generateTBSCertificate()
            val signature = Signature.getInstance("SHA256withECDSA").apply { initSign(key.private); update(tbs.encoded) }.sign()
            val der = DERSequence(arrayOf<ASN1Encodable>(tbs, algorithm, DERBitString(signature))).encoded
            return CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(der)) as X509Certificate
        }

        /** Authenticates Ed25519 and RSA host identities, including hashed RSA bootstrap peer IDs. */
        fun verifyCertificate(cert: X509Certificate, expectedPeerId: ByteArray, crypto: IpnsCrypto) {
            require(cert.encoded.size <= 16384)
            cert.checkValidity()
            cert.verify(cert.publicKey)
            require(cert.issuerUniqueID == null && cert.subjectUniqueID == null)
            require(cert.criticalExtensionOIDs.orEmpty().all { it == EXTENSION }) { "Unknown critical libp2p certificate extension" }
            val extension = requireNotNull(cert.getExtensionValue(EXTENSION)) { "Missing libp2p certificate extension" }
            val octets = ASN1OctetString.getInstance(extension).octets
            require(octets.size <= 12000)
            val signed = ASN1Sequence.getInstance(octets)
            require(signed.size() == 2)
            val public = ASN1OctetString.getInstance(signed.getObjectAt(0)).octets
            val signature = ASN1OctetString.getInstance(signed.getObjectAt(1)).octets
            require(public.size in 4..8192 && signature.size in 32..8192)
            val (type, key) = publicKey(public)
            val canonical = IpnsProtobuf.uint(1, type.toULong()) + IpnsProtobuf.bytes(2, key)
            val derived = if (canonical.size <= 42) byteArrayOf(0, canonical.size.toByte()) + canonical
            else byteArrayOf(0x12, 0x20) + MessageDigest.getInstance("SHA-256").digest(canonical)
            require(MessageDigest.isEqual(derived, expectedPeerId)) { "TLS peer ID differs from dialed peer" }
            val payload = PREFIX + cert.publicKey.encoded
            val valid = when (type) {
                1 -> crypto.verify(key, payload, signature)
                0 -> {
                    val rsa = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(key))
                    Signature.getInstance("SHA256withRSA").apply { initVerify(rsa); update(payload) }.verify(signature)
                }
                else -> throw IllegalArgumentException("Unsupported libp2p identity key type $type")
            }
            require(valid) { "Invalid libp2p identity signature" }
        }

        private fun publicKey(bytes: ByteArray): Pair<Int, ByteArray> {
            val fields = IpnsProtobuf.decode(bytes, 8192, 2)
            require(fields.size == 2)
            val kind = fields.single { it.number == 1 }
            val data = fields.single { it.number == 2 }
            require(kind.wireType == 0 && kind.integer <= 3uL && data.wireType == 2)
            val type = kind.integer.toInt()
            val key = data.bytes
            if (type == 1) require(key.size == 32)
            return type to key
        }
    }
}

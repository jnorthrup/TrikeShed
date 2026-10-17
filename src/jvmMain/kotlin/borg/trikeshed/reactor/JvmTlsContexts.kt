package borg.trikeshed.reactor

import borg.trikeshed.lib.*
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyFactory
import java.security.KeyStore
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.KeyManager
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory

/**
 * The SSLContexts TLS engines come from: one per identity (role, certificate and key files, key password, trust
 * store), shared by every engine made from it, while each connection still gets its own SSLEngine. Engine settings
 * resolved against the provider (protocol, suite, group and ALPN names) are kept per distinct settings.
 *
 * Rotation is explicit: [reload] re-reads the files of every identity built so far. Engines created afterwards use
 * the new material; engines already created keep theirs, with their sessions. A reload that fails changes nothing.
 */
class JvmTlsContexts {
    private data class Identity(
        val server: Boolean,
        val certificateFile: String?,
        val privateKeyFile: String?,
        val privateKeyPassword: String?,
        val trustStore: String?,
    )

    private data class Settings(
        val client: Boolean,
        val protocols: List<TlsProtocol>,
        val cipherSuites: List<TlsCipherSuite>,
        val groups: List<TlsSupportedGroup>,
        val alpn: List<TlsApplicationProtocol>,
    )

    private class Names(val protocols: Array<String>, val cipherSuites: Array<String>, val groups: Array<String>, val alpn: Array<String>)

    private val contexts = ConcurrentHashMap<Identity, SSLContext>()
    private val names = ConcurrentHashMap<Settings, Names>()
    private val built = AtomicLong()

    /** SSLContexts put in use so far, reloads included: one per identity while nothing rotates. */
    val builds: Long get() = built.get()

    /** The shared context of [config]'s identity in [role], built on first use. */
    fun context(config: TlsConfig, role: TlsRole): SSLContext =
        contexts.computeIfAbsent(Identity(role == TlsRole.SERVER, config.certificateFile, config.privateKeyFile,
            config.privateKeyPassword, config.trustStore)) { build(it).also { built.incrementAndGet() } }

    /**
     * A new engine for one connection from the shared context, with [config]'s settings. A client given [peerHost]
     * sends it as SNI and may resume that peer's sessions; without one it does neither.
     */
    fun engine(config: TlsConfig, role: TlsRole, peerHost: String? = null, peerPort: Int = -1): SSLEngine {
        val context = context(config, role)
        val client = role == TlsRole.CLIENT
        val engine = if (client && peerHost != null) context.createSSLEngine(peerHost, peerPort) else context.createSSLEngine()
        engine.useClientMode = client
        val resolved = names.computeIfAbsent(Settings(client, config.protocols.toList(), config.cipherSuites.toList(),
            config.supportedGroups.toList(), config.alpnProtocols.toList())) { resolve(engine, it) }
        if (resolved.protocols.isNotEmpty()) engine.enabledProtocols = resolved.protocols
        if (resolved.cipherSuites.isNotEmpty()) engine.enabledCipherSuites = resolved.cipherSuites
        val parameters = engine.sslParameters
        if (resolved.alpn.isNotEmpty()) parameters.applicationProtocols = resolved.alpn
        if (resolved.groups.isNotEmpty()) parameters.namedGroups = resolved.groups
        if (client && config.hostnameVerification) parameters.endpointIdentificationAlgorithm = "HTTPS"
        engine.sslParameters = parameters
        when (config.clientAuth) {
            ClientAuth.NONE -> {
                engine.needClientAuth = false
                engine.wantClientAuth = false
            }
            ClientAuth.REQUESTED -> engine.wantClientAuth = true
            ClientAuth.REQUIRED -> engine.needClientAuth = true
        }
        return engine
    }

    /** Re-reads every identity built so far, all or nothing: a failure leaves the previous contexts in use. */
    fun reload() {
        val rebuilt = contexts.keys.toList().associateWith(::build)
        contexts.putAll(rebuilt)
        built.addAndGet(rebuilt.size.toLong())
    }

    private fun resolve(engine: SSLEngine, settings: Settings): Names {
        val protocols = engine.supportedProtocols.toHashSet()
        val suites = engine.supportedCipherSuites.toHashSet()
        return Names(
            settings.protocols.map(::protocolNameOf).filter { it in protocols }.toTypedArray(),
            settings.cipherSuites.map(::cipherSuiteNameOf).filter { it in suites }.toTypedArray(),
            settings.groups.map(::namedGroupOf).toTypedArray(),
            settings.alpn.map { it.wireName }.toTypedArray(),
        )
    }

    private fun build(identity: Identity): SSLContext {
        val context = SSLContext.getInstance("TLS")
        context.init(keyManagers(identity), trustManagers(identity), null)
        return context
    }

    private fun keyManagers(identity: Identity): Array<KeyManager>? {
        if (identity.certificateFile == null || identity.privateKeyFile == null) {
            check(!identity.server) { "Server TLS requires certificateFile and privateKeyFile." }
            return null
        }
        val certs = loadCertificates(Path.of(identity.certificateFile))
        val privateKey = loadPrivateKey(Path.of(identity.privateKeyFile), certs.first().publicKey.algorithm)
        // In-memory KeyStore; a random password when none is given.
        val password = (identity.privateKeyPassword ?: run {
            val bytes = ByteArray(16)
            SecureRandom().nextBytes(bytes)
            Base64.getEncoder().encodeToString(bytes)
        }).toCharArray()
        val keyStore = KeyStore.getInstance(KeyStore.getDefaultType())
        keyStore.load(null, null)
        keyStore.setKeyEntry("tls", privateKey, password, certs.toTypedArray())
        val factory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        factory.init(keyStore, password)
        return factory.keyManagers
    }

    private fun trustManagers(identity: Identity): Array<TrustManager> {
        val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        val trustStore = identity.trustStore?.let {
            KeyStore.getInstance(KeyStore.getDefaultType()).apply {
                load(null, null)
                loadCertificates(Path.of(it)).forEachIndexed { index, cert -> setCertificateEntry("trust-$index", cert) }
            }
        }
        factory.init(trustStore)
        return factory.trustManagers
    }
}

internal fun loadCertificates(path: Path): List<X509Certificate> {
    val text = Files.readString(path)
    val blocks = pemBlocks(text, "CERTIFICATE")
    check(blocks.isNotEmpty()) { "No certificates found in $path" }
    val factory = CertificateFactory.getInstance("X.509")
    return blocks.map { block -> ByteArrayInputStream(block).use { factory.generateCertificate(it) as X509Certificate } }
}

internal fun loadPrivateKey(path: Path, preferredAlgorithm: String): PrivateKey {
    val text = Files.readString(path)
    check("BEGIN ENCRYPTED PRIVATE KEY" !in text) { "Encrypted private keys are not supported yet for $path" }
    val block = pemBlocks(text, "PRIVATE KEY").firstOrNull() ?: error("Expected PKCS#8 private key in $path")
    val spec = PKCS8EncodedKeySpec(block)
    return sequenceOf(preferredAlgorithm, "RSA", "EC", "DSA")
        .distinct()
        .firstNotNullOfOrNull { algorithm -> runCatching { KeyFactory.getInstance(algorithm).generatePrivate(spec) }.getOrNull() }
        ?: error("Could not decode private key in $path")
}

private fun pemBlocks(text: String, label: String): List<ByteArray> =
    Regex("-----BEGIN $label-----(.*?)-----END $label-----", setOf(RegexOption.DOT_MATCHES_ALL))
        .findAll(text).map { match -> Base64.getMimeDecoder().decode(match.groupValues[1]) }.toList()

internal fun protocolNameOf(protocol: TlsProtocol): String = when (protocol) {
    TlsProtocol.TLS13 -> "TLSv1.3"
    TlsProtocol.TLS12 -> "TLSv1.2"
}

internal fun protocolOf(name: String?): TlsProtocol? = when (name) {
    "TLSv1.3" -> TlsProtocol.TLS13
    "TLSv1.2" -> TlsProtocol.TLS12
    else -> null
}

internal fun cipherSuiteNameOf(cipherSuite: TlsCipherSuite): String = when (cipherSuite) {
    TlsCipherSuite.TLS_AES_128_GCM_SHA256 -> "TLS_AES_128_GCM_SHA256"
    TlsCipherSuite.TLS_AES_256_GCM_SHA384 -> "TLS_AES_256_GCM_SHA384"
    TlsCipherSuite.TLS_CHACHA20_POLY1305_SHA256 -> "TLS_CHACHA20_POLY1305_SHA256"
    TlsCipherSuite.ECDHE_RSA_AES128_GCM_SHA256 -> "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256"
    TlsCipherSuite.ECDHE_RSA_AES256_GCM_SHA384 -> "TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384"
    TlsCipherSuite.ECDHE_ECDSA_CHACHA20_POLY1305 -> "TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256"
    TlsCipherSuite.ECDHE_ECDSA_AES128_GCM_SHA256 -> "TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256"
    TlsCipherSuite.ECDHE_ECDSA_AES256_GCM_SHA384 -> "TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384"
}

internal fun cipherSuiteOf(name: String?): TlsCipherSuite? = when (name) {
    "TLS_AES_128_GCM_SHA256" -> TlsCipherSuite.TLS_AES_128_GCM_SHA256
    "TLS_AES_256_GCM_SHA384" -> TlsCipherSuite.TLS_AES_256_GCM_SHA384
    "TLS_CHACHA20_POLY1305_SHA256" -> TlsCipherSuite.TLS_CHACHA20_POLY1305_SHA256
    "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256" -> TlsCipherSuite.ECDHE_RSA_AES128_GCM_SHA256
    "TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384" -> TlsCipherSuite.ECDHE_RSA_AES256_GCM_SHA384
    "TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256" -> TlsCipherSuite.ECDHE_ECDSA_CHACHA20_POLY1305
    "TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256" -> TlsCipherSuite.ECDHE_ECDSA_AES128_GCM_SHA256
    "TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384" -> TlsCipherSuite.ECDHE_ECDSA_AES256_GCM_SHA384
    else -> null
}

internal fun namedGroupOf(group: TlsSupportedGroup): String = when (group) {
    TlsSupportedGroup.X25519 -> "x25519"
    TlsSupportedGroup.P256 -> "secp256r1"
    TlsSupportedGroup.P384 -> "secp384r1"
    TlsSupportedGroup.P521 -> "secp521r1"
}

/** The negotiated session as the TLS vocabulary names it; null before a protocol and suite are agreed. */
internal fun sessionOf(engine: SSLEngine): TlsSession? {
    val session = engine.session ?: return null
    val protocol = protocolOf(session.protocol) ?: return null
    val cipherSuite = cipherSuiteOf(session.cipherSuite) ?: return null
    val peerCertificate = try {
        (session.peerCertificates.firstOrNull() as? X509Certificate)?.let {
            TlsPeerCertificate(subject = it.subjectX500Principal?.name, issuer = it.issuerX500Principal?.name)
        }
    } catch (_: SSLPeerUnverifiedException) {
        null
    }
    val verified = try {
        session.peerCertificates.isNotEmpty()
    } catch (_: SSLPeerUnverifiedException) {
        false
    }
    return TlsSession(
        protocol = protocol,
        cipherSuite = cipherSuite,
        peerCertificate = peerCertificate,
        verified = verified,
        applicationProtocol = TlsApplicationProtocol.entries.firstOrNull { it.wireName == engine.applicationProtocol },
    )
}

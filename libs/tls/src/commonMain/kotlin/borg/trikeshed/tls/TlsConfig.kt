package borg.trikeshed.tls

import kotlinx.serialization.Serializable

/**
 * TLS endpoint configuration.
 */
@Serializable
data class TlsConfig(
    val keyStore: String? = null,
    val keyStorePassword: String? = null,
    val keyAlias: String? = null,
    val trustStore: String? = null,
    val trustStorePassword: String? = null,
    val protocols: List<String> = listOf("TLSv1.3", "TLSv1.2"),
    val ciphers: List<String> = listOf(
        "TLS_AES_256_GCM_SHA384",
        "TLS_CHACHA20_POLY1305_SHA256",
        "TLS_AES_128_GCM_SHA256",
    ),
    val clientAuth: ClientAuth = ClientAuth.NONE,
    val hostnameVerification: Boolean = false,
)

enum class ClientAuth {
    NONE,
    REQUESTED,
    REQUIRED,
}

/**
 * TLS endpoint abstraction — wraps SSLEngine for non-blocking I/O.
 *
 * Provides first-class TLS support:
 * - TLS handshake via suspend/resume
 * - Encrypted read/write
 * - SNI support
 * - Client certificate authentication
 */
interface TlsEndpoint {
    /** Perform TLS handshake via suspend/resume. */
    suspend fun handshake()

    /** Read decrypted data from the TLS stream. */
    suspend fun read(destination: java.nio.ByteBuffer): Int

    /** Write encrypted data to the TLS stream. */
    suspend fun write(source: java.nio.ByteBuffer): Int

    /** Close the TLS connection. */
    suspend fun close()

    /** Check if handshake completed successfully. */
    val isHandshakeComplete: Boolean

    /** Get the peer certificate. */
    val peerCertificate: java.security.cert.X509Certificate?

    /** Get the protocol negotiated. */
    val protocol: String?

    /** Get the cipher suite negotiated. */
    val cipherSuite: String?
}

/**
 * TLS element for structured concurrency integration.
 * Implements CoroutineContext.Element for use in coroutine contexts.
 */
interface TlsElement : kotlin.coroutines.CoroutineContext.Element {
    /** Current lifecycle state of the TLS element. */
    val state: ElementState

    /** Create a client-side TLS endpoint. */
    fun clientEndpoint(hostname: String): TlsEndpoint

    /** Create a server-side TLS endpoint. */
    fun serverEndpoint(): TlsEndpoint

    /** Open the TLS element (initialize resources). */
    suspend fun open()

    /** Close the TLS element (release resources). */
    suspend fun close()
}

/**
 * TLS element lifecycle states.
 */
enum class ElementState {
    CREATED,
    OPEN,
    ACTIVE,
    DRAINING,
    CLOSED;

    fun isAtLeast(other: ElementState): Boolean = this.ordinal >= other.ordinal
    fun isLessThan(other: ElementState): Boolean = this.ordinal < other.ordinal
}

/**
 * Context key for TlsElement in structured concurrency.
 */
object TlsKey : kotlin.coroutines.CoroutineContext.Key<TlsElement>

/**
 * ChannelRunner interface for reactor I/O operations.
 * The actual implementation is provided by the reactor module.
 */
interface ChannelRunner {
    suspend fun readAsync(fd: Int)
    suspend fun writeAsync(fd: Int)
    fun tcpConnect(host: String, port: Int): Int
}

/**
 * Creates and opens a TLS element.
 */
suspend fun openTlsElement(config: TlsConfig = TlsConfig()): TlsElement {
    throw NotImplementedError("TlsElement requires ChannelRunner from reactor context. Use openTlsElementWithRunner(config, channelRunner) instead.")
}

/**
 * JVM-specific function to create and open a TLS element with a ChannelRunner.
 */
@kotlin.jvm.JvmName("openTlsElementWithRunner")
expect suspend fun openTlsElementWithRunner(
    config: TlsConfig,
    channelRunner: ChannelRunner,
): TlsElement
package borg.trikeshed.reactor

import borg.trikeshed.context.AsyncContextElement
import borg.trikeshed.context.AsyncContextKey
import borg.trikeshed.context.ElementState
import borg.trikeshed.userspace.nio.ByteBuffer
import borg.trikeshed.userspace.nio.channels.spi.ProcessOperations
import borg.trikeshed.userspace.nio.channels.spi.ProcessResult
import borg.trikeshed.userspace.nio.spi.NioSupervisor
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.serialization.Serializable
import kotlin.coroutines.CoroutineContext

/**
 * Root-level TLS CCEK element.
 *
 * This keeps the choreography in the reactor root and uses the platform
 * [ProcessOperations] SPI as the lowest-common-denominator backend. The
 * current fallback is a worst-case `openssl s_client` exchange: it is
 * connection-oriented and sufficient for request/response flows, but it is
 * not a replacement for a fully streaming in-process TLS stack.
 */
@Serializable
data class TlsConfig(
    val trustStore: String? = null,
    val certificateFile: String? = null,
    val privateKeyFile: String? = null,
    val privateKeyPassword: String? = null,
    val protocols: List<TlsProtocol> = listOf(TlsProtocol.TLS13, TlsProtocol.TLS12),
    val cipherSuites: List<String> = listOf(
        "TLS_AES_128_GCM_SHA256",
        "TLS_AES_256_GCM_SHA384",
        "TLS_CHACHA20_POLY1305_SHA256",
        "ECDHE-RSA-AES128-GCM-SHA256",
        "ECDHE-RSA-AES256-GCM-SHA384",
        "ECDHE-ECDSA-CHACHA20-POLY1305",
        "ECDHE-ECDSA-AES128-GCM-SHA256",
        "ECDHE-ECDSA-AES256-GCM-SHA384",
    ),
    val supportedGroups: List<String> = listOf("X25519", "P-256", "P-384", "P-521"),
    val alpnProtocols: List<String> = listOf("h2", "http/1.1"),
    val clientAuth: ClientAuth = ClientAuth.NONE,
    val hostnameVerification: Boolean = true,
    val opensslCommand: String = "openssl",
)

enum class TlsProtocol {
    TLS13,
    TLS12,
}

enum class ClientAuth {
    NONE,
    REQUESTED,
    REQUIRED,
}

enum class TlsRole {
    CLIENT,
    SERVER,
}

data class TlsPeerCertificate(
    val subject: String? = null,
    val issuer: String? = null,
)

data class TlsSession(
    val protocol: String? = null,
    val cipherSuite: String? = null,
    val peerCertificate: TlsPeerCertificate? = null,
    val verified: Boolean = false,
    val stdout: ByteArray = byteArrayOf(),
    val stderr: ByteArray = byteArrayOf(),
)

interface TlsEndpoint {
    val role: TlsRole
    val remoteHost: String
    val remotePort: Int

    suspend fun handshake()
    suspend fun read(destination: ByteBuffer): Int
    suspend fun write(source: ByteBuffer): Int
    suspend fun close()

    val isHandshakeComplete: Boolean
    val peerCertificate: TlsPeerCertificate?
    val protocol: String?
    val cipherSuite: String?
    val lastSession: TlsSession?
}

class TlsElement(
    val config: TlsConfig,
    private val processOperations: ProcessOperations,
    parentJob: kotlinx.coroutines.Job? = null,
    private val ownedSupervisor: NioSupervisor? = null,
    override val fanoutSubscribers: List<AsyncContextElement> = emptyList(),
) : AsyncContextElement(ElementState.CREATED, parentJob) {
    companion object Key : AsyncContextKey<TlsElement>()

    override val key: CoroutineContext.Key<*> get() = Key

    private val endpoints = linkedSetOf<OpenSslTlsEndpoint>()

    override suspend fun open() {
        if (state == ElementState.CREATED) {
            super.open()
            state = ElementState.ACTIVE
        }
    }

    fun clientEndpoint(hostname: String, port: Int = 443): TlsEndpoint =
        OpenSslTlsEndpoint(
            owner = this,
            role = TlsRole.CLIENT,
            remoteHost = hostname,
            remotePort = port,
            config = config,
            processOperations = processOperations,
        ).also { endpoints += it }

    fun serverEndpoint(bindHost: String = "0.0.0.0", port: Int = 443): TlsEndpoint =
        UnsupportedServerTlsEndpoint(bindHost, port)

    internal fun release(endpoint: TlsEndpoint) {
        if (endpoint is OpenSslTlsEndpoint) {
            endpoints -= endpoint
        }
    }

    override suspend fun drain() {
        endpoints.toList().forEach { it.close() }
        super.drain()
    }

    override suspend fun close() {
        endpoints.toList().forEach { it.close() }
        endpoints.clear()
        if (ownedSupervisor != null && ownedSupervisor.state.isLessThan(ElementState.CLOSED)) {
            ownedSupervisor.close()
        }
        super.close()
    }
}

suspend fun openTlsElement(
    config: TlsConfig = TlsConfig(),
    processOperations: ProcessOperations,
    parentJob: kotlinx.coroutines.Job? = null,
    subscribers: List<AsyncContextElement> = emptyList(),
): TlsElement =
    TlsElement(
        config = config,
        processOperations = processOperations,
        parentJob = parentJob,
        fanoutSubscribers = subscribers,
    ).also { it.open() }

suspend fun openTlsElement(
    config: TlsConfig = TlsConfig(),
    nioSupervisor: NioSupervisor? = null,
    parentJob: kotlinx.coroutines.Job? = null,
    subscribers: List<AsyncContextElement> = emptyList(),
): TlsElement {
    val contextSupervisor = currentCoroutineContext()[NioSupervisor.Key]
    val activeSupervisor = nioSupervisor ?: contextSupervisor ?: NioSupervisor()
    val ownsSupervisor = nioSupervisor == null && contextSupervisor == null

    if (activeSupervisor.state == ElementState.CREATED) {
        activeSupervisor.open()
    }

    val processOperations = activeSupervisor.service<ProcessOperations>()
        ?: error("TlsElement requires ProcessOperations in NioSupervisor. Open the supervisor before installing TLS.")

    return TlsElement(
        config = config,
        processOperations = processOperations,
        parentJob = parentJob,
        ownedSupervisor = activeSupervisor.takeIf { ownsSupervisor },
        fanoutSubscribers = subscribers,
    ).also { it.open() }
}

private class OpenSslTlsEndpoint(
    private val owner: TlsElement,
    override val role: TlsRole,
    override val remoteHost: String,
    override val remotePort: Int,
    private val config: TlsConfig,
    private val processOperations: ProcessOperations,
) : TlsEndpoint {
    private var closed = false
    private var requestBuffer = byteArrayOf()
    private var responseBuffer = byteArrayOf()
    private var responseOffset = 0
    private var exchangePerformed = false

    override var lastSession: TlsSession? = null
        private set

    override val isHandshakeComplete: Boolean
        get() = lastSession != null

    override val peerCertificate: TlsPeerCertificate?
        get() = lastSession?.peerCertificate

    override val protocol: String?
        get() = lastSession?.protocol

    override val cipherSuite: String?
        get() = lastSession?.cipherSuite

    override suspend fun handshake() {
        ensureOpen()
        if (lastSession != null) {
            return
        }
        if (requestBuffer.isEmpty()) {
            lastSession = executeOpenSsl(payload = byteArrayOf(), quiet = false)
        } else {
            ensureExchange()
        }
    }

    override suspend fun read(destination: ByteBuffer): Int {
        ensureOpen()
        ensureExchange()
        if (responseOffset >= responseBuffer.size) {
            return -1
        }
        val count = minOf(destination.remaining(), responseBuffer.size - responseOffset)
        destination.put(responseBuffer, responseOffset, count)
        responseOffset += count
        return count
    }

    override suspend fun write(source: ByteBuffer): Int {
        ensureOpen()
        check(!exchangePerformed) { "OpenSSL exec fallback is one-shot per endpoint. Create a fresh endpoint for a new exchange." }
        val bytes = ByteArray(source.remaining())
        source.get(bytes)
        requestBuffer = requestBuffer + bytes
        return bytes.size
    }

    override suspend fun close() {
        if (closed) {
            return
        }
        closed = true
        requestBuffer = byteArrayOf()
        responseBuffer = byteArrayOf()
        responseOffset = 0
        owner.release(this)
    }

    private fun ensureOpen() {
        check(!closed) { "TLS endpoint is closed" }
    }

    private suspend fun ensureExchange() {
        if (exchangePerformed) {
            return
        }
        val session = executeOpenSsl(payload = requestBuffer, quiet = true)
        lastSession = session
        responseBuffer = session.stdout
        responseOffset = 0
        exchangePerformed = true
    }

    private suspend fun executeOpenSsl(payload: ByteArray, quiet: Boolean): TlsSession {
        val result = processOperations.exec(
            command = config.opensslCommand,
            args = buildClientArgs(quiet = quiet),
            stdin = payload,
        )
        val session = result.toTlsSession()
        check(session.verified || !config.hostnameVerification) {
            "OpenSSL TLS verification failed for $remoteHost:$remotePort"
        }
        check(result.exitCode == 0) {
            val detailBytes = if (result.stderr.isEmpty()) result.stdout else result.stderr
            val detail = decodeText(detailBytes)
            "OpenSSL exited with ${result.exitCode} for $remoteHost:$remotePort: $detail"
        }
        return session
    }

    private fun buildClientArgs(quiet: Boolean): List<String> {
        val args = mutableListOf(
            "s_client",
            "-connect", "$remoteHost:$remotePort",
            "-servername", remoteHost,
        )

        if (quiet) {
            args += "-quiet"
        }

        when (config.protocols.firstOrNull()) {
            TlsProtocol.TLS13 -> args += "-tls1_3"
            TlsProtocol.TLS12 -> args += "-tls1_2"
            null -> {}
        }

        val tls13Suites = config.cipherSuites.filter { it.startsWith("TLS_") }
        if (tls13Suites.isNotEmpty()) {
            args += listOf("-ciphersuites", tls13Suites.joinToString(":"))
        }

        val legacySuites = config.cipherSuites.filterNot { it.startsWith("TLS_") }
        if (legacySuites.isNotEmpty()) {
            args += listOf("-cipher", legacySuites.joinToString(":"))
        }

        if (config.supportedGroups.isNotEmpty()) {
            args += listOf("-groups", config.supportedGroups.joinToString(":"))
        }

        if (config.alpnProtocols.isNotEmpty()) {
            args += listOf("-alpn", config.alpnProtocols.joinToString(","))
        }

        config.trustStore?.let { args += listOf("-CAfile", it) }
        config.certificateFile?.let { args += listOf("-cert", it) }
        config.privateKeyFile?.let { args += listOf("-key", it) }
        config.privateKeyPassword?.let { args += listOf("-pass", "pass:$it") }

        if (config.hostnameVerification) {
            args += listOf("-verify_hostname", remoteHost)
            args += "-verify_return_error"
        }

        return args
    }
}

private class UnsupportedServerTlsEndpoint(
    override val remoteHost: String,
    override val remotePort: Int,
) : TlsEndpoint {
    override val role: TlsRole = TlsRole.SERVER

    override suspend fun handshake() {
        error("OpenSSL exec fallback does not support server-side TLS endpoints.")
    }

    override suspend fun read(destination: ByteBuffer): Int =
        error("OpenSSL exec fallback does not support server-side TLS endpoints.")

    override suspend fun write(source: ByteBuffer): Int =
        error("OpenSSL exec fallback does not support server-side TLS endpoints.")

    override suspend fun close() {}

    override val isHandshakeComplete: Boolean = false
    override val peerCertificate: TlsPeerCertificate? = null
    override val protocol: String? = null
    override val cipherSuite: String? = null
    override val lastSession: TlsSession? = null
}

private fun ProcessResult.toTlsSession(): TlsSession {
    val merged = buildString {
        append(decodeText(stderr))
        if (stderr.isNotEmpty() && stdout.isNotEmpty()) {
            append('\n')
        }
        append(decodeText(stdout))
    }
    return TlsSession(
        protocol = merged.findField("Protocol"),
        cipherSuite = merged.findField("Cipher"),
        peerCertificate = TlsPeerCertificate(
            subject = merged.findPrefixValue("subject="),
            issuer = merged.findPrefixValue("issuer="),
        ),
        verified = merged.contains("Verify return code: 0", ignoreCase = true) ||
            merged.contains("Verification: OK", ignoreCase = true) ||
            exitCode == 0,
        stdout = stdout,
        stderr = stderr,
    )
}

private fun String.findField(name: String): String? =
    lineSequence()
        .map { it.trim() }
        .firstOrNull { it.startsWith("$name:", ignoreCase = true) }
        ?.substringAfter(':')
        ?.trim()
        ?.takeIf { it.isNotEmpty() }

private fun String.findPrefixValue(prefix: String): String? =
    lineSequence()
        .map { it.trim() }
        .firstOrNull { it.startsWith(prefix, ignoreCase = true) }
        ?.substringAfter(prefix)
        ?.trim()
        ?.takeIf { it.isNotEmpty() }

private fun decodeText(bytes: ByteArray): String =
    if (bytes.isEmpty()) "" else bytes.decodeToString()

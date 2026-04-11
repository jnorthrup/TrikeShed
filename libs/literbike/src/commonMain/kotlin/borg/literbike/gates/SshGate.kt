package borg.literbike.gates

/**
 * SSH Gate for LiteBike - Termux-native sshd integration
 *
 * Uses OpenSSH for pubkey authentication, then unlocks keys from keymux.
 * This provides a secure unlock mechanism where keys are only available
 * to authenticated SSH sessions.
 * Ported from literbike/src/gates/ssh_gate.rs
 */
import kotlin.time.Duration.Companion.seconds

/** SSH session state */
data class SshSession(
    val sessionId: String,
    val username: String,
    val pubkeyFingerprint: String,
    val unlockedKeys: MutableMap<String, String> = mutableMapOf(),
    val createdAt: Long = System.currentTimeMillis(),
    var lastActivity: Long = System.currentTimeMillis(),
)

/** SSH Gate configuration */
data class SshGateConfig(
    val port: UShort = 2222u,
    val keymuxUrl: String = "http://127.0.0.1:8888",
    val sessionTimeoutSecs: Long = 3600L,
    val maxSessionsPerKey: Int = 3,
)

/** SSH Gate - handles OpenSSH connections and key unlocking */
class SshGate(
    config: SshGateConfig = SshGateConfig(),
) {
    private var enabled: Boolean = true
    private var config: SshGateConfig = config
    private val sessions: MutableMap<String, SshSession> = mutableMapOf()
    private val registeredPubkeys: MutableMap<String, List<String>> = mutableMapOf()

    constructor() : this(SshGateConfig())

    fun enable() { enabled = true }
    fun disable() { enabled = false }

    /** Register a public key for unlocking */
    fun registerPubkey(pubkey: String, allowedProviders: List<String>) {
        val fingerprint = fingerprintPubkey(pubkey)
        registeredPubkeys[fingerprint] = allowedProviders
        println("Registered pubkey: $fingerprint")
    }

    /** Unregister a public key */
    fun unregisterPubkey(pubkey: String) {
        val fingerprint = fingerprintPubkey(pubkey)
        registeredPubkeys.remove(fingerprint)
        println("Unregistered pubkey: $fingerprint")
    }

    /** Generate SHA256 fingerprint from pubkey */
    private fun fingerprintPubkey(pubkey: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(pubkey.toByteArray(Charsets.UTF_8))
        val base64 = java.util.Base64.getEncoder().encodeToString(hash)
        return "SHA256:$base64"
    }

    /** Create a new SSH session after successful pubkey auth */
    fun createSession(username: String, pubkeyFingerprint: String): String? {
        val allowedProviders = registeredPubkeys[pubkeyFingerprint] ?: return null

        val activeCount = sessions.values.count { it.pubkeyFingerprint == pubkeyFingerprint }
        if (activeCount >= config.maxSessionsPerKey) {
            println("Max sessions reached for pubkey: $pubkeyFingerprint")
            return null
        }

        val sessionId = java.util.UUID.randomUUID().toString()
        val session = SshSession(
            sessionId = sessionId,
            username = username,
            pubkeyFingerprint = pubkeyFingerprint,
        )
        sessions[sessionId] = session
        println("Created SSH session: $sessionId for user: $username")
        return sessionId
    }

    /** End an SSH session */
    fun endSession(sessionId: String) {
        sessions.remove(sessionId)?.let {
            println("Ended SSH session: $sessionId")
        }
    }

    /** Clean up expired sessions */
    fun cleanupExpiredSessions() {
        val now = System.currentTimeMillis()
        val timeoutMs = config.sessionTimeoutSecs * 1000
        val expired = sessions.filterValues { (now - it.lastActivity) > timeoutMs }.keys.toList()
        for (id in expired) {
            sessions.remove(id)
            println("Cleaned up expired session: $id")
        }
    }

    /** Get session by ID */
    fun getSession(sessionId: String): SshSession? = sessions[sessionId]

    /** List all active sessions */
    fun listSessions(): List<SshSession> = sessions.values.toList()

    /** Detect SSH protocol */
    fun detectSsh(data: ByteArray): Boolean {
        if (data.size < 4) return false
        val prefix = data.take(8).toByteArray()
        return prefix.startsWith("SSH-2.0-".toByteArray()) ||
                prefix.startsWith("SSH-1.99-".toByteArray())
    }

    suspend fun isOpen(data: ByteArray): Boolean = enabled && detectSsh(data)

    suspend fun processData(data: ByteArray): Result<ByteArray> {
        if (!isOpen(data)) {
            return Result.failure(Exception("SSH gate is closed"))
        }
        return Result.success("SSH-2.0-LiteBike_1.0\r\n".toByteArray(Charsets.UTF_8))
    }

    val gateName: String get() = "ssh"
    val gateChildren: List<Gate> get() = emptyList()
    val priority: UByte get() = 95u

    fun canHandleProtocol(protocol: String): Boolean = protocol in listOf("ssh", "ssh2")
}

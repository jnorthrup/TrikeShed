@file:Suppress("UNCHECKED_CAST")

package borg.trikeshed.userspace.nio.tls

import borg.trikeshed.collections.s_
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.view
import borg.trikeshed.lib.α
import kotlin.random.Random

/**
 * Complete TLS fingerprint configuration.
 * Mirrors literbike/src/tls_fingerprint.rs:TlsFingerprint.
 */
data class TlsFingerprint(
    val version: borg.trikeshed.userspace.nio.tls.TlsVersionKind,
    val cipherSuites: Series<Int>,
    val extensions: Series<Int>,
    val ellipticCurves: Series<Int>,
    val signatureAlgorithms: Series<Int>,
    val alpnProtocols: Series<CharSequence>,
    val compressCertificate: Boolean,
    val earlyData: Boolean,
    val sessionTicket: Boolean,
)

/**
 * Mobile browser profiles for TLS fingerprint obfuscation.
 * Market-share-weighted profile selection mirrors LiterBike's Knox bypass.
 *
 * Mirrors literbike/src/tls_fingerprint.rs:MobileBrowserProfile.
 */
enum class BrowserProfile(val label: CharSequence, val marketSharePercent: Int) {
    CHROME_120("Chrome Mobile 120", 65),
    SAFARI_17("Safari 17", 25),
    SAMSUNG_21("Samsung Browser 21", 5),
    EDGE_120("Edge Mobile 120", 3),
    FIREFOX_121("Firefox Mobile 121", 2);

    /**
     * TLS fingerprint for this browser.
     * Mirrors literbike/src/tls_fingerprint.rs:get_tls_fingerprint().
     */
    val fingerprint: borg.trikeshed.userspace.nio.tls.TlsFingerprint
        get() = when (this) {
        SAFARI_17 -> _root_ide_package_.borg.trikeshed.userspace.nio.tls.TlsFingerprint(
            version = _root_ide_package_.borg.trikeshed.userspace.nio.tls.TlsVersionKind.V1_3,
            cipherSuites = s_[0x1301, 0x1302, 0x1303, 0xC02F, 0xC030, 0xCCA9, 0xC02B, 0xC02C, ],
            extensions = s_[0x0000, 0x000B, 0x000A, 0x0023, 0x0010, 0x0005, 0x0033, 0x002B, 0x0029, ],
            ellipticCurves = s_[0x001D, 0x0017, 0x0018, ],
            signatureAlgorithms = s_[0x0403, 0x0503, 0x0603, 0x0804, 0x0805, 0x0806, ],
            alpnProtocols = s_["h2", "http/1.1"],
            compressCertificate = true,
            earlyData = false,
            sessionTicket = true,
        )
        CHROME_120 -> _root_ide_package_.borg.trikeshed.userspace.nio.tls.TlsFingerprint(
            version = _root_ide_package_.borg.trikeshed.userspace.nio.tls.TlsVersionKind.V1_3,
            cipherSuites = s_[0x1301, 0x1302, 0x1303, 0xC02F, 0xC030, 0xCCA9, 0xC02B, 0xC02C, 0xCCA8],
            extensions = s_[0x0000, 0x000B, 0x000A, 0x0023, 0x0010, 0x0005, 0x0012, 0x0033, 0x002B, 0x002A, 0x001B, 0x0029],
            ellipticCurves = s_[0x001D, 0x0017, 0x0018, 0x0019],
            signatureAlgorithms = s_[0x0403, 0x0804, 0x0401, 0x0503, 0x0805, 0x0501],
            alpnProtocols = s_["h2", "http/1.1"],
            compressCertificate = true,
            earlyData = true,
            sessionTicket = true,
        )
        FIREFOX_121 -> _root_ide_package_.borg.trikeshed.userspace.nio.tls.TlsFingerprint(
            version = _root_ide_package_.borg.trikeshed.userspace.nio.tls.TlsVersionKind.V1_3,
            cipherSuites = s_[0x1301, 0x1302, 0x1303, 0xC02B, 0xC02F, 0xC02C, 0xC030, 0xCCA9, 0xCCA8],
            extensions = s_[0x0000, 0x000B, 0x000A, 0x0023, 0x0010, 0x0033, 0x002B, 0x0029],
            ellipticCurves = s_[0x001D, 0x0017, 0x0018],
            signatureAlgorithms = s_[0x0403, 0x0503, 0x0603, 0x0804, 0x0805, 0x0806],
            alpnProtocols = s_["h2", "http/1.1"],
            compressCertificate = false,
            earlyData = false,
            sessionTicket = true,
        )
        SAMSUNG_21 -> _root_ide_package_.borg.trikeshed.userspace.nio.tls.TlsFingerprint(
            version = _root_ide_package_.borg.trikeshed.userspace.nio.tls.TlsVersionKind.V1_3,
            cipherSuites = s_[0x1301, 0x1302, 0x1303, 0xC02F, 0xC030, 0xC02B, 0xC02C],
            extensions = s_[0x0000, 0x000B, 0x000A, 0x0023, 0x0010, 0x0033, 0x002B, 0x0029],
            ellipticCurves = s_[0x001D, 0x0017, 0x0018],
            signatureAlgorithms = s_[0x0403, 0x0503, 0x0804, 0x0805],
            alpnProtocols = s_["h2", "http/1.1"],
            compressCertificate = false,
            earlyData = false,
            sessionTicket = true,
        )
        EDGE_120 -> _root_ide_package_.borg.trikeshed.userspace.nio.tls.TlsFingerprint(
            version = _root_ide_package_.borg.trikeshed.userspace.nio.tls.TlsVersionKind.V1_3,
            cipherSuites = s_[0x1301, 0x1302, 0x1303, 0xC02F, 0xC030, 0xCCA9, 0xC02B, 0xC02C, 0xCCA8],
            extensions = s_[0x0000, 0x000B, 0x000A, 0x0023, 0x0010, 0x0005, 0x0033, 0x002B, 0x002A, 0x0029],
            ellipticCurves = s_[0x001D, 0x0017, 0x0018, 0x0019],
            signatureAlgorithms = s_[0x0403, 0x0804, 0x0401, 0x0503, 0x0805, 0x0501],
            alpnProtocols = s_["h2", "http/1.1"],
            compressCertificate = true,
            earlyData = true,
            sessionTicket = true,
        )
    }

    companion object {
        /**
         * Weighted random selection based on mobile browser market share.
         * Mirrors literbike/src/tls_fingerprint.rs:select_weighted_profile().
         */
        fun random(rng: Random = Random): BrowserProfile {
            val totalWeight = entries.sumOf { it.marketSharePercent }
            var choice = rng.nextInt(totalWeight)
            for (profile in entries) {
                if (choice < profile.marketSharePercent) return profile
                choice -= profile.marketSharePercent
            }
            return CHROME_120
        }
    }
}

/**
 * TLS fingerprint manager — profile selection, rotation, JA3 generation.
 * All protocol logic in commonMain. Only raw crypto stays behind expect/actual.
 *
 * Mirrors literbike/src/tls_fingerprint.rs:TlsFingerprintManager.
 */
class TlsFingerprintManager(
    initialProfile: borg.trikeshed.userspace.nio.tls.BrowserProfile = _root_ide_package_.borg.trikeshed.userspace.nio.tls.BrowserProfile.random(),
    /** Rotate fingerprint every N seconds (0 = disabled). */
    val rotationIntervalSec: Int = 1200,  // 20 minutes default
) {
    var currentProfile: borg.trikeshed.userspace.nio.tls.BrowserProfile = initialProfile
        private set

    private val profileHistory = mutableListOf<Pair<Long, borg.trikeshed.userspace.nio.tls.BrowserProfile>>()
    private val ja3Cache = mutableMapOf<CharSequence, CharSequence>()

    private var lastRotationMs: Long = 0L

    /**
     * Rotate profile if enough time has elapsed.
     * @param nowMs current time in milliseconds (e.g. System.currentTimeMillis or equivalent)
     */
    fun maybeRotate(nowMs: Long) {
        if (rotationIntervalSec <= 0) return
        if (profileHistory.isEmpty()) {
            profileHistory.add(nowMs to currentProfile)
            lastRotationMs = nowMs
            return
        }
        val elapsed = nowMs - lastRotationMs
        val thresholdMs = (rotationIntervalSec * 1000L) + Random.nextInt(0, 900_000) // add ±15min jitter
        if (elapsed >= thresholdMs) {
            val newProfile = _root_ide_package_.borg.trikeshed.userspace.nio.tls.BrowserProfile.random()
            profileHistory.add(nowMs to newProfile)
            if (profileHistory.size > 5) profileHistory.removeAt(0)
            currentProfile = newProfile
            lastRotationMs = nowMs
            ja3Cache.clear()
        }
    }

    /**
     * Force immediate profile rotation.
     */
    fun forceRotate(nowMs: Long) {
        val newProfile = _root_ide_package_.borg.trikeshed.userspace.nio.tls.BrowserProfile.random()
        profileHistory.add(nowMs to newProfile)
        if (profileHistory.size > 5) profileHistory.removeAt(0)
        currentProfile = newProfile
        lastRotationMs = nowMs
        ja3Cache.clear()
    }

    /**
     * Generate JA3 fingerprint hash for current profile + server name.
     *
     * JA3 format: TLSVersion,Ciphers,Extensions,EllipticCurves,ECPointFormats
     * Hash: DJB2 (multiply-by-31) over the concatenated string.
     *
     * Mirrors literbike/src/tls_fingerprint.rs:generate_ja3_fingerprint().
     */
    fun generateJa3(serverName: CharSequence): CharSequence {
        ja3Cache[serverName]?.let { return it }

        val fp = currentProfile.fingerprint
        val version = when (fp.version) { _root_ide_package_.borg.trikeshed.userspace.nio.tls.TlsVersionKind.V1_2 -> "771"; _root_ide_package_.borg.trikeshed.userspace.nio.tls.TlsVersionKind.V1_3 -> "772" }
        val ciphers = fp.cipherSuites.view.joinToString("-") { it.toInt().and(0xFFFF).toString() }
        val extensions = fp.extensions.view.joinToString("-") { it.toInt().and(0xFFFF).toString() }
        val curves = fp.ellipticCurves.view.joinToString("-") { it.toInt().and(0xFFFF).toString() }
        val ecPointFormats = "0" // uncompressed only

        val ja3String = "$version,$ciphers,$extensions,$curves,$ecPointFormats"
        val hash = djb2(ja3String)

        ja3Cache[serverName] = hash
        return hash
    }

    /**
     * Build a TLS Settings object from the current fingerprint.
     */
    fun toTlsSettings(serverName: CharSequence? = null): borg.trikeshed.userspace.nio.tls.TlsSettings {
        val fp = currentProfile.fingerprint
        return _root_ide_package_.borg.trikeshed.userspace.nio.tls.TlsSettings(
            protocolVersion = when (fp.version) {
                _root_ide_package_.borg.trikeshed.userspace.nio.tls.TlsVersionKind.V1_2 -> _root_ide_package_.borg.trikeshed.userspace.nio.tls.TlsProtocolVersion.V1_2; _root_ide_package_.borg.trikeshed.userspace.nio.tls.TlsVersionKind.V1_3 -> _root_ide_package_.borg.trikeshed.userspace.nio.tls.TlsProtocolVersion.V1_3
            },
            cipherSuites = (fp.cipherSuites α { suiteName(it) }) as Series<CharSequence>,
            serverName = serverName,
            pinnedCertificates = emptyList(),
        )
    }

    // ── Internal ──────────────────────────────────────────────────────

    private fun djb2(s: CharSequence): CharSequence {
        var hash = 5381L
        for (c in s) hash = (hash * 33) + c.code.toLong()
        return hash.toString(16)
    }

    private fun suiteName(id: Int): CharSequence = when (id.and(0xFFFF)) {
        0x1301 -> "TLS_AES_128_GCM_SHA256"
        0x1302 -> "TLS_AES_256_GCM_SHA384"
        0x1303 -> "TLS_CHACHA20_POLY1305_SHA256"
        else -> "TLS_${id.toString(16)}"
    }
}

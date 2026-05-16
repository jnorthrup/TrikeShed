package borg.trikeshed.userspace.nio.tls.codec

import borg.trikeshed.userspace.nio.file.Files
import borg.trikeshed.lib.SeriesBuffer
import borg.trikeshed.lib.size
import borg.trikeshed.lib.view

/**
 * CommonMain CA certificate bundle discovery — mirrors curl's CA path probing
 * order across platforms. Uses the pure userspace NIO file facade; zero
 * target-specific code.
 *
 * curl --version prints "SSL: Schannel / SecureTransport / OpenSSL..."
 * and internally probes these exact paths. Return the first existing bundle.
 *
 * If none exist, caller should fall back to curl interrogation or
 * OS-specific store (JVM trust store, Keychain, etc.).
 */
object CaBundleLocator {

    /** Canonical system CA bundle paths curl probes, in discovery order
     * (Debian/Ubuntu, RHEL/Fedora, macOS homebrew, Alpine, FreeBSD, etc). */
    private val KNOWN_BUNDLES = arrayOf(
        "/etc/ssl/certs/ca-certificates.crt",         // Debian/Ubuntu/Gentoo
        "/etc/pki/tls/certs/ca-bundle.crt",           // RHEL 6/7
        "/etc/ssl/ca-bundle.pem",                     // OpenSUSE
        "/etc/pki/tls/cacert.pem",                    // OpenELEC
        "/etc/pki/ca-trust/extracted/pem/tls-ca-bundle.pem", // RHEL 8+
        "/usr/local/share/certs/ca-root-nss.crt",     // FreeBSD
        "/etc/ssl/cert.pem",                          // macOS, Alpine, OpenBSD
        "/usr/local/openssl/cert.pem",                // macOS Homebrew OpenSSL
        "/usr/share/ssl/certs/ca-bundle.crt",         // CentOS
        "/system/etc/security/cacerts.pem",           // Android (non-root)
        "/data/local/user/0/cacerts/cert-*.pem",      // Android (user-added)
    )

    /**
     * Locate the system CA bundle file. Returns the path of the first
     * existing file from the canonical list, or null if none are found.
     *
     * On success, read the file via
     * `Files.readAllBytes(result)` to get PEM bytes.
     */
    fun locate(): CharSequence? {
        for (path in KNOWN_BUNDLES) {
            if (Files.exists(path)) {
                return path
            }
        }
        return null
    }

    /**
     * Read the CA bundle bytes from the located path. Returns null when
     * no bundle is found on this system.
     */
    fun load(): ByteArray? {
        val path = locate() ?: return null
        return Files.readAllBytes(path)
    }

    /**
     * When no bundle is found, produce a diagnostic message listing
     * curl's expected paths and whether any exist on this system.
     */
    fun diagnostic(): CharSequence {
        val found = SeriesBuffer<CharSequence>()
        for (path in KNOWN_BUNDLES) {
            if (Files.exists(path)) found += path
        }
        return buildString {
            append("CA bundle discovery: ")
            if (found.size == 0) {
                append("NONE found.")
                append(" Probe these curl-default paths:")
                for (p in KNOWN_BUNDLES) {
                    append("\n  - ").append(p)
                }
                append("\nIf you have curl installed, run: curl-config --ca")
                append(" or curl --version for backend info.")
            } else {
                append("found ").append(found.size).append(" bundle(s):")
                for (f in found.view) {
                    append("\n  ").append(f)
                }
            }
        }
    }
}

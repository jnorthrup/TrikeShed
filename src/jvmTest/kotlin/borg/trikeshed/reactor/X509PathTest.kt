package borg.trikeshed.reactor

import borg.trikeshed.lib.*
import kotlin.test.*

/**
 * webpki path validation over [X509Test]'s chains: the public chains to [TLS_SERVER_ROOTS] and the keytool PKI's
 * signature algorithms and built-in failures. Plain Kotlin, kept off Kotlin/JS (ECDSA there outruns mocha's 2 s);
 * natively it runs under kotlinc-native -tr.
 */
class X509PathTest {
    val fixtures = X509Test()
    val captured = fixtures.captured
    val publicChains = fixtures.publicChains
    val jdkRoots = fixtures.jdkRoots

    fun jdkChain(alias: String) = fixtures.jdkChain(alias)

    fun rejects(error: CertificateError, block: () -> Unit) = assertEquals(error, assertFailsWith<CertificateException> { block() }.error)

    @Test fun publicChainsVerifyToTheWebpkiRoots() {
        for ((host, chain) in publicChains) assertContentEquals(chain[0].let { X509Certificate(it).subject }, verifyServerCertificate(chain, TLS_SERVER_ROOTS, host, captured).subject)
        // a wildcard dNSName: runpod.ai's leaf names *.runpod.ai
        verifyServerCertificate(publicChains.getValue("api.runpod.ai"), TLS_SERVER_ROOTS, "rest.runpod.ai", captured)
        assertEquals(121, TLS_SERVER_ROOTS.size)
    }

    @Test fun publicChainFailures() {
        val chain = publicChains.getValue("storage.googleapis.com")
        val leaf = X509Certificate(chain[0])
        // validity as Python's cryptography reads it: 2026-09-18T20:04:36Z to 2026-12-11T20:04:35Z
        assertEquals(1789761876L, leaf.notBefore)
        assertEquals(1797019475L, leaf.notAfter)
        verifyServerCertificate(chain, TLS_SERVER_ROOTS, "storage.googleapis.com", leaf.notAfter)
        rejects(CertificateError.CertExpired) { verifyServerCertificate(chain, TLS_SERVER_ROOTS, "storage.googleapis.com", leaf.notAfter + 1) }
        rejects(CertificateError.CertNotValidYet) { verifyServerCertificate(chain, TLS_SERVER_ROOTS, "storage.googleapis.com", leaf.notBefore - 1) }
        rejects(CertificateError.CertNotValidForName) { verifyServerCertificate(chain, TLS_SERVER_ROOTS, "api.cloudflare.com", captured) }
        rejects(CertificateError.CertNotValidForName) { verifyServerCertificate(chain, TLS_SERVER_ROOTS, "googleapis.com", captured) }
        rejects(CertificateError.CertNotValidForName) { verifyServerCertificate(publicChains.getValue("api.runpod.ai"), TLS_SERVER_ROOTS, "a.b.runpod.ai.example", captured) }
        // without GTS Root R4 the path runs through its GlobalSign Root CA cross-certificate, whose issuer webpki-roots no longer carries
        val gtsRootR4 = "310b300906035504061302555331223020060355040a1319476f6f676c65205472757374205365727669636573204c4c43311430120603550403130b47545320526f6f74205234".hexToByteArray()
        val withoutR4 = TLS_SERVER_ROOTS.view.filter { !it.subject.contentEquals(gtsRootR4) }
        assertEquals(120, withoutR4.size)
        rejects(CertificateError.UnknownIssuer) { verifyServerCertificate(chain, withoutR4, "storage.googleapis.com", captured) }
        rejects(CertificateError.UnknownIssuer) { verifyServerCertificate(chain, jdkRoots, "storage.googleapis.com", captured) }
        val tampered = chain[0].copyOf().also { it[it.size - 5] = (it[it.size - 5].toInt() xor 1).toByte() }
        rejects(CertificateError.InvalidSignatureForPublicKey) { verifyServerCertificate(listOf(tampered) + chain.drop(1), TLS_SERVER_ROOTS, "storage.googleapis.com", captured) }
    }

    @Test fun jdkSignatureAlgorithms() {
        for (alias in listOf("leaf-p256", "leaf-p384", "leaf-rsa", "leaf-rsa-pkcs1-sha256", "leaf-rsa-pkcs1-sha512", "leaf-rsa-pss-sha384", "leaf-ed25519"))
            for (name in listOf("localhost", "LOCALHOST", "127.0.0.1", "any.trikeshed.test", null))
                verifyServerCertificate(jdkChain(alias), jdkRoots, name, captured)
    }

    @Test fun jdkFailures() {
        val leaf = jdkChain("leaf-p256")
        rejects(CertificateError.CertExpired) { verifyServerCertificate(jdkChain("leaf-expired"), jdkRoots, "localhost", captured) }
        for (name in listOf("wrong.example", "trikeshed.test", "a.b.trikeshed.test", "127.0.0.2", "::1", "localhost.example"))
            rejects(CertificateError.CertNotValidForName) { verifyServerCertificate(leaf, jdkRoots, name, captured) }
        rejects(CertificateError.UnknownIssuer) { verifyServerCertificate(leaf, TLS_SERVER_ROOTS, "localhost", captured) }
        rejects(CertificateError.UnknownIssuer) { verifyServerCertificate(leaf.take(1), jdkRoots, "localhost", captured) }
        rejects(CertificateError.EndEntityUsedAsCa) { verifyServerCertificate(jdkChain("leaf-under-ee"), jdkRoots, "localhost", captured) }
        rejects(CertificateError.KeyCertSignNotSet) { verifyServerCertificate(jdkChain("leaf-under-no-certsign"), jdkRoots, "localhost", captured) }
        rejects(CertificateError.RequiredEkuNotFound) { verifyServerCertificate(jdkChain("leaf-client-eku"), jdkRoots, "localhost", captured) }
        rejects(CertificateError.DigitalSignatureNotSet) { verifyServerCertificate(jdkChain("leaf-rsa-encipher"), jdkRoots, "localhost", captured) }
        rejects(CertificateError.CaUsedAsEndEntity) { verifyServerCertificate(jdkChain("leaf-p256").drop(1), jdkRoots, null, captured) }
    }

}

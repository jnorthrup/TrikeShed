package borg.trikeshed.reactor

import borg.trikeshed.lib.*
import java.io.ByteArrayInputStream
import java.security.KeyFactory
import java.security.cert.*
import java.security.spec.X509EncodedKeySpec
import java.util.Date
import javax.security.auth.x500.X500Principal
import kotlin.test.*

/** The JDK's PKIX CertPathBuilder (revocation off) as the path-building oracle over [X509Test]'s chains, and X500Principal's RFC 2253 names for [rfc4514]. */
class X509JvmTest {
    val fixtures = X509Test()
    val factory: CertificateFactory = CertificateFactory.getInstance("X.509")

    fun jdk(der: ByteArray) = factory.generateCertificate(ByteArrayInputStream(der)) as java.security.cert.X509Certificate

    /** A SEQUENCE around [contents], the outer element webpki strips from names and keys. */
    fun sequence(contents: ByteArray): ByteArray {
        val n = contents.size
        val length = when {
            n < 0x80 -> byteArrayOf(n.toByte())
            n < 0x100 -> byteArrayOf(0x81.toByte(), n.toByte())
            else -> byteArrayOf(0x82.toByte(), (n shr 8).toByte(), n.toByte())
        }
        return byteArrayOf(0x30) + length + contents
    }

    /** The JDK PKIX refuses anchors with name constraints (one webpki root has them; no case here paths through it). */
    fun jdkAnchor(anchor: TrustAnchor): java.security.cert.TrustAnchor {
        val spki = X509EncodedKeySpec(sequence(anchor.subjectPublicKeyInfo))
        val key = listOf("EC", "RSA", "Ed25519").firstNotNullOf { runCatching { KeyFactory.getInstance(it).generatePublic(spki) }.getOrNull() }
        return java.security.cert.TrustAnchor(X500Principal(sequence(anchor.subject)), key, null)
    }

    fun jdkBuilds(chain: List<ByteArray>, anchors: Series<TrustAnchor>, time: Long): Boolean {
        val certs = chain.map(::jdk)
        val parameters = PKIXBuilderParameters(anchors.view.map(::jdkAnchor).toSet(), X509CertSelector().apply { certificate = certs[0] })
        parameters.isRevocationEnabled = false
        parameters.date = Date(time * 1000)
        parameters.addCertStore(CertStore.getInstance("Collection", CollectionCertStoreParameters(certs)))
        return runCatching { CertPathBuilder.getInstance("PKIX").build(parameters) }.isSuccess
    }

    fun ours(chain: List<ByteArray>, anchors: Series<TrustAnchor>, time: Long): Boolean =
        runCatching { verifyServerCertificate(chain, anchors, null, time) }.isSuccess

    /** Path verdicts the JDK and webpki share; EKU and end-entity key usage are TLS rules PKIX leaves to its caller. */
    @Test fun pathVerdictsAgreeWithPkix() {
        val captured = fixtures.captured
        val storage = fixtures.publicChains.getValue("storage.googleapis.com")
        val gtsRootR4 = jdk(storage[1]).issuerX500Principal.encoded.copyOfRange(2, jdk(storage[1]).issuerX500Principal.encoded.size)
        val withoutR4 = TLS_SERVER_ROOTS.view.filter { !it.subject.contentEquals(gtsRootR4) }
        val cases = fixtures.publicChains.values.map { Triple(it, TLS_SERVER_ROOTS, captured) } + listOf(
            Triple(storage, TLS_SERVER_ROOTS, X509Certificate(storage[0]).notAfter + 1),
            Triple(storage, withoutR4, captured),
            Triple(fixtures.jdkChain("leaf-p256"), TLS_SERVER_ROOTS, captured),
        ) + listOf(
            "leaf-p256", "leaf-p384", "leaf-rsa", "leaf-rsa-pkcs1-sha256", "leaf-rsa-pkcs1-sha512", "leaf-rsa-pss-sha384", "leaf-ed25519",
            "leaf-expired", "leaf-under-ee", "leaf-under-no-certsign",
        ).map { Triple(fixtures.jdkChain(it), fixtures.jdkRoots, captured) }
        var accepted = 0
        for ((chain, anchors, time) in cases) {
            val verdict = jdkBuilds(chain, anchors, time)
            assertEquals(verdict, ours(chain, anchors, time), "${jdk(chain[0]).subjectX500Principal} at $time")
            if (verdict) accepted++
        }
        assertEquals(10, accepted)
    }

    @Test fun rfc4514AgreesWithX500Principal() {
        val names = TLS_SERVER_ROOTS.view.map { it.subject } +
            (fixtures.publicChains.values.flatten() + fixtures.certificates.values).flatMap { X509Certificate(it).let { c -> listOf(c.subject, c.issuer) } }
        for (name in names) assertEquals(X500Principal(sequence(name)).name, rfc4514(name))
    }
}

@file:OptIn(ExperimentalEncodingApi::class)

package borg.trikeshed.reactor

import borg.trikeshed.ipns.*
import borg.trikeshed.job.*
import borg.trikeshed.lib.*
import kotlin.io.encoding.*

/**
 * webpki 0.103's Error variants this verifier raises, under webpki's names and its specificity rank (Error::rank), plus
 * the two RFC key-usage rules webpki leaves out: RFC 5280 6.1.4 (n) keyCertSign on issuers and RFC 8446 4.4.2.2
 * digitalSignature on the end entity, ranked with the role errors.
 */
enum class CertificateError(val rank: Int) {
    CertNotValidYet(290), CertExpired(290), CertNotValidForName(280), InvalidSignatureForPublicKey(260),
    SignatureAlgorithmMismatch(250), EmptyEkuExtension(245), RequiredEkuNotFound(240), NameConstraintViolation(230),
    PathLenConstraintViolated(220), CaUsedAsEndEntity(210), EndEntityUsedAsCa(210), KeyCertSignNotSet(210),
    DigitalSignatureNotSet(210), InvalidCertValidity(190), InvalidNetworkMaskConstraint(180),
    UnsupportedSignatureAlgorithmForPublicKey(150), UnsupportedSignatureAlgorithm(140), UnsupportedCriticalExtension(130),
    UnsupportedCertVersion(130), MaximumPathDepthExceeded(61), MalformedDnsIdentifier(60), MalformedNameConstraint(50),
    ExtensionValueInvalid(30), BadDerTime(20), BadDer(10), MaximumSignatureChecksExceeded(0),
    MaximumPathBuildCallsExceeded(0), MaximumNameConstraintComparisonsExceeded(0), UnknownIssuer(0);

    /** webpki ControlFlow::Break: an exhausted budget ends the search. */
    val fatal: Boolean get() = this == MaximumSignatureChecksExceeded || this == MaximumPathBuildCallsExceeded || this == MaximumNameConstraintComparisonsExceeded

    /** webpki Error::most_specific: the higher rank, the earlier error on a tie. */
    fun mostSpecific(other: CertificateError): CertificateError = if (rank >= other.rank) this else other
}

class CertificateException(val error: CertificateError) : Exception(error.name)

/** RFC 5280 trust anchor as webpki keeps it: the Name and SubjectPublicKeyInfo contents (no outer tag and length) and the NameConstraints contents. */
class TrustAnchor(val subject: ByteArray, val subjectPublicKeyInfo: ByteArray, val nameConstraints: ByteArray? = null) {
    constructor(subject: String, subjectPublicKeyInfo: String, nameConstraints: String?) :
        this(subject.hexToByteArray(), subjectPublicKeyInfo.hexToByteArray(), nameConstraints?.hexToByteArray())

    companion object {
        /** webpki anchor_from_trusted_cert for a v3 certificate: its subject, key and name constraints, unknown critical extensions ignored. */
        fun of(der: ByteArray): TrustAnchor = X509Certificate(der, anchor = true).let { TrustAnchor(it.subject, it.subjectPublicKeyInfo, it.nameConstraints) }
    }
}

/** RFC 7468 textual encodings labelled CERTIFICATE in [text], decoded. */
fun pemCertificates(text: String): List<ByteArray> {
    val certificates = ArrayList<ByteArray>()
    var at = 0
    while (true) {
        val begin = text.indexOf("-----BEGIN CERTIFICATE-----", at).takeIf { it >= 0 } ?: return certificates
        val end = text.indexOf("-----END CERTIFICATE-----", begin)
        require(end >= 0) { "unterminated CERTIFICATE section" }
        certificates += Base64.Default.decode(text.substring(begin + 27, end).filterNot { it == ' ' || it == '\t' || it == '\r' || it == '\n' })
        at = end + 25
    }
}

/** The next element's tag, or -1 at the end. */
fun Der.peek(): Int = if (at < limit) bytes[at].toInt() and 0xFF else -1

/** BIT STRING contents after an unused-bits octet that must be zero. */
fun Der.bitString(): ByteArray {
    val value = next(0x03).value()
    if (value.isEmpty() || value[0] != 0.toByte()) throw CertificateException(CertificateError.BadDer)
    return value.copyOfRange(1, value.size)
}

/** A DER BOOLEAN, FALSE when absent (webpki accepts an explicit FALSE). */
fun Der.boolean(): Boolean {
    if (peek() != 0x01) return false
    return when (next(0x01).value().singleOrNull()?.toInt()) {
        -1 -> true
        0 -> false
        else -> throw CertificateException(CertificateError.BadDer)
    }
}

/** [block] over [Der] cursors, any structural fault BadDer as webpki's untrusted::read_all reports it. */
inline fun <T> readDer(block: () -> T): T = try {
    block()
} catch (e: IllegalArgumentException) {
    throw CertificateException(CertificateError.BadDer)
}

/**
 * RFC 5280 4.1 Certificate as webpki 0.103 cert.rs reads it: version 3 only; the TBS signature AlgorithmIdentifier equal
 * to the outer one; extensions under id-ce 15 (keyUsage), 17 (subjectAltName), 19 (basicConstraints), 30
 * (nameConstraints), 31 (cRLDistributionPoints) and 37 (extKeyUsage) kept once each, any other critical extension
 * refused unless the certificate is read as a trust anchor. Names and keys stay as their DER contents; keyUsage stays
 * as its BIT STRING element.
 */
class X509Certificate(der: ByteArray, anchor: Boolean = false) {
    /** The TBSCertificate element, the signed data. */
    val tbs: ByteArray
    /** AlgorithmIdentifier contents. */
    val signatureAlgorithm: ByteArray
    val signature: ByteArray
    val issuer: ByteArray
    val notBefore: Long
    val notAfter: Long
    val subject: ByteArray
    val subjectPublicKeyInfo: ByteArray
    var basicConstraints: ByteArray? = null
    var keyUsage: ByteArray? = null
    var subjectAltName: ByteArray? = null
    var nameConstraints: ByteArray? = null
    var crlDistributionPoints: ByteArray? = null
    var extendedKeyUsage: ByteArray? = null

    init {
        try {
            val outer = Der(der, 0, der.size)
            val certificate = outer.next(0x30)
            outer.end()
            val tbsStart = certificate.at
            val tbsDer = certificate.next(0x30)
            tbs = der.copyOfRange(tbsStart, certificate.at)
            signatureAlgorithm = certificate.next(0x30).value()
            signature = certificate.bitString()
            certificate.end()
            if (tbsDer.peek() != 0xA0) throw CertificateException(CertificateError.UnsupportedCertVersion)
            val version = tbsDer.next(0xA0)
            if (version.integer().let { it.size != 1 || it[0] != 2.toByte() }) throw CertificateException(CertificateError.UnsupportedCertVersion)
            version.end()
            tbsDer.next(0x02)
            if (!tbsDer.next(0x30).value().contentEquals(signatureAlgorithm)) throw CertificateException(CertificateError.SignatureAlgorithmMismatch)
            issuer = tbsDer.next(0x30).value()
            val validity = tbsDer.next(0x30)
            notBefore = time(validity)
            notAfter = time(validity)
            validity.end()
            subject = tbsDer.next(0x30).value()
            subjectPublicKeyInfo = tbsDer.next(0x30).value()
            if (tbsDer.peek() == 0xA3) {
                val extensions = tbsDer.next(0xA3).next(0x30)
                while (extensions.peek() >= 0) {
                    val extension = extensions.next(0x30)
                    val id = extension.next(0x06).value()
                    val critical = extension.boolean()
                    val value = extension.next(0x04).value()
                    extension.end()
                    // id-ce: 2.5.29.x
                    if (id.size == 3 && id[0] == 0x55.toByte() && id[1] == 0x1d.toByte()) {
                        val content = { if (id[2].toInt() == 15) value else Der(value, 0, value.size).let { d -> d.next(0x30).value().also { d.end() } } }
                        when (id[2].toInt()) {
                            15 -> keyUsage = once(keyUsage, content())
                            17 -> subjectAltName = once(subjectAltName, content())
                            19 -> basicConstraints = once(basicConstraints, content())
                            30 -> nameConstraints = once(nameConstraints, content())
                            31 -> crlDistributionPoints = once(crlDistributionPoints, content())
                            37 -> extendedKeyUsage = once(extendedKeyUsage, content())
                            else -> if (critical && !anchor) throw CertificateException(CertificateError.UnsupportedCriticalExtension)
                        }
                    } else if (critical && !anchor) throw CertificateException(CertificateError.UnsupportedCriticalExtension)
                }
            }
            tbsDer.end()
        } catch (e: IllegalArgumentException) {
            throw CertificateException(CertificateError.BadDer)
        }
    }

    fun once(present: ByteArray?, value: ByteArray): ByteArray = if (present != null) throw CertificateException(CertificateError.ExtensionValueInvalid) else value

    /** webpki's Time: UTCTime YYMMDDHHMMSSZ (years 50-99 are 19xx) or GeneralizedTime YYYYMMDDHHMMSSZ, as seconds since 1970. */
    fun time(validity: Der): Long {
        val utc = validity.peek() == 0x17
        val text = validity.next(if (utc) 0x17 else 0x18).value()
        var at = 0
        fun two(min: Int, max: Int): Int {
            if (at + 2 > text.size) throw CertificateException(CertificateError.BadDerTime)
            val hi = text[at].toInt() - '0'.code
            val lo = text[at + 1].toInt() - '0'.code
            if (hi !in 0..9 || lo !in 0..9) throw CertificateException(CertificateError.BadDerTime)
            at += 2
            return (10 * hi + lo).takeIf { it in min..max } ?: throw CertificateException(CertificateError.BadDerTime)
        }
        val year = if (utc) two(0, 99).let { if (it >= 50) 1900 + it else 2000 + it } else 100 * two(0, 99) + two(0, 99)
        if (year < 1970) throw CertificateException(CertificateError.BadDerTime)
        val month = two(1, 12)
        val leap = year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)
        val monthDays = intArrayOf(31, if (leap) 29 else 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31)
        val day = two(1, monthDays[month - 1])
        val hours = two(0, 23)
        val minutes = two(0, 59)
        val seconds = two(0, 59)
        if (at + 1 != text.size || text[at] != 'Z'.code.toByte()) throw CertificateException(CertificateError.BadDerTime)
        val y = year - 1L
        val days = y * 365 + y / 4 - y / 100 + y / 400 - (1969L * 365 + 477) + (0 until month - 1).sumOf { monthDays[it] } + day - 1
        return ((days * 24 + hours) * 60 + minutes) * 60 + seconds
    }

    /** RFC 5280 4.2.1.3 KeyUsage bit [bit] (digitalSignature 0, keyCertSign 5) when the extension is present, else null. */
    fun keyUsage(bit: Int): Boolean? = keyUsage?.let { element ->
        val bits = readDer { Der(element, 0, element.size).let { d -> d.next(0x03).value().also { d.end() } } }
        if (bits.isEmpty()) throw CertificateException(CertificateError.BadDer)
        1 + bit / 8 < bits.size && bits[1 + bit / 8].toInt() and (0x80 ushr (bit % 8)) != 0
    }
}

/** RFC 8017 A.1.1 RSAPublicKey with ring's limits: a 2048- to 8192-bit modulus and an odd public exponent from 3 to 2^33 - 1. */
fun rsaPublicKey(key: ByteArray): RSAPublicKey? = try {
    val d = Der(key, 0, key.size)
    val sequence = d.next(0x30)
    d.end()
    val n = sequence.integer()
    val e = sequence.integer()
    sequence.end()
    val bits = if (n.isEmpty()) 0 else 8 * (n.size - 1) + 32 - (n[0].toInt() and 0xFF).countLeadingZeroBits()
    val exponent = e.fold(0L) { acc, b -> if (acc > 0xFFFFFFFFL) acc else (acc shl 8) or (b.toLong() and 0xFF) }
    if (bits !in 2048..8192 || n.last().toInt() and 1 == 0 || exponent !in 3L..0x1FFFFFFFFL || exponent and 1L == 0L) null
    else RSAPublicKey(n, e)
} catch (e: IllegalArgumentException) {
    null
}

/** rustls-pki-types SignatureVerificationAlgorithm: the key's AlgorithmIdentifier, the signature's, and the verification over (key bits, message, signature). */
class SignatureVerificationAlgorithm(
    val publicKeyAlgorithm: ByteArray,
    val signatureAlgorithm: ByteArray,
    val verify: (ByteArray, ByteArray, ByteArray) -> Boolean,
) {
    /** The AlgorithmIdentifier contents webpki matches byte for byte (rustls-pki-types and rustls-webpki src/data/alg-*.der). */
    object AlgorithmIdentifier {
        val ECDSA_P256 = "06072a8648ce3d020106082a8648ce3d030107".hexToByteArray()
        val ECDSA_P384 = "06072a8648ce3d020106052b81040022".hexToByteArray()
        val ECDSA_SHA256 = "06082a8648ce3d040302".hexToByteArray()
        val ECDSA_SHA384 = "06082a8648ce3d040303".hexToByteArray()
        val ED25519 = "06032b6570".hexToByteArray()
        val RSA_ENCRYPTION = "06092a864886f70d0101010500".hexToByteArray()
        val RSA_PKCS1_SHA256 = "06092a864886f70d01010b0500".hexToByteArray()
        val RSA_PKCS1_SHA384 = "06092a864886f70d01010c0500".hexToByteArray()
        val RSA_PKCS1_SHA512 = "06092a864886f70d01010d0500".hexToByteArray()
        val RSA_PKCS1_SHA256_ABSENT_PARAMS = "06092a864886f70d01010b".hexToByteArray()
        val RSA_PKCS1_SHA384_ABSENT_PARAMS = "06092a864886f70d01010c".hexToByteArray()
        val RSA_PKCS1_SHA512_ABSENT_PARAMS = "06092a864886f70d01010d".hexToByteArray()
        val RSA_PSS_SHA256 = "06092a864886f70d01010a3034a00f300d06096086480165030402010500a11c301a06092a864886f70d010108300d06096086480165030402010500a203020120".hexToByteArray()
        val RSA_PSS_SHA384 = "06092a864886f70d01010a3034a00f300d06096086480165030402020500a11c301a06092a864886f70d010108300d06096086480165030402020500a203020130".hexToByteArray()
        val RSA_PSS_SHA512 = "06092a864886f70d01010a3034a00f300d06096086480165030402030500a11c301a06092a864886f70d010108300d06096086480165030402030500a203020140".hexToByteArray()
    }

    companion object {
        val SHA256: (ByteArray) -> ByteArray = { Sha256Pure.digest(it) }
        val SHA384: (ByteArray) -> ByteArray = { Sha384Pure.digest(it) }
        val SHA512: (ByteArray) -> ByteArray = { Sha512Pure.digest(it) }

        fun ecdsa(curve: WeierstrassCurve, key: ByteArray, hash: (ByteArray) -> ByteArray, signature: ByteArray) =
            SignatureVerificationAlgorithm(key, signature) { q, m, s -> curve.verify(q, hash(m), s) }

        fun pkcs1(signature: ByteArray, info: ByteArray, hash: (ByteArray) -> ByteArray) =
            SignatureVerificationAlgorithm(AlgorithmIdentifier.RSA_ENCRYPTION, signature) { n, m, s -> rsaPublicKey(n)?.let { RSASSA_PKCS1_v1_5.verify(it, info, hash(m), s) } ?: false }

        fun pss(signature: ByteArray, hash: (ByteArray) -> ByteArray) =
            SignatureVerificationAlgorithm(AlgorithmIdentifier.RSA_ENCRYPTION, signature) { n, m, s -> rsaPublicKey(n)?.let { RSASSA_PSS.verify(it, hash, m, s) } ?: false }

        val ECDSA_P256_SHA256 = ecdsa(WeierstrassCurve.P256, AlgorithmIdentifier.ECDSA_P256, SHA256, AlgorithmIdentifier.ECDSA_SHA256)
        val ECDSA_P256_SHA384 = ecdsa(WeierstrassCurve.P256, AlgorithmIdentifier.ECDSA_P256, SHA384, AlgorithmIdentifier.ECDSA_SHA384)
        val ECDSA_P384_SHA256 = ecdsa(WeierstrassCurve.P384, AlgorithmIdentifier.ECDSA_P384, SHA256, AlgorithmIdentifier.ECDSA_SHA256)
        val ECDSA_P384_SHA384 = ecdsa(WeierstrassCurve.P384, AlgorithmIdentifier.ECDSA_P384, SHA384, AlgorithmIdentifier.ECDSA_SHA384)
        val ED25519 = SignatureVerificationAlgorithm(AlgorithmIdentifier.ED25519, AlgorithmIdentifier.ED25519) { k, m, s -> Ed25519.verify(k, m, s) }
        val RSA_PSS_2048_8192_SHA256_LEGACY_KEY = pss(AlgorithmIdentifier.RSA_PSS_SHA256, SHA256)
        val RSA_PSS_2048_8192_SHA384_LEGACY_KEY = pss(AlgorithmIdentifier.RSA_PSS_SHA384, SHA384)
        val RSA_PSS_2048_8192_SHA512_LEGACY_KEY = pss(AlgorithmIdentifier.RSA_PSS_SHA512, SHA512)
        val RSA_PKCS1_2048_8192_SHA256 = pkcs1(AlgorithmIdentifier.RSA_PKCS1_SHA256, RSASSA_PKCS1_v1_5.SHA256, SHA256)
        val RSA_PKCS1_2048_8192_SHA384 = pkcs1(AlgorithmIdentifier.RSA_PKCS1_SHA384, RSASSA_PKCS1_v1_5.SHA384, SHA384)
        val RSA_PKCS1_2048_8192_SHA512 = pkcs1(AlgorithmIdentifier.RSA_PKCS1_SHA512, RSASSA_PKCS1_v1_5.SHA512, SHA512)

        /** rustls 0.23 crypto::ring SUPPORTED_SIG_ALGS.all, in order. */
        val SUPPORTED_SIG_ALGS = listOf(
            ECDSA_P256_SHA256, ECDSA_P256_SHA384, ECDSA_P384_SHA256, ECDSA_P384_SHA384, ED25519,
            RSA_PSS_2048_8192_SHA256_LEGACY_KEY, RSA_PSS_2048_8192_SHA384_LEGACY_KEY, RSA_PSS_2048_8192_SHA512_LEGACY_KEY,
            RSA_PKCS1_2048_8192_SHA256, RSA_PKCS1_2048_8192_SHA384, RSA_PKCS1_2048_8192_SHA512,
            pkcs1(AlgorithmIdentifier.RSA_PKCS1_SHA256_ABSENT_PARAMS, RSASSA_PKCS1_v1_5.SHA256, SHA256),
            pkcs1(AlgorithmIdentifier.RSA_PKCS1_SHA384_ABSENT_PARAMS, RSASSA_PKCS1_v1_5.SHA384, SHA384),
            pkcs1(AlgorithmIdentifier.RSA_PKCS1_SHA512_ABSENT_PARAMS, RSASSA_PKCS1_v1_5.SHA512, SHA512),
        )
    }
}

/** webpki signed_data::verify_signature: the key's algorithm must be [algorithm]'s, then the signature must verify. */
fun verifySignature(algorithm: SignatureVerificationAlgorithm, spki: ByteArray, message: ByteArray, signature: ByteArray) {
    val (keyAlgorithm, key) = readDer { Der(spki, 0, spki.size).let { d -> (d.next(0x30).value() to d.bitString()).also { d.end() } } }
    if (!algorithm.publicKeyAlgorithm.contentEquals(keyAlgorithm)) throw CertificateException(CertificateError.UnsupportedSignatureAlgorithmForPublicKey)
    if (!algorithm.verify(key, message, signature)) throw CertificateException(CertificateError.InvalidSignatureForPublicKey)
}

/** webpki signed_data::verify_signed_data over [SignatureVerificationAlgorithm.SUPPORTED_SIG_ALGS]. */
fun verifySignedData(spki: ByteArray, algorithm: ByteArray, message: ByteArray, signature: ByteArray) {
    var forPublicKey = false
    for (candidate in SignatureVerificationAlgorithm.SUPPORTED_SIG_ALGS) {
        if (!candidate.signatureAlgorithm.contentEquals(algorithm)) continue
        try {
            return verifySignature(candidate, spki, message, signature)
        } catch (e: CertificateException) {
            if (e.error != CertificateError.UnsupportedSignatureAlgorithmForPublicKey) throw e
            forPublicKey = true
        }
    }
    throw CertificateException(if (forPublicKey) CertificateError.UnsupportedSignatureAlgorithmForPublicKey else CertificateError.UnsupportedSignatureAlgorithm)
}

/** id-kp-serverAuth, 1.3.6.1.5.5.7.3.1. */
val ID_KP_SERVER_AUTH = "2b06010505070301".hexToByteArray()

/**
 * webpki 0.103 ChainOptions::build_chain for server authentication: from the end entity, issuers by exact Name bytes,
 * trust anchors before the presented intermediates, at most six intermediates, no subject and key repeated, budgets of
 * 100 signatures, 200,000 path-building calls and 250,000 name-constraint comparisons, the most specific error kept;
 * each certificate checked at [time] for validity, basic constraints for its role and path length, key usage and
 * extKeyUsage serverAuth when present.
 */
class ServerCertificatePath(val anchors: Series<TrustAnchor>, val intermediates: List<ByteArray>, val time: Long) {
    var signatures = 100
    var calls = 200_000
    var comparisons = 250_000
    val path = ArrayList<X509Certificate>()

    fun build(endEntity: X509Certificate): TrustAnchor {
        path += endEntity
        return search(0)
    }

    fun search(subCaCount: Int): TrustAnchor {
        val head = path.last()
        val endEntity = path.size == 1
        checkIssuerIndependentProperties(head, endEntity, subCaCount)
        var error = CertificateError.UnknownIssuer
        for (anchor in anchors.view) {
            if (!head.issuer.contentEquals(anchor.subject)) continue
            try {
                checkSignedChain(anchor)
                checkSignedChainNameConstraints(anchor)
                return anchor
            } catch (e: CertificateException) {
                if (e.error.fatal) throw e
                error = error.mostSpecific(e.error)
            }
        }
        for (der in intermediates) {
            try {
                val candidate = X509Certificate(der)
                if (!candidate.subject.contentEquals(head.issuer)) continue
                if (path.any { it.subjectPublicKeyInfo.contentEquals(candidate.subjectPublicKeyInfo) && it.subject.contentEquals(candidate.subject) }) continue
                if (--calls < 0) throw CertificateException(CertificateError.MaximumPathBuildCallsExceeded)
                if (path.size > 6) throw CertificateException(CertificateError.MaximumPathDepthExceeded)
                path += candidate
                try {
                    return search(if (endEntity) subCaCount else subCaCount + 1)
                } catch (e: CertificateException) {
                    path.removeAt(path.size - 1)
                    throw e
                }
            } catch (e: CertificateException) {
                if (e.error.fatal) throw e
                error = error.mostSpecific(e.error)
            }
        }
        throw CertificateException(error)
    }

    fun checkIssuerIndependentProperties(cert: X509Certificate, endEntity: Boolean, subCaCount: Int) {
        if (cert.notBefore > cert.notAfter) throw CertificateException(CertificateError.InvalidCertValidity)
        if (time < cert.notBefore) throw CertificateException(CertificateError.CertNotValidYet)
        if (time > cert.notAfter) throw CertificateException(CertificateError.CertExpired)
        var ca = false
        var pathLength = -1
        cert.basicConstraints?.let { value ->
            readDer {
                val d = Der(value, 0, value.size)
                ca = d.boolean()
                if (d.peek() >= 0) pathLength = d.integer().let { if (it.size > 1) throw CertificateException(CertificateError.BadDer) else it.firstOrNull()?.toInt()?.and(0xFF) ?: 0 }
                d.end()
            }
        }
        if (endEntity && ca) throw CertificateException(CertificateError.CaUsedAsEndEntity)
        if (!endEntity && !ca) throw CertificateException(CertificateError.EndEntityUsedAsCa)
        if (!endEntity && pathLength >= 0 && subCaCount > pathLength) throw CertificateException(CertificateError.PathLenConstraintViolated)
        if (!endEntity && cert.keyUsage(5) == false) throw CertificateException(CertificateError.KeyCertSignNotSet)
        if (endEntity && cert.keyUsage(0) == false) throw CertificateException(CertificateError.DigitalSignatureNotSet)
        cert.extendedKeyUsage?.let { value ->
            if (value.isEmpty()) throw CertificateException(CertificateError.EmptyEkuExtension)
            val d = Der(value, 0, value.size)
            readDer { while (d.peek() >= 0) if (d.next(0x06).value().contentEquals(ID_KP_SERVER_AUTH)) return }
            throw CertificateException(CertificateError.RequiredEkuNotFound)
        }
    }

    fun checkSignedChain(anchor: TrustAnchor) {
        var spki = anchor.subjectPublicKeyInfo
        for (i in path.indices.reversed()) {
            if (--signatures < 0) throw CertificateException(CertificateError.MaximumSignatureChecksExceeded)
            val cert = path[i]
            verifySignedData(spki, cert.signatureAlgorithm, cert.tbs, cert.signature)
            spki = cert.subjectPublicKeyInfo
        }
    }

    fun checkSignedChainNameConstraints(anchor: TrustAnchor) {
        var constraints = anchor.nameConstraints
        for (i in path.indices.reversed()) {
            constraints?.let { checkNameConstraints(it, i) }
            constraints = path[i].nameConstraints
        }
    }

    /** webpki check_name_constraints: the NameConstraints contents [constraints] against path[top] and every certificate below it. */
    fun checkNameConstraints(constraints: ByteArray, top: Int) {
        val (permitted, excluded) = readDer {
            val d = Der(constraints, 0, constraints.size)
            val permitted = if (d.peek() == 0xA0) d.next(0xA0).value() else null
            val excluded = if (d.peek() == 0xA1) d.next(0xA1).value() else null
            d.end()
            permitted to excluded
        }
        for (i in top downTo 0) {
            for ((tag, name) in generalNames(path[i].subjectAltName)) conform(tag, name, permitted, excluded)
            conform(0xA4, null, permitted, excluded)
        }
    }

    /** webpki check_presented_id_conforms_to_constraints for one presented name of GeneralName [tag]. */
    fun conform(tag: Int, name: ByteArray?, permitted: ByteArray?, excluded: ByteArray?) {
        for ((subtrees, isPermitted) in listOf(permitted to true, excluded to false)) {
            if (subtrees == null) continue
            var match = false
            var mismatch = false
            for ((baseTag, base) in generalSubtrees(subtrees)) {
                if (--comparisons < 0) throw CertificateException(CertificateError.MaximumNameConstraintComparisonsExceeded)
                val matches = when {
                    tag == 0x82 && baseTag == 0x82 -> presentedIdMatchesReferenceId(name!!, if (isPermitted) DnsIdRole.Permitted else DnsIdRole.Excluded, base)
                    tag == 0x87 && baseTag == 0x87 -> ipAddressMatchesConstraint(name!!, base)
                    (tag == 0xA4 || tag == 0x86) && baseTag == tag -> !isPermitted
                    tag != 0x82 && tag != 0x87 && tag != 0xA4 && tag != 0x86 && (tag and 0x1F) == (baseTag and 0x1F) -> throw CertificateException(CertificateError.NameConstraintViolation)
                    else -> continue
                }
                if (isPermitted) { if (matches) match = true else mismatch = true }
                else if (matches) throw CertificateException(CertificateError.NameConstraintViolation)
            }
            if (mismatch && !match) throw CertificateException(CertificateError.NameConstraintViolation)
        }
    }

    /** GeneralSubtrees: each GeneralSubtree holds exactly its base GeneralName (webpki refuses minimum and maximum). */
    fun generalSubtrees(subtrees: ByteArray): List<Pair<Int, ByteArray>> = readDer {
        val d = Der(subtrees, 0, subtrees.size)
        val bases = ArrayList<Pair<Int, ByteArray>>()
        while (d.peek() >= 0) {
            val subtree = d.next(0x30)
            bases += generalName(subtree)
            subtree.end()
        }
        bases
    }
}

/** One GeneralName's tag and contents; webpki refuses tags outside the RFC 5280 CHOICE. */
fun generalName(d: Der): Pair<Int, ByteArray> {
    val tag = d.peek()
    if (tag !in listOf(0xA0, 0x81, 0x82, 0xA3, 0xA4, 0xA5, 0x86, 0x87, 0x88)) throw CertificateException(CertificateError.BadDer)
    return tag to d.next(tag).value()
}

/** The GeneralNames of a subjectAltName extension's contents. */
fun generalNames(subjectAltName: ByteArray?): List<Pair<Int, ByteArray>> {
    if (subjectAltName == null) return emptyList()
    return readDer {
        val d = Der(subjectAltName, 0, subjectAltName.size)
        val names = ArrayList<Pair<Int, ByteArray>>()
        while (d.peek() >= 0) names += generalName(d)
        names
    }
}

enum class DnsIdRole { Reference, Presented, Permitted, Excluded }

/** webpki is_valid_dns_id: RFC 5280 7.2 labels plus underscores, a wildcard label exactly '*' followed by at least two labels. */
fun isValidDnsId(hostname: ByteArray, role: DnsIdRole, wildcards: Boolean): Boolean {
    if (hostname.size > 253) return false
    val constraint = role == DnsIdRole.Permitted || role == DnsIdRole.Excluded
    if (constraint && hostname.isEmpty()) return true
    var at = 0
    var dots = 0
    var labelLength = 0
    var allNumeric = false
    var endsWithHyphen = false
    val wildcard = wildcards && hostname.isNotEmpty() && hostname[0] == '*'.code.toByte()
    var firstByte = !wildcard
    if (wildcard) {
        if (hostname.size < 2 || hostname[1] != '.'.code.toByte()) return false
        at = 2
        dots++
    }
    while (true) {
        if (at >= hostname.size) return false
        when (val c = hostname[at++].toInt().toChar()) {
            '-' -> {
                if (labelLength == 0) return false
                allNumeric = false
                endsWithHyphen = true
                if (++labelLength > 63) return false
            }
            in '0'..'9' -> {
                if (labelLength == 0) allNumeric = true
                endsWithHyphen = false
                if (++labelLength > 63) return false
            }
            '.' -> {
                dots++
                if (labelLength == 0 && (!constraint || !firstByte)) return false
                if (endsWithHyphen) return false
                labelLength = 0
            }
            else -> {
                if (c !in 'a'..'z' && c !in 'A'..'Z' && c != '_') return false
                allNumeric = false
                endsWithHyphen = false
                if (++labelLength > 63) return false
            }
        }
        firstByte = false
        if (at >= hostname.size) break
    }
    if (labelLength == 0 && role != DnsIdRole.Reference) return false
    if (endsWithHyphen || allNumeric) return false
    if (wildcard && (if (labelLength == 0) dots else dots + 1) < 3) return false
    return true
}

/** webpki dns_name::presented_id_matches_reference_id, ASCII case-insensitive. */
fun presentedIdMatchesReferenceId(presented: ByteArray, role: DnsIdRole, reference: ByteArray): Boolean {
    fun lower(b: Byte): Int = b.toInt().let { if (it in 'A'.code..'Z'.code) it + 32 else it }
    val constraint = role == DnsIdRole.Permitted || role == DnsIdRole.Excluded
    if (!isValidDnsId(presented, DnsIdRole.Presented, true)) throw CertificateException(CertificateError.MalformedDnsIdentifier)
    if (!isValidDnsId(reference, role, false)) throw CertificateException(if (constraint) CertificateError.MalformedNameConstraint else CertificateError.MalformedDnsIdentifier)
    var p = 0
    var r = 0
    if (constraint && presented.size > reference.size) {
        if (reference.isEmpty()) return true
        if (reference[0] == '.'.code.toByte()) p = presented.size - reference.size
        else {
            p = presented.size - reference.size - 1
            if (presented[p++] != '.'.code.toByte()) return false
        }
    }
    if (p < presented.size && presented[p] == '*'.code.toByte() && role != DnsIdRole.Permitted) {
        p++
        do {
            if (r >= reference.size) return false
            r++
        } while (!(r < reference.size && reference[r] == '.'.code.toByte()))
    }
    while (true) {
        if (p >= presented.size || r >= reference.size) return false
        val pb = presented[p++]
        val rb = reference[r++]
        if (lower(pb) != lower(rb)) return false
        if (p == presented.size) {
            if (pb == '.'.code.toByte()) throw CertificateException(CertificateError.MalformedDnsIdentifier)
            break
        }
    }
    if (r < reference.size) {
        if (!constraint && reference[r++] != '.'.code.toByte()) return false
        if (r < reference.size) return false
    }
    return true
}

/** webpki ip_address::presented_id_matches_constraint: address and mask of the same family, the mask leading ones then zeros. */
fun ipAddressMatchesConstraint(name: ByteArray, constraint: ByteArray): Boolean {
    when {
        name.size == 4 && constraint.size == 8 || name.size == 16 && constraint.size == 32 -> Unit
        name.size == 4 && constraint.size == 32 || name.size == 16 && constraint.size == 8 -> return false
        name.size == 4 || name.size == 16 -> throw CertificateException(CertificateError.InvalidNetworkMaskConstraint)
        else -> throw CertificateException(CertificateError.BadDer)
    }
    var seenZeroBit = false
    for (i in name.indices) {
        val mask = constraint[name.size + i].toInt() and 0xFF
        val leadingOnes = (mask.inv() and 0xFF).countLeadingZeroBits() - 24
        val trailingZeros = if (mask == 0) 8 else mask.countTrailingZeroBits()
        if (leadingOnes + trailingZeros != 8) throw CertificateException(CertificateError.InvalidNetworkMaskConstraint)
        if (seenZeroBit && mask != 0) throw CertificateException(CertificateError.InvalidNetworkMaskConstraint)
        if (mask != 0xFF) seenZeroBit = true
        if (((name[i].toInt() xor constraint[i].toInt()) and mask) != 0) return false
    }
    return true
}

/** RFC 791 dotted decimal (four octets, no leading zeros) or RFC 4291 2.2 text (with :: and a trailing dotted IPv4), else null. */
fun ipAddress(host: String): ByteArray? {
    fun ipv4(text: String): ByteArray? {
        val parts = text.split('.')
        if (parts.size != 4) return null
        return ByteArray(4) { i ->
            val part = parts[i]
            if (part.isEmpty() || part.length > 3 || part.any { it !in '0'..'9' } || part.length > 1 && part[0] == '0') return null
            part.toInt().takeIf { it <= 255 }?.toByte() ?: return null
        }
    }
    if (':' !in host) return ipv4(host)
    val halves = host.split("::")
    if (halves.size > 2) return null
    fun groups(text: String): List<Int>? {
        if (text.isEmpty()) return emptyList()
        val parts = text.split(':')
        val out = ArrayList<Int>()
        for ((i, part) in parts.withIndex()) {
            if (i == parts.size - 1 && '.' in part) {
                val v4 = ipv4(part) ?: return null
                out += (v4[0].toInt() and 0xFF shl 8) or (v4[1].toInt() and 0xFF)
                out += (v4[2].toInt() and 0xFF shl 8) or (v4[3].toInt() and 0xFF)
            } else {
                if (part.isEmpty() || part.length > 4) return null
                out += part.toIntOrNull(16) ?: return null
            }
        }
        return out
    }
    val head = groups(halves[0]) ?: return null
    val tail = if (halves.size == 2) groups(halves[1]) ?: return null else emptyList()
    val count = head.size + tail.size
    if (if (halves.size == 2) count > 7 else count != 8) return null
    val all = head + List(8 - count) { 0 } + tail
    return ByteArray(16) { i -> (all[i / 2] shr (if (i % 2 == 0) 8 else 0)).toByte() }
}

/** RFC 4514 2.3 attribute type names, by OID contents. */
val RFC4514_NAMES = mapOf(
    "550403" to "CN", "550407" to "L", "550408" to "ST", "55040a" to "O", "55040b" to "OU", "550406" to "C", "550409" to "STREET",
    "0992268993f22c640119" to "DC", "0992268993f22c640101" to "UID",
)

/** RFC 4514 string of a Name's DER contents: RDNs last first, 2.3 short names with string values escaped per 2.4, dotted-decimal types and other values as #hex of their encoding. */
fun rfc4514(name: ByteArray): String = readDer {
    fun oidText(oid: ByteArray): String {
        val arcs = ArrayList<Long>()
        var value = 0L
        for (b in oid) {
            value = (value shl 7) or (b.toLong() and 0x7F)
            if (b >= 0) { arcs += value; value = 0 }
        }
        val first = arcs.firstOrNull() ?: return ""
        val head = if (first < 80) listOf(first / 40, first % 40) else listOf(2L, first - 80)
        return (head + arcs.drop(1)).joinToString(".")
    }

    /** RFC 4514 2.4 escaping. */
    fun escape(value: String): String = buildString {
        for ((i, c) in value.withIndex()) {
            when {
                c in "\"+,;<>\\" || c == '#' && i == 0 || c == ' ' && (i == 0 || i == value.length - 1) -> append('\\').append(c)
                c == '\u0000' -> append("\\00")
                else -> append(c)
            }
        }
    }

    val rdns = ArrayList<String>()
    val d = Der(name, 0, name.size)
    while (d.peek() >= 0) {
        val set = d.next(0x31)
        val attributes = ArrayList<String>()
        while (set.peek() >= 0) {
            val attribute = set.next(0x30)
            val oid = attribute.next(0x06).value()
            val start = attribute.at
            val tag = attribute.peek()
            val value = attribute.next(tag).value()
            attribute.end()
            val type = RFC4514_NAMES[oid.toHexString()]
            val text = when {
                type == null -> "#" + name.copyOfRange(start, attribute.at).toHexString()
                tag == 0x0C || tag == 0x13 || tag == 0x16 || tag == 0x14 -> escape(value.decodeToString())
                tag == 0x1E -> escape(CharArray(value.size / 2) { ((value[2 * it].toInt() and 0xFF shl 8) or (value[2 * it + 1].toInt() and 0xFF)).toChar() }.concatToString())
                else -> "#" + name.copyOfRange(start, attribute.at).toHexString()
            }
            attributes += "${type ?: oidText(oid)}=$text"
        }
        rdns += attributes.joinToString("+")
    }
    rdns.reversed().joinToString(",")
}

/**
 * webpki EndEntityCert::verify_for_usage (server authentication) over the TLS Certificate message's [chain], end entity
 * first, then verify_is_valid_for_subject_name for [serverName]: an IP literal against iPAddress names, otherwise a DNS
 * name against dNSName names, the subject CN never consulted; null skips the name. Returns the end entity.
 */
fun verifyServerCertificate(chain: List<ByteArray>, anchors: Series<TrustAnchor>, serverName: String?, time: Long): X509Certificate {
    if (chain.isEmpty()) throw CertificateException(CertificateError.UnknownIssuer)
    val endEntity = X509Certificate(chain[0])
    ServerCertificatePath(anchors, chain.drop(1), time).build(endEntity)
    if (serverName == null) return endEntity
    val names = generalNames(endEntity.subjectAltName)
    val address = ipAddress(serverName)
    if (address != null) {
        if (names.none { (tag, value) -> tag == 0x87 && value.contentEquals(address) }) throw CertificateException(CertificateError.CertNotValidForName)
        return endEntity
    }
    val reference = serverName.encodeToByteArray()
    if (!isValidDnsId(reference, DnsIdRole.Reference, false)) throw CertificateException(CertificateError.CertNotValidForName)
    for ((tag, value) in names) {
        if (tag != 0x82) continue
        val matched = try {
            presentedIdMatchesReferenceId(value, DnsIdRole.Reference, reference)
        } catch (e: CertificateException) {
            if (e.error != CertificateError.MalformedDnsIdentifier) throw e
            false
        }
        if (matched) return endEntity
    }
    throw CertificateException(CertificateError.CertNotValidForName)
}

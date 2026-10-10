@file:OptIn(ExperimentalEncodingApi::class)

package borg.trikeshed.loom

import borg.trikeshed.job.*
import kotlin.io.encoding.*

/**
 * cocaine-rats crates/loom-mesh/src/gcs.rs, the parts the wire objects carry. GcsConfig holds the two
 * fields a [Handoff] binds to; Rust's also carries the GCS client's credentials and timeout.
 */
class GcsConfig(val bucket: String, val prefix: String = "loom-settlement/v1")

/** A held, generation-pinned GCS object. */
class GcsReceipt(
    val bucket: String,
    val `object`: String,
    val generation: ULong,
    val size: ULong,
    val sha256: ByteArray,
) {
    fun validate() {
        if (!safe_bucket(bucket) || !safe_name(`object`, 256) || generation == 0uL ||
            generation > Long.MAX_VALUE.toULong() || size == 0uL || size > MAX_WIRE.toULong()
        ) error("invalid GCS receipt")
    }
}

fun safe_bucket(bucket: String): Boolean =
    bucket.length in 3..63 && bucket.all { it in 'a'..'z' || it in '0'..'9' || it == '-' } &&
        bucket.first() != '-' && bucket.last() != '-'

fun safe_name(name: String, cap: Int): Boolean =
    name.isNotEmpty() && name.length <= cap && name.split('/').all { part ->
        part.isNotEmpty() && part != "." && part != ".." &&
            part.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '-' || it == '_' || it == '.' }
    }

const val STORAGE_SCOPE: String = "https://www.googleapis.com/auth/devstorage.read_write"

/** google-cloud-auth 1.16 service_account/jws.rs: a token's iat is backdated by this many seconds. */
const val CLOCK_SKEW_FUDGE: ULong = 10uL

/** google-cloud-auth 1.16 service_account/jws.rs: a token's lifetime past now + [CLOCK_SKEW_FUDGE]. */
const val DEFAULT_TOKEN_TIMEOUT: ULong = 3600uL

/**
 * google-cloud-auth 1.16 credentials::service_account::ServiceAccountKey, the fields a token reads. Its
 * service-account credentials sign their own JWT and send it as the bearer; no token endpoint is called.
 */
class ServiceAccountKey(val client_email: String, val private_key_id: String, val private_key: String) {
    /**
     * ServiceAccountTokenGenerator::generate with scopes at [now]: JwsHeader `.` JwsClaims (serde field
     * order, `aud` null) `.` the RS256 signature of both, each base64url without padding.
     */
    fun generate(scope: String, now: ULong): String {
        val header = StringBuilder("{\"alg\":\"RS256\",\"typ\":\"JWT\",\"kid\":").apply { quoted(private_key_id); append('}') }
        val claims = StringBuilder("{\"iss\":").apply {
            quoted(client_email)
            append(",\"scope\":")
            quoted(scope)
            append(",\"aud\":null,\"exp\":").append(now + CLOCK_SKEW_FUDGE + DEFAULT_TOKEN_TIMEOUT)
            append(",\"iat\":").append(now - CLOCK_SKEW_FUDGE)
            append(",\"sub\":")
            quoted(client_email)
            append('}')
        }
        val input = JWS.encode(header.toString().encodeToByteArray()) + "." + JWS.encode(claims.toString().encodeToByteArray())
        return input + "." + JWS.encode(RS256.sign(RSAPrivateKey.pem(private_key), input.encodeToByteArray()))
    }

    companion object {
        /** RFC 7515 base64url without padding. */
        val JWS = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)
    }
}

@file:OptIn(ExperimentalEncodingApi::class)

package borg.trikeshed.loom

import borg.trikeshed.htx.*
import borg.trikeshed.job.*
import borg.trikeshed.lib.*
import borg.trikeshed.reactor.*
import borg.trikeshed.util.*
import kotlin.concurrent.Volatile
import kotlin.io.encoding.*
import kotlinx.coroutines.*

/*
 * cocaine-rats crates/loom-mesh/src/gcs.rs: the insert-only GCS settlement archive. Provider JSON is
 * transport, not mesh wire. The bearer is a keeper-installed `loom-lease/v1` Google token or the self-signed
 * JWT of an explicitly injected service-account key; requests go through [HtxClientReactorElement] (JDK TLS
 * on the JVM; elsewhere https stops at StubTlsCodecBackend until a TLS 1.3 client codec exists).
 */
const val MAX_METADATA: Int = 32 * 1024

/** gcs.rs GcsConfig with serde's defaults. [bucket] and [prefix] are what a [Handoff] binds to. */
data class GcsConfig(
    val bucket: String = "",
    val prefix: String = "loom-settlement/v1",
    val credentials_file: String? = null,
    val allow_gcloud_cli_test_only: Boolean = false,
    val timeout_ms: ULong = 30_000uL,
    /** Lease mode: the handoff service account whose short-lived access tokens arrive as `loom-lease/v1` Google leases. No stored credential. */
    val service_account: String? = null,
) {
    fun validate() {
        if (!safe_bucket(bucket) || !safe_name(prefix, 128) || timeout_ms !in 1uL..120_000uL ||
            (credentials_file != null && allow_gcloud_cli_test_only) ||
            (service_account != null && (credentials_file != null || allow_gcloud_cli_test_only))
        ) error("invalid GCS configuration")
    }

    companion object {
        val fields = s_["bucket", "prefix", "credentials_file", "allow_gcloud_cli_test_only", "timeout_ms", "service_account"]

        /** serde's derived `Deserialize`, `default` and `deny_unknown_fields`. */
        fun deserialize(value: Any): GcsConfig {
            val f = deserialize_struct(value, fields)
            val d = GcsConfig()
            return GcsConfig(
                bucket = f[0]?.deserialize_string("bucket") ?: d.bucket,
                prefix = f[1]?.deserialize_string("prefix") ?: d.prefix,
                credentials_file = f[2]?.deserialize_option { it.deserialize_string("credentials_file") },
                allow_gcloud_cli_test_only = f[3]?.deserialize_bool("allow_gcloud_cli_test_only") ?: d.allow_gcloud_cli_test_only,
                timeout_ms = f[4]?.deserialize_u64("timeout_ms") ?: d.timeout_ms,
                service_account = f[5]?.deserialize_option { it.deserialize_string("service_account") },
            )
        }
    }
}

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

/**
 * The insert-only archive: uploads with `ifGenerationMatch=0` and a temporary hold, then reads back the
 * exact generation and compares its bytes. Owns its HTTP client, which follows no redirects; [close] ends it.
 */
class GcsArchive(var config: GcsConfig, val base: String, val client: HtxClientReactorElement, var auth: Auth) {
    sealed class Auth {
        /** Service-account credentials, their JWT reused until [NORMAL_REFRESH_SLACK] before it expires; null for [new]. */
        class Google(val key: ServiceAccountKey?) : Auth() {
            @Volatile var token: String? = null
            @Volatile var refresh_at: ULong = 0uL
        }

        /** Short-lived token installed by the keeper; refused once expired. */
        class Lease : Auth() {
            @Volatile var lease: borg.trikeshed.loom.Lease? = null
        }

        object Loopback : Auth()
    }

    /** gcs.rs Metadata: the object resource fields bind reads, serde camelCase; other fields are ignored. */
    class Metadata(
        val bucket: String,
        val name: String,
        val generation: String,
        val size: String,
        val temporary_hold: Boolean,
        val content_type: String,
        val cache_control: String,
    ) {
        companion object {
            val FIELDS = s_["bucket", "name", "generation", "size", "temporaryHold", "contentType", "cacheControl"]

            /** serde_json::from_slice: an object carrying every field (a repeated key is refused) or a sequence of exactly the fields; null where serde refuses. */
            fun from_json(bytes: ByteArray): Metadata? {
                val values: List<Any?> = when (val value = json(bytes, unique = true)) {
                    is Map<*, *> -> FIELDS.view.map { value[it] }
                    is List<*> -> if (value.size == FIELDS.size) value else return null
                    else -> return null
                }
                val text = values.filterIndexed { at, _ -> at != 4 }.map { it as? String ?: return null }
                return Metadata(text[0], text[1], text[2], text[3], values[4] as? Boolean ?: return null, text[4], text[5])
            }
        }
    }

    /** Install a fresh token for the configured service account. The old token stays until replaced; neither is persisted. */
    fun install_lease(lease: borg.trikeshed.loom.Lease, now: ULong) {
        val slot = auth as? Auth.Lease ?: error("GCS archive is not in lease mode")
        if (lease.provider() != Provider.Google || lease.scope != config.service_account) error("GCS lease scope mismatch")
        lease.check_usable(now)
        slot.lease = lease
    }

    /** Expiry of the installed lease, if any. */
    fun lease_not_after(): ULong? = (auth as? Auth.Lease)?.lease?.not_after

    suspend fun close() = client.close()

    /** `url` path_segments_mut: bucket and object as single segments; safe_bucket and safe_name leave `/` the only character the segment set encodes. */
    fun object_url(`object`: String, generation: ULong?, media: Boolean): String {
        val query = listOfNotNull(generation?.let { "generation=$it" }, if (media) "alt=media" else null)
        return base + "storage/v1/b/" + config.bucket + "/o/" + `object`.replace("/", "%2F") +
            (if (query.isEmpty()) "" else "?" + query.joinToString("&"))
    }

    /** The `authorization` header value. */
    suspend fun authorized(): String = when (val auth = auth) {
        Auth.Loopback -> "Bearer loom-gcs-loopback-test-only"
        is Auth.Lease -> {
            val lease = auth.lease ?: error("GCS lease absent")
            val now = now()
            try {
                lease.check_usable(now)
            } catch (expired: IllegalStateException) {
                error("GCS lease expired")
            }
            lease.bearer()
        }
        is Auth.Google -> {
            val key = auth.key ?: TODO(
                "GcsArchive::new loads Application Default Credentials (credentials_file or GOOGLE_APPLICATION_CREDENTIALS through " +
                    "protected_credentials; else ~/.config/gcloud or the metadata server; user, impersonated and external accounts each need their OAuth exchange) " +
                    "or the gcloud CLI; only from_service_account_json and leased are ported",
            )
            val now = now()
            auth.token?.takeIf { now < auth.refresh_at } ?: try {
                "Bearer " + key.generate(STORAGE_SCOPE, now)
            } catch (refused: Exception) {
                error("GCS authentication failed")
            }.also {
                auth.token = it
                auth.refresh_at = now + CLOCK_SKEW_FUDGE + DEFAULT_TOKEN_TIMEOUT - NORMAL_REFRESH_SLACK
            }
        }
    }

    suspend fun send(url: String, vararg headers: HtxHeader): HtxResponse {
        val authorization = authorized()
        return client.send(HtxMethod.GET, url, htxHeaders(*headers, "authorization" j authorization), ByteArray(0), config.timeout_ms)
            ?: error("GCS transport failure")
    }

    suspend fun read_metadata(`object`: String, generation: ULong?): Metadata =
        Metadata.from_json(bounded_body(send(object_url(`object`, generation, false)), MAX_METADATA)) ?: error("GCS metadata invalid")

    fun bind(metadata: Metadata, `object`: String, data: ByteArray, generation: ULong?): ULong {
        val found = metadata.generation.toULongOrNull() ?: error("GCS generation invalid")
        val size = metadata.size.toULongOrNull() ?: error("GCS size invalid")
        if (metadata.bucket != config.bucket || metadata.name != `object` || size != data.size.toULong() || !metadata.temporary_hold ||
            metadata.content_type != "application/cbor" || metadata.cache_control != "no-store" || found == 0uL ||
            found > Long.MAX_VALUE.toULong() || (generation != null && generation != found)
        ) error("GCS metadata binding mismatch")
        return found
    }

    suspend fun check_content(`object`: String, generation: ULong, data: ByteArray) {
        val response = send(object_url(`object`, generation, true), "accept-encoding" j "identity")
        val encoding = response.headers.headerValue("content-encoding")
        val found = response.headers.headerValue("x-goog-generation")
        if ((encoding != null && encoding != "identity") || (found != null && found.toULongOrNull() != generation)) {
            error("GCS media binding mismatch")
        }
        if (!bounded_body(response, data.size).contentEquals(data)) error("GCS content mismatch")
    }

    /** Revalidates provider custody; a structurally valid receipt alone never authorizes reclamation. Reads only the receipt's immutable generation. */
    suspend fun verify(receipt: GcsReceipt, data: ByteArray) {
        receipt.validate()
        if (receipt.bucket != config.bucket || !receipt.`object`.startsWith("${config.prefix}/") ||
            receipt.size != data.size.toULong() || !receipt.sha256.contentEquals(sha256(data))
        ) error("GCS receipt binding mismatch")
        val metadata = read_metadata(receipt.`object`, receipt.generation)
        bind(metadata, receipt.`object`, data, receipt.generation)
        check_content(receipt.`object`, receipt.generation, data)
    }

    suspend fun commit(relative_name: String, data: ByteArray): GcsReceipt {
        val `object` = "${config.prefix}/$relative_name"
        if (!safe_name(relative_name, 256) || !safe_name(`object`, 256) || data.isEmpty() || data.size > MAX_WIRE) error("GCS object bounds")
        val metadata = jsonText(mapOf("name" to `object`, "temporaryHold" to true, "contentType" to "application/cbor", "cacheControl" to "no-store"))
        val digest = sha256(data)
        val boundary = "loom-" + digest.toLowerHex()
        val body = "--$boundary\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n$metadata\r\n--$boundary\r\nContent-Type: application/cbor\r\n\r\n"
            .encodeToByteArray() + data + "\r\n--$boundary--\r\n".encodeToByteArray()
        val url = base + "upload/storage/v1/b/" + config.bucket + "/o?uploadType=multipart&ifGenerationMatch=0"
        val headers = htxHeaders("content-type" j "multipart/related; boundary=$boundary", "authorization" j authorized())
        val upload = client.send(HtxMethod.POST, url, headers, body, config.timeout_ms)
        // Never replay a write blindly. Ambiguous completion and precondition failure both require an independent read of the extant object.
        val uploaded = when {
            upload == null -> null
            upload.status == 200 -> runCatching { Metadata.from_json(bounded_body(upload, MAX_METADATA)) }.getOrNull()
            upload.status == 412 || upload.status in 500..599 || upload.status == 408 -> null
            else -> error("GCS upload rejected")
        }
        val generation = if (uploaded != null) {
            val generation = bind(uploaded, `object`, data, null)
            bind(read_metadata(`object`, generation), `object`, data, generation)
        } else {
            bind(read_metadata(`object`, null), `object`, data, null)
        }
        check_content(`object`, generation, data)
        return GcsReceipt(config.bucket, `object`, generation, data.size.toULong(), digest)
    }

    companion object {
        /** The storage origin; configuration cannot override it. */
        const val ORIGIN: String = "https://storage.googleapis.com/"

        /** google-cloud-auth 1.16 token_cache.rs: a token is replaced this long before it expires. */
        const val NORMAL_REFRESH_SLACK: ULong = 240uL

        /** Explicit serverless identity, supplied in the protected bootstrap document. No filesystem, ambient ADC, metadata identity or gcloud fallback. */
        suspend fun from_service_account_json(config: GcsConfig, source: String, routeService: HtxRouteService): GcsArchive {
            if (config.credentials_file != null || config.allow_gcloud_cli_test_only || source.encodeToByteArray().size > 64 * 1024) {
                error("serverless GCS credentials must be explicitly injected")
            }
            config.validate()
            fun refused(): Nothing = error("invalid GCS service-account credentials")
            val json = json(source.encodeToByteArray(), unique = false) as? Map<*, *> ?: refused()
            if (json["type"] != "service_account" || (json["client_email"] as? String)?.endsWith(".gserviceaccount.com") != true ||
                (json.containsKey("token_uri") && json["token_uri"] != "https://oauth2.googleapis.com/token")
            ) refused()
            // google-cloud-auth's ServiceAccountKey: four required strings and an optional universe_domain.
            if (json["project_id"] !is String || json["universe_domain"].let { it != null && it != Null && it !is String }) refused()
            val key = ServiceAccountKey(
                json["client_email"] as String,
                json["private_key_id"] as? String ?: refused(),
                json["private_key"] as? String ?: refused(),
            )
            return build(config, ORIGIN, Auth.Google(key), routeService)
        }

        /** Synchronous and offline in Rust: credentials load only when a request is made (here a TODO in [authorized]). */
        suspend fun new(config: GcsConfig, routeService: HtxRouteService): GcsArchive {
            config.validate()
            return build(config, ORIGIN, Auth.Google(null), routeService)
        }

        /** Lease mode: no credential is loaded. Every request needs a current Google lease for `config.service_account`, installed by the keeper. */
        suspend fun leased(config: GcsConfig, routeService: HtxRouteService): GcsArchive {
            config.validate()
            if (config.service_account == null) error("GCS lease mode requires a service account")
            return build(config, ORIGIN, Auth.Lease(), routeService)
        }

        /** Lease mode over the loopback fixture transport. */
        suspend fun leased_for_loopback_test(config: GcsConfig, base_url: String, routeService: HtxRouteService): GcsArchive {
            val archive = for_loopback_test(config.copy(service_account = null), base_url, routeService)
            if (config.service_account == null) {
                archive.close()
                error("GCS lease mode requires a service account")
            }
            archive.config = config
            archive.auth = Auth.Lease()
            return archive
        }

        /**
         * INSECURE fixture transport: only a literal loopback HTTP origin (a dotted-quad 127/8 address or `[::1]`, path `/`)
         * and a fixed synthetic bearer. Never reads ADC or invokes the gcloud CLI.
         */
        suspend fun for_loopback_test(config: GcsConfig, base_url: String, routeService: HtxRouteService): GcsArchive {
            config.validate()
            val origin = loopback_origin(base_url)
            if (origin == null || config.credentials_file != null || config.allow_gcloud_cli_test_only) {
                error("GCS test transport requires credential-free loopback origin")
            }
            return build(config, origin, Auth.Loopback, routeService)
        }

        /** `http://<loopback>[:port]` with an empty or `/` path and no user, query or fragment, as `http://authority/`; else null. */
        fun loopback_origin(base_url: String): String? {
            if (!base_url.startsWith("http://")) return null
            val rest = base_url.substring(7)
            val end = rest.indexOfFirst { it == '/' || it == '?' || it == '#' }.let { if (it < 0) rest.length else it }
            val authority = rest.substring(0, end)
            if (rest.substring(end) !in setOf("", "/") || '@' in authority) return null
            val host = if (authority.startsWith("[")) authority.substringBefore(']') + "]" else authority.substringBefore(':')
            val port = authority.substring(host.length)
            if (port.isNotEmpty() && (!port.startsWith(":") || port.substring(1).toIntOrNull()?.takeIf { it in 0..65535 } == null)) return null
            val octets = host.split('.')
            val loopback = host == "[::1]" || (octets.size == 4 && octets[0] == "127" &&
                octets.all { it.isNotEmpty() && it.length <= 3 && it.all { c -> c in '0'..'9' } && (it == "0" || it[0] != '0') && it.toInt() <= 255 })
            return if (loopback) "http://$authority/" else null
        }

        suspend fun build(config: GcsConfig, base: String, auth: Auth, routeService: HtxRouteService): GcsArchive {
            if (base.startsWith("https://") && routeService is HtxReactorElement && routeService.tlsBackend is StubTlsCodecBackend) {
                TODO("GCS over https on this target waits on a TLS 1.3 client codec behind TlsCodecBackend; the platform registers StubTlsCodecBackend")
            }
            return GcsArchive(config, base, HtxClientReactorElement(routeService, HtxClientOptions(maxRedirects = 0)).also { it.open() }, auth)
        }

        fun bounded_body(response: HtxResponse, cap: Int): ByteArray {
            if (response.status != 200) error("GCS HTTP status")
            if ((response.headers.headerValue("content-length")?.toULongOrNull() ?: 0uL) > cap.toULong()) error("GCS response too large")
            val body = response.body.toArray()
            if (body.size > cap) error("GCS response too large")
            return body
        }
    }
}

fun safe_bucket(bucket: String): Boolean =
    bucket.length in 3..63 && bucket.all { it in 'a'..'z' || it in '0'..'9' || it == '-' } &&
        bucket.first() != '-' && bucket.last() != '-'

/** reqwest `RequestBuilder::send`: the response, or null where reqwest fails the request (transport failure or timeout). */
suspend fun HtxClientReactorElement.send(method: HtxMethod, url: String, headers: HtxHeaders, body: ByteArray, timeout_ms: ULong): HtxResponse? = try {
    request(parseHtxRequest(url, method = method, body = ByteSeries(body)).copy(headers = headers, timeoutMs = timeout_ms.toLong()))
} catch (timeout: TimeoutCancellationException) {
    currentCoroutineContext().ensureActive()
    null
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (failure: Exception) {
    null
}

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

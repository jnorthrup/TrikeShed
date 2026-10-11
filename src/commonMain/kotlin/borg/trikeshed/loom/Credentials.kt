@file:OptIn(ExperimentalTime::class)

package borg.trikeshed.loom

import borg.trikeshed.context.*
import borg.trikeshed.htx.*
import borg.trikeshed.lib.*
import borg.trikeshed.userspace.nio.channels.spi.*
import borg.trikeshed.userspace.nio.platform.spi.*
import borg.trikeshed.userspace.nio.spi.*
import kotlin.coroutines.*
import kotlin.random.Random
import kotlin.time.*
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource.Monotonic.ValueTimeMark
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/*
 * google-cloud-auth 1.16.0 (default features off, `idtoken`) as cocaine-rats gcs.rs builds it: credentials.rs
 * `Credentials` over token_cache.rs `TokenCache`, errors.rs, headers_util.rs, the metadata-server (mds.rs,
 * mds/client.rs), user-account, service-account and impersonated token providers, and google-cloud-gax 1.14's retry
 * loop where impersonated.rs uses one. Trust boundaries are off (the crate gates them on
 * `google_cloud_unstable_trust_boundaries`). Each token request goes through the caller's [HtxClientReactorElement].
 */

const val DEFAULT_SCOPE: String = "https://www.googleapis.com/auth/cloud-platform"
const val OAUTH2_TOKEN_SERVER_URL: String = "https://oauth2.googleapis.com/token"
const val DEFAULT_UNIVERSE_DOMAIN: String = "googleapis.com"
const val QUOTA_PROJECT_KEY: String = "x-goog-user-project"
const val X_GOOG_API_CLIENT: String = "x-goog-api-client"
const val MDS_DEFAULT_URI: String = "/computeMetadata/v1/instance/service-accounts/default"
const val METADATA_FLAVOR: String = "metadata-flavor"
const val METADATA_FLAVOR_VALUE: String = "Google"
const val METADATA_ROOT: String = "http://metadata.google.internal"
const val GCE_METADATA_HOST_ENV_VAR: String = "GCE_METADATA_HOST"

/** headers_util.rs metrics_header_value with the reference build's build_info: rustc 1.96.0, google-cloud-auth 1.16.0. */
fun metrics_header_value(request_type: String, cred_type: String): String =
    "gl-rust/1.96.0 auth/1.16.0 auth-request-type/$request_type cred-type/$cred_type"

/** google-cloud-gax CredentialsError: [is_transient] tells the token cache whether a later fetch may succeed. */
class CredentialsError(val is_transient: Boolean, message: String) : Exception(message)

/** build_errors.rs Error: credentials a builder refuses. */
class BuilderError(message: String) : Exception(message)

/** reqwest::Error from `send`: a request reqwest refuses to build, else a failed transport (connect or request). */
class SendError(val is_builder: Boolean) : Exception(if (is_builder) "request builder" else "transport")

/** token.rs Token; [expires_at] is a monotonic mark (tokio Instant), null for a token that never expires. */
class Token(val token: String, val token_type: String, val expires_at: ValueTimeMark?)

/** token.rs TokenProvider. */
fun interface TokenProvider {
    suspend fun token(): Token
}

/** std::env::var_os through the context's [SystemOperations], else the platform's. */
suspend fun var_os(name: String): ByteArray? = (currentCoroutineContext()[SystemOperations] ?: SystemOperations.default).var_os(name)

/** std::env::var: the value when set and valid UTF-8. */
suspend fun env_var(name: String): String? = Bootstrap.utf8(var_os(name))

/** The platform's [ProcessOperations]: the context's, its NioSupervisor's, else a NioSupervisor's opened for [block]. */
suspend fun <T> process(block: suspend (ProcessOperations) -> T): T {
    val context = currentCoroutineContext()
    (context[ProcessOperations] ?: context[NioSupervisor]?.service<ProcessOperations>())?.let { return block(it) }
    val nio = NioSupervisor().also { it.open() }
    try {
        return block(nio.service<ProcessOperations>() ?: error("no ProcessOperations on this platform"))
    } finally {
        nio.close()
    }
}

/**
 * token_cache.rs TokenCache: its refresh task fetches at once, publishes each token or error to one watch slot, and
 * fetches again [NORMAL_REFRESH_SLACK] before a token expires, every [SHORT_REFRESH_SLACK] within that, at once when it
 * has, and [SHORT_REFRESH_SLACK] after a transient failure; a permanent failure ends the task once the current token
 * expires. Drain lets an in-flight fetch publish, then stops the task instead of sleeping.
 */
class TokenCache(val provider: TokenProvider) : AsyncContextElement() {
    companion object Key : AsyncContextKey<TokenCache>() {
        val NORMAL_REFRESH_SLACK: Duration = 240.seconds
        val SHORT_REFRESH_SLACK: Duration = 10.seconds

        /** TokenCache::new: the refresh task starts at once in the caller's context, as tokio::spawn does on its runtime. */
        suspend fun new(provider: TokenProvider): TokenCache = TokenCache(provider).also { cache ->
            cache.open()
            CoroutineScope(currentCoroutineContext() + cache.supervisor).launch { cache.refresh_task() }
        }
    }

    override val key: CoroutineContext.Key<*> get() = Key

    /** The watch channel: the number of sends and the latest token or error, null before the first. */
    val tx_token = MutableStateFlow<Join<Long, Result<Token>?>>(0L j null)

    val draining = CompletableDeferred<Unit>()

    fun send(value: Result<Token>) = tx_token.update { (it.a + 1L) j value }

    fun current_expiration(): ValueTimeMark? = tx_token.value.b?.getOrNull()?.expires_at

    suspend fun sleep(duration: Duration) {
        withTimeoutOrNull(duration) { draining.await() }
    }

    suspend fun sleep_until(deadline: ValueTimeMark) = sleep(-deadline.elapsedNow())

    suspend fun refresh_task() {
        try {
            while (!draining.isCompleted) {
                val token = try {
                    provider.token()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (e: CredentialsError) {
                    if (!e.is_transient) {
                        current_expiration()?.let { sleep_until(it) }
                        send(Result.failure(e))
                        return
                    }
                    val now = TimeSource.Monotonic.markNow()
                    val short = now + SHORT_REFRESH_SLACK
                    val d = current_expiration()
                    when {
                        d == null || d < now -> send(Result.failure(e))
                        d < short -> {
                            sleep_until(d)
                            send(Result.failure(e))
                        }
                    }
                    sleep_until(short)
                    continue
                } catch (panic: Throwable) {
                    // A panic in the spawned task: the caller waiting on the channel panics too.
                    send(Result.failure(panic))
                    throw panic
                }
                send(Result.success(token))
                val expiry = token.expires_at ?: break
                val until = -expiry.elapsedNow()
                if (until > NORMAL_REFRESH_SLACK) sleep(until - NORMAL_REFRESH_SLACK)
                else if (until > SHORT_REFRESH_SLACK) sleep(SHORT_REFRESH_SLACK)
            }
        } finally {
            if (tx_token.value.b == null) send(Result.failure(CredentialsError(false, "token cache closed")))
        }
    }

    /** latest_token_and_entity_tag: the published token, or the next send when none is published or it has expired. */
    suspend fun token(): Token {
        val seen = tx_token.value
        val token = (seen.b ?: return wait_for_next_token(seen.a)).getOrThrow()
        val expires = token.expires_at
        return if (expires != null && expires < TimeSource.Monotonic.markNow()) wait_for_next_token(seen.a) else token
    }

    suspend fun wait_for_next_token(seen: Long): Token = tx_token.first { it.a > seen }.b!!.getOrThrow()

    override suspend fun drain() {
        draining.complete(Unit)
        super.drain()
    }
}

/**
 * credentials.rs Credentials as gcs.rs reads them: [headers] is `headers(Default::default())`, always `New`; the
 * credentials these were built from ([sources]) close with them.
 */
class Credentials(val token_provider: TokenCache, val quota_project_id: String?, val sources: List<Credentials> = emptyList()) {
    suspend fun headers(): HtxHeaders = as_headers(token_provider.token(), quota_project_id)

    suspend fun close() {
        token_provider.close()
        for (source in sources) source.close()
    }
}

/** headers_util.rs AuthHeadersBuilder: `authorization: <token_type> <token>`, then the quota project's header. */
fun as_headers(token: Token, quota_project_id: String?): HtxHeaders {
    val value = "${token.token_type} ${token.token}"
    if (!header_value(value) || (quota_project_id != null && !header_value(quota_project_id))) throw CredentialsError(false, "invalid header value")
    return (listOf("authorization" j value) + listOfNotNull(quota_project_id?.let { QUOTA_PROJECT_KEY j it })).toSeries()
}

/** http `HeaderValue::from_str`: tab, space, visible ASCII and every byte from 0x80. */
fun header_value(text: String): Boolean = text.all { it == '\t' || (it >= ' ' && it != '\u007f') }

/** http `HeaderName::from_bytes`: one or more RFC 9110 token characters. */
fun header_name(text: String): Boolean = text.isNotEmpty() && text.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it in "!#$%&'*+-.^_`|~" }

/** errors.rs is_retryable_code: a server error, 408 or 429. */
fun is_retryable_code(status: Int): Boolean = status in 500..599 || status == 408 || status == 429

/**
 * A request from google-cloud-auth's own reqwest client: the URL as the url crate parses it (a refusal or a scheme other
 * than http and https is reqwest's builder error), no timeout unless [timeout]. Throws [SendError] where reqwest's `send`
 * fails. reqwest's environment proxy and its default redirect policy are not ported: each is a TODO where it would act.
 */
suspend fun HtxClientReactorElement.token_request(
    method: HtxMethod,
    url: String,
    headers: List<HtxHeader>,
    body: ByteArray = ByteArray(0),
    timeout: Duration? = null,
): HtxResponse {
    val parsed = Url.parse(url)?.takeIf { (it.scheme == "http" || it.scheme == "https") && !it.host.isNullOrEmpty() } ?: throw SendError(true)
    if (!headers.all { header_name(it.a) && header_value(it.b) }) throw SendError(true)
    system_proxy(parsed.scheme)
    val millis = timeout?.inWholeMilliseconds?.coerceIn(1L, Int.MAX_VALUE.toLong()) ?: Int.MAX_VALUE.toLong()
    val response = send(method, parsed.serialization.substringBefore('#'), headers.toSeries(), body, millis.toULong()) ?: throw SendError(false)
    if (response.status in setOf(301, 302, 303, 307, 308) && response.headers.headerValue("location") != null) {
        TODO("reqwest's default redirect policy (follow up to 10, 301-303 as GET, sensitive headers dropped across origins) for a google-cloud-auth token request")
    }
    return response
}

/**
 * hyper-util Matcher::from_env as reqwest's token clients read it (unless REQUEST_METHOD is set): the first set of
 * HTTP(S)_PROXY / http(s)_proxy, else of ALL_PROXY / all_proxy. A non-empty value is a TODO whether or not NO_PROXY
 * would exempt the host.
 */
suspend fun system_proxy(scheme: String) {
    if (var_os("REQUEST_METHOD") != null) return
    suspend fun first(upper: String, lower: String): String = env_var(upper) ?: env_var(lower) ?: ""
    val proxy = (if (scheme == "https") first("HTTPS_PROXY", "https_proxy") else first("HTTP_PROXY", "http_proxy")).ifEmpty { first("ALL_PROXY", "all_proxy") }
    if (proxy.isNotEmpty()) TODO("reqwest sends google-cloud-auth's $scheme token requests through the environment's proxy; HTX has no proxy client")
}

/** url form_urlencoded::byte_serialize: `*-._` and alphanumerics kept, space as `+`, every other byte `%XX`. */
fun byte_serialize(text: String): String = StringBuilder().apply {
    for (byte in text.encodeToByteArray()) {
        val b = byte.toInt() and 0xff
        val c = b.toChar()
        when {
            c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c in "*-._" -> append(c)
            c == ' ' -> append('+')
            else -> append('%').append(Url.HEX[b shr 4]).append(Url.HEX[b and 15])
        }
    }
}.toString()

/** serde_urlencoded / form_urlencoded::Serializer: `k=v` pairs joined by `&`. */
fun form_urlencoded(pairs: List<Join<String, String>>): String = pairs.joinToString("&") { byte_serialize(it.a) + "=" + byte_serialize(it.b) }

/** A serde refusal while reading a field. */
fun serde_refusal(): Nothing = throw IllegalArgumentException("serde")

fun Any?.serde_string(): String = this as? String ?: serde_refusal()
fun Any?.serde_option_string(): String? = if (this == null || this == Null) null else serde_string()
fun Any?.serde_option_strings(): List<String>? = when (this) {
    null, Null -> null
    is List<*> -> map { it.serde_string() }
    else -> serde_refusal()
}
fun Any?.serde_u64(): ULong = asU64(this) ?: serde_refusal()
fun Any?.serde_option_u64(): ULong? = if (this == null || this == Null) null else serde_u64()
fun Any?.serde_bool(): Boolean = this as? Boolean ?: serde_refusal()

/**
 * serde's derived struct without deny_unknown_fields, from a `Value`: an object's known fields (others ignored) or an
 * array of exactly the fields. The values in [fields] order, null where absent and [Null] where JSON null.
 */
fun serde_struct(value: Any?, fields: List<String>): Array<Any?> = when (value) {
    is Map<*, *> -> Array(fields.size) { value[fields[it]] }
    is List<*> -> if (value.size == fields.size) Array(fields.size) { value[it] } else serde_refusal()
    else -> serde_refusal()
}

/**
 * serde_json::from_slice into such a struct: as [serde_struct], and a known field twice is refused; null where serde
 * refuses the bytes.
 */
fun serde_struct(bytes: ByteArray, fields: List<String>): Array<Any?>? = try {
    val reader = JsonReader(bytes, unique = false)
    reader.whitespace()
    val values = when (reader.peek()) {
        '{'.code -> reader.nested {
            val values = arrayOfNulls<Any>(fields.size)
            reader.whitespace()
            if (reader.peek() == '}'.code) reader.at++ else while (true) {
                reader.whitespace()
                require(reader.next() == '"'.code)
                val name = reader.string()
                reader.whitespace()
                require(reader.next() == ':'.code)
                val field = reader.value()
                val at = fields.indexOf(name)
                if (at >= 0) {
                    require(values[at] == null)
                    values[at] = field
                }
                reader.whitespace()
                val c = reader.next()
                if (c == '}'.code) break
                require(c == ','.code)
            }
            values
        }
        '['.code -> serde_struct(reader.nested { reader.array() }, fields)
        else -> serde_refusal()
    }
    reader.whitespace()
    if (reader.at == bytes.size) values else null
} catch (refused: IllegalArgumentException) {
    null
}

/** A response body as serde_json reads it into a struct of [fields], then [read]; a refusal is a permanent error. */
fun <T> decode(response: HtxResponse, fields: List<String>, read: (Array<Any?>) -> T): T = try {
    read(serde_struct(response.body.toArray(), fields) ?: serde_refusal())
} catch (refused: IllegalArgumentException) {
    throw CredentialsError(false, "decode")
}

/** `Instant::now() + Duration::from_secs(secs)`; past i64 seconds Instant's add panics. */
fun expires_in(secs: ULong): ValueTimeMark {
    if (secs > Long.MAX_VALUE.toULong()) throw Error("overflow when adding duration to instant")
    return TimeSource.Monotonic.markNow() + secs.toLong().seconds
}

/** mds/client.rs Client::new(None): `http://` + GCE_METADATA_HOST when set and UTF-8, else the metadata server, `/` trimmed. */
suspend fun mds_endpoint(): String = (env_var(GCE_METADATA_HOST_ENV_VAR)?.let { "http://$it" } ?: METADATA_ROOT).trimEnd('/')

/**
 * credentials::mds::Builder::default().with_scopes(scopes).build(): GETs `<endpoint>/computeMetadata/v1/instance/
 * service-accounts/default/token?scopes=<comma-joined>` with `metadata-flavor: Google`, once per fetch (NeverRetry).
 */
suspend fun mds_credentials(scopes: List<String>?, http: HtxClientReactorElement): Credentials {
    val endpoint = mds_endpoint()
    val query = scopes?.let { "?" + form_urlencoded(listOf("scopes" j it.joinToString(","))) } ?: ""
    val provider = TokenProvider {
        val response = try {
            http.token_request(HtxMethod.GET, "$endpoint$MDS_DEFAULT_URI/token$query", listOf(METADATA_FLAVOR j METADATA_FLAVOR_VALUE))
        } catch (e: SendError) {
            throw CredentialsError(!e.is_builder, "failed to fetch access token")
        }
        if (response.status !in 200..299) throw CredentialsError(is_retryable_code(response.status), "failed to fetch access token")
        decode(response, listOf("access_token", "expires_in", "token_type")) {
            Token(it[0].serde_string(), it[2].serde_string(), it[1].serde_option_u64()?.let(::expires_in))
        }
    }
    return Credentials(TokenCache.new(provider), null)
}

/**
 * credentials::user_account::Builder::new(json).with_scopes(scopes).build(): AuthorizedUser (type, client_id,
 * client_secret and refresh_token required) in googleapis.com only; POSTs the refresh-token grant as JSON to `token_uri`.
 */
suspend fun user_account_credentials(json: Any?, scopes: List<String>?, http: HtxClientReactorElement): Credentials {
    val user = try {
        serde_struct(json, listOf("type", "client_id", "client_secret", "refresh_token", "token_uri", "quota_project_id", "universe_domain")).also {
            for (i in 0..3) it[i].serde_string()
            for (i in 4..6) it[i].serde_option_string()
        }
    } catch (refused: IllegalArgumentException) {
        throw BuilderError("authorized_user")
    }
    user[6].serde_option_string()?.let { if (it != DEFAULT_UNIVERSE_DOMAIN) throw BuilderError("User Account Credentials are not supported in universes other than googleapis.com") }
    val endpoint = user[4].serde_option_string() ?: OAUTH2_TOKEN_SERVER_URL
    val body = StringBuilder("{\"grant_type\":\"refresh_token\",\"client_id\":").apply {
        quoted(user[1].serde_string())
        append(",\"client_secret\":")
        quoted(user[2].serde_string())
        append(",\"refresh_token\":")
        quoted(user[3].serde_string())
        append(",\"scopes\":")
        if (scopes == null) append("null") else quoted(scopes.joinToString(" "))
        append('}')
    }.toString().encodeToByteArray()
    val provider = TokenProvider {
        val response = try {
            http.token_request(HtxMethod.POST, endpoint, listOf("content-type" j "application/json"), body)
        } catch (e: SendError) {
            throw CredentialsError(!e.is_builder, "failed to refresh user access token")
        }
        if (response.status !in 200..299) throw CredentialsError(is_retryable_code(response.status), "failed to refresh user access token")
        decode(response, listOf("access_token", "id_token", "scope", "expires_in", "token_type", "refresh_token")) {
            it[1].serde_option_string()
            it[2].serde_option_string()
            it[5].serde_option_string()
            Token(it[0].serde_string(), it[4].serde_string(), it[3].serde_option_u64()?.let(::expires_in))
        }
    }
    return Credentials(TokenCache.new(provider), user[5].serde_option_string())
}

/**
 * service_account.rs ServiceAccountTokenProvider under a [TokenCache]: each fetch self-signs a JWT for [scopes]
 * (space-joined) at the wall clock, expiring CLOCK_SKEW_FUDGE + DEFAULT_TOKEN_TIMEOUT later; no quota project.
 */
suspend fun ServiceAccountKey.credentials(scopes: List<String>): Credentials = Credentials(TokenCache.new {
    val expires_at = TimeSource.Monotonic.markNow() + (CLOCK_SKEW_FUDGE + DEFAULT_TOKEN_TIMEOUT).toLong().seconds
    val token = try {
        generate(scopes.joinToString(" "), now())
    } catch (refused: Exception) {
        throw CredentialsError(false, "failed to sign the service account token")
    }
    Token(token, "Bearer", expires_at)
}, null)

/** credentials.rs extract_credential_type. */
fun extract_credential_type(json: Any?): String =
    ((json as? Map<*, *>)?.get("type") ?: throw BuilderError("no `type` field found.")) as? String ?: throw BuilderError("`type` field is not a string.")

/** credentials.rs build_credentials(Some(json), None, None, None): a source credential with each builder's defaults. */
suspend fun build_credentials(json: Any?, http: HtxClientReactorElement): Credentials = when (val type = extract_credential_type(json)) {
    "authorized_user" -> user_account_credentials(json, null, http)
    "service_account" -> ServiceAccountKey.from_value(json).credentials(listOf(DEFAULT_SCOPE))
    "impersonated_service_account" -> impersonated_credentials(json, null, http)
    "external_account" -> external_account_credentials(json, null, http)
    "gdch_service_account" -> throw BuilderError("$type, use gdch::Builder directly.")
    else -> throw BuilderError("unknown credential type $type")
}

/** impersonated.rs extract_client_email: exactly one `/serviceAccounts/`. */
fun extract_client_email(service_account_impersonation_url: String): String {
    val parts = service_account_impersonation_url.split("/serviceAccounts/")
    if (parts.size != 2) throw BuilderError("invalid service account impersonation URL")
    return parts[1].removeSuffixRepeatedly(":generateAccessToken")
}

/** `str::trim_end_matches` for a string pattern. */
fun String.removeSuffixRepeatedly(suffix: String): String {
    var text = this
    while (suffix.isNotEmpty() && text.endsWith(suffix)) text = text.dropLast(suffix.length)
    return text
}

/**
 * credentials::impersonated::Builder::new(json).with_scopes(scopes).build(): ImpersonatedConfig, its source built by
 * type (an impersonated source refused), scopes defaulting to the file's then cloud-platform, the file's quota project;
 * each fetch takes the source's headers and calls [generate_access_token] at the exact URL.
 */
suspend fun impersonated_credentials(json: Any?, scopes: List<String>?, http: HtxClientReactorElement): Credentials {
    val config = try {
        serde_struct(json, listOf("service_account_impersonation_url", "source_credentials", "delegates", "quota_project_id", "scopes", "universe_domain")).also {
            it[0].serde_string()
            if (it[1] == null) serde_refusal()
            it[2].serde_option_strings()
            it[3].serde_option_string()
            it[4].serde_option_strings()
            it[5].serde_option_string()
        }
    } catch (refused: IllegalArgumentException) {
        throw BuilderError("impersonated_service_account")
    }
    val url = config[0].serde_string()
    extract_client_email(url)
    if (extract_credential_type(config[1]) == "impersonated_service_account") {
        throw BuilderError("source credential of type `impersonated_service_account` is not supported. Use the `delegates` field to specify a delegation chain.")
    }
    val source = build_credentials(config[1], http)
    val token_scopes = scopes ?: config[4].serde_option_strings() ?: listOf(DEFAULT_SCOPE)
    val delegates = config[2].serde_option_strings()
    val cache = TokenCache.new { generate_access_token(http, source.headers(), delegates, token_scopes, url) }
    return Credentials(cache, config[3].serde_option_string(), listOf(source))
}

/**
 * impersonated.rs generate_access_token: POST [url] with `content-type: application/json`, the metrics header and the
 * source's headers, body `{"delegates":…,"scope":…,"lifetime":"3600s"}`; retried by [retry_loop] (AIP-194 strict plus
 * 429 and attempt timeouts, 60 s), each attempt at most 10 s. The token expires at `expireTime` (RFC 3339).
 */
suspend fun generate_access_token(http: HtxClientReactorElement, source_headers: HtxHeaders, delegates: List<String>?, scopes: List<String>, url: String): Token {
    val body = StringBuilder("{").apply {
        if (delegates != null) {
            append("\"delegates\":[")
            delegates.forEachIndexed { i, d -> if (i > 0) append(','); quoted(d) }
            append("],")
        }
        append("\"scope\":[")
        scopes.forEachIndexed { i, s -> if (i > 0) append(','); quoted(s) }
        append("],\"lifetime\":\"3600s\"}")
    }.toString().encodeToByteArray()
    val headers = replace_headers(listOf("content-type" j "application/json", X_GOOG_API_CLIENT j metrics_header_value("at", "imp")), source_headers)
    val response = try {
        retry_loop { remaining ->
            val limit = if (remaining < 10.seconds) remaining else 10.seconds
            withTimeoutOrNull(limit) {
                val response = try {
                    http.token_request(HtxMethod.POST, url, headers, body)
                } catch (e: SendError) {
                    throw GaxError.Io(e.is_builder)
                }
                if (response.status !in 200..299) throw GaxError.Http(response.status)
                response
            } ?: throw GaxError.Timeout()
        }
    } catch (e: GaxError) {
        throw CredentialsError(e.is_retryable(), "failed to fetch token")
    }
    val (token, expire_time) = decode(response, listOf("accessToken", "expireTime")) { it[0].serde_string() to it[1].serde_string() }
    val expiry = rfc3339(expire_time) ?: throw CredentialsError(false, "invalid expireTime")
    val now = Clock.System.now()
    val remaining = (expiry.a - now.epochSeconds).seconds + (expiry.b - now.nanosecondsOfSecond).nanoseconds
    // `remaining_duration.try_into().unwrap()` panics on an expireTime already past.
    if (remaining.isNegative()) throw Error("impersonated.rs: an expireTime in the past")
    return Token(token, "Bearer", TimeSource.Monotonic.markNow() + remaining)
}

/** reqwest RequestBuilder::headers (util::replace_headers): a name already present is replaced in place, a new one appended. */
fun replace_headers(dst: List<HtxHeader>, src: HtxHeaders): List<HtxHeader> {
    val headers = dst.toMutableList()
    for (header in src.toList()) {
        val at = headers.indexOfFirst { it.a.equals(header.a, ignoreCase = true) }
        if (at >= 0) headers[at] = header else headers += header
    }
    return headers
}

/** google-cloud-gax Error as impersonated.rs's retry loop produces it. */
sealed class GaxError(message: String) : Exception(message) {
    /** GaxError::io: reqwest's send failed, building ([is_builder]) or in transport. */
    class Io(val is_builder: Boolean) : GaxError("io")
    class Http(val status: Int) : GaxError("http $status")
    class Timeout : GaxError("timeout")
    class Exhausted(val prev: GaxError) : GaxError("exhausted")

    /** errors.rs is_gax_error_retryable. */
    fun is_retryable(): Boolean = when (this) {
        is Http -> is_retryable_code(status)
        is Io -> !is_builder
        is Timeout, is Exhausted -> false
    }

    /** `Aip194Strict.continue_on_too_many_requests().continue_on_client_timeout()` for an idempotent request. */
    fun is_continue(): Boolean = this is Timeout || (this is Http && (status == 429 || status == 503)) || this is Io
}

/**
 * google-cloud-gax retry_loop under `with_time_limit(60 s)` with ExponentialBackoff::default() (the attempt's delay up to
 * 1 s doubling to 60 s, drawn uniformly from zero) and a fresh AdaptiveThrottler::default() (factor 2). [attempt] gets the
 * remaining time and throws [GaxError].
 */
suspend fun <T> retry_loop(random: Random = Random.Default, attempt: suspend (Duration) -> T): T {
    val loop_start = TimeSource.Monotonic.markNow()
    val time_limit = 60.seconds
    var requests = 0.0
    var accepts = 0.0
    var attempt_count = 0
    var retry: Join<Duration, GaxError>? = null
    fun backoff(): Duration {
        val delay = if (attempt_count - 1 >= 6) 60.seconds else (1L shl maxOf(attempt_count - 1, 0)).seconds
        return random.nextLong(0L, delay.inWholeNanoseconds + 1L).nanoseconds
    }
    while (true) {
        val remaining = (time_limit - loop_start.elapsedNow()).coerceAtLeast(Duration.ZERO)
        val pending = retry
        if (pending != null) {
            if (remaining < pending.a) throw GaxError.Exhausted(pending.b)
            delay(pending.a)
            val reject = ((requests - 2.0 * accepts) / (requests + 1.0)).coerceAtLeast(0.0)
            if (random.nextDouble(0.0, 1.0) <= reject) {
                if (loop_start.elapsedNow() >= time_limit) throw GaxError.Exhausted(pending.b)
                retry = backoff() j pending.b
                continue
            }
        }
        attempt_count += 1
        try {
            return attempt(remaining).also {
                requests += 1.0
                accepts += 1.0
            }
        } catch (e: GaxError) {
            val permanent = !e.is_continue()
            val exhausted = !permanent && loop_start.elapsedNow() >= time_limit
            val delay = backoff()
            requests += 1.0
            if (permanent) accepts += 1.0
            if (permanent || exhausted) throw e
            retry = delay j e
        }
    }
}

/**
 * time 0.3 `OffsetDateTime::parse(_, &Rfc3339)`: four-digit year, any one separator byte before the time, a fraction of
 * any length (nanoseconds kept), `Z`/`z` or `±HH:MM`; a second of 60 only as the leap-second stand-in 23:59:60 UTC.
 * Unix seconds j nanoseconds, or null where time refuses it.
 */
fun rfc3339(text: String): Join<Long, Int>? {
    val s = text.encodeToByteArray()
    var at = 0
    fun digits(n: Int): Int? {
        if (at + n > s.size) return null
        var value = 0
        repeat(n) {
            val d = s[at + it] - '0'.code.toByte()
            if (d !in 0..9) return null
            value = value * 10 + d
        }
        at += n
        return value
    }
    fun literal(c: Char): Boolean = (at < s.size && s[at] == c.code.toByte()).also { if (it) at++ }
    val year = digits(4) ?: return null
    if (!literal('-')) return null
    val month = digits(2)?.takeIf { it in 1..12 } ?: return null
    if (!literal('-')) return null
    val day = digits(2)?.takeIf { it >= 1 } ?: return null
    if (at >= s.size) return null
    at++
    val hour = digits(2) ?: return null
    if (!literal(':')) return null
    val minute = digits(2) ?: return null
    if (!literal(':')) return null
    val second = digits(2) ?: return null
    var nanos = 0
    if (literal('.')) {
        var multiplier = 100_000_000
        val first = digits(1) ?: return null
        nanos = first * multiplier
        while (true) {
            val d = digits(1) ?: break
            multiplier /= 10
            nanos += d * multiplier
        }
    }
    var offset = 0
    if (at < s.size && (s[at] == 'Z'.code.toByte() || s[at] == 'z'.code.toByte())) at++
    else {
        val sign = when {
            literal('+') -> 1
            literal('-') -> -1
            else -> return null
        }
        val oh = digits(2)?.takeIf { it <= 23 } ?: return null
        if (!literal(':')) return null
        val om = digits(2)?.takeIf { it <= 59 } ?: return null
        offset = sign * (oh * 3600 + om * 60)
    }
    if (at != s.size) return null
    val leap = year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)
    val month_days = intArrayOf(31, if (leap) 29 else 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31)[month - 1]
    if (day > month_days || hour > 23 || minute > 59 || second > 60) return null
    val y = (if (month <= 2) year - 1 else year).toLong()
    val era = y.floorDiv(400L)
    val yoe = y - era * 400
    val mp = (month + 9) % 12
    val doy = (153 * mp + 2) / 5 + day - 1
    val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
    val days = era * 146_097 + doe - 719_468
    if (second == 60) {
        // is_valid_leap_second_stand_in: 23:59:59.999999999 once the offset is applied.
        val utc = (hour * 3600 + minute * 60 + 59 - offset).mod(86_400)
        if (utc != 86_399) return null
        return (days * 86_400 + hour * 3600 + minute * 60 + 59 - offset) j 999_999_999
    }
    return (days * 86_400 + hour * 3600 + minute * 60 + second - offset) j nanos
}
